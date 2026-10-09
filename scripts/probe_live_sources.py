#!/usr/bin/env python3
"""Check that every live caller-lookup source still answers the way the app parses it.

Free lookup services die quietly. On 2026-09-21 three of the four the app
queried were gone: OpenCNAM and PhoneBlock's hash endpoint started demanding
accounts (HTTP 401), and WhoCalledMe's domain was parked, serving a 200 page
that the app's parser read as "no reports, clean". The one survivor, SkipCalls,
had changed its field name, so every spam verdict it gave was read as clean too.

For each source the app calls (data/remote/ExternalLookup.kt), this fetches a
sample number and checks the HTTP status and the marker field the Kotlin parser
keys on. Exit status is non-zero when any source stops answering in that shape.

--sample measures what SkipCalls adds: it asks about numbers drawn from the
published database, from the unpublished community pending pool and from
well-known business lines, and counts how often SkipCalls names a spam
category the app acts on and how often that number isn't already in the
local database.
"""

from __future__ import annotations

import argparse
import http.client
import json
import random
import re
import sys
import time
import urllib.error
import urllib.request
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EXTERNAL_LOOKUP = ROOT / "app" / "src" / "main" / "java" / "com" / "sysadmindoc" / "callshield" / "data" / "remote" / "ExternalLookup.kt"
FETCH_TIMEOUT_SECONDS = 20
DATA = ROOT / "data"


@dataclass(frozen=True)
class Source:
    name: str
    url_prefix: str
    sample_digits: str
    # Must match what the Kotlin parser treats as "this is a lookup result".
    marker: re.Pattern[str]
    # A number the source is known to flag, and the verdict the parser reads as
    # spam. A clean sample alone can't tell a working source from one whose spam
    # answers moved to a shape the app reads as clean, which is how SkipCalls
    # verdicts were lost before.
    spam_sample_digits: str
    spam_marker: re.Pattern[str]


SOURCES = (
    Source(
        name="SkipCalls",
        url_prefix="https://spam.skipcalls.app/check/",
        sample_digits="8443218090",
        marker=re.compile(r'"is_spam"\s*:\s*(true|false)'),
        spam_sample_digits="8333041447",
        spam_marker=re.compile(
            r'(?=.*"is_spam"\s*:\s*true)(?=.*"status_description"\s*:\s*"(?:scam|robocall|telemarketer|fraud)")',
            re.IGNORECASE | re.DOTALL,
        ),
    ),
)

Fetch = Callable[[str], tuple[int, str]]


def fetch(url: str) -> tuple[int, str]:
    request = urllib.request.Request(url, headers={"User-Agent": "CallShield/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=FETCH_TIMEOUT_SECONDS) as response:
            return response.status, response.read(64 * 1024).decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, ""


def app_source_urls(source_code: str) -> set[str]:
    """Every https URL prefix the app's lookup code builds a request from."""
    return set(re.findall(r'"(https://[^"$]+)', source_code))


def check(source: Source, fetcher: Fetch) -> tuple[bool, str]:
    try:
        status, body = fetcher(source.url_prefix + source.sample_digits)
    except OSError as error:
        return False, f"unreachable: {error}"
    if status != 200:
        return False, f"HTTP {status}"
    if not source.marker.search(body):
        return False, "answered without the field the app parses; first bytes: " + body[:120].replace("\n", " ")
    try:
        status, body = fetcher(source.url_prefix + source.spam_sample_digits)
    except OSError as error:
        return False, f"unreachable for the known spam sample: {error}"
    if status != 200 or not source.spam_marker.search(body):
        return False, (
            f"known spam number {source.spam_sample_digits} no longer reads as spam (HTTP {status}); "
            "first bytes: " + body[:120].replace("\n", " ")
        )
    return True, "answers in the shape the app parses, and still flags a known spam number"


# Well-known US customer-service lines. SkipCalls naming one of these as spam
# would be a false alarm the app shows to the user.
BUSINESS_LINES = (
    "+18002752273",  # Apple Support
    "+18008291040",  # IRS
    "+18007721213",  # Social Security
    "+18006334227",  # Medicare
    "+18002758777",  # USPS
    "+18007425877",  # UPS
    "+18004633339",  # FedEx
    "+18002255345",  # DHL Express
    "+18882804331",  # Amazon
    "+18004321000",  # Bank of America
    "+18009359935",  # Chase
    "+18008693557",  # Wells Fargo
    "+18009505114",  # Citi
    "+18002274825",  # Capital One
    "+18003472683",  # Discover
    "+18005284800",  # American Express
    "+18882211161",  # PayPal
    "+18009346489",  # Xfinity
    "+18009220204",  # Verizon
    "+18003310500",  # AT&T
    "+18009378997",  # T-Mobile
    "+18002211212",  # Delta
    "+18008648331",  # United
    "+18004337300",  # American Airlines
    "+18004359792",  # Southwest
    "+18006427676",  # Microsoft
    "+18006249897",  # Dell
    "+18004746836",  # HP
    "+18882378289",  # Best Buy
    "+18009256278",  # Walmart
    "+18005913869",  # Target
    "+18004663337",  # Home Depot
    "+18004456937",  # Lowe's
    "+18007742678",  # Costco
    "+18007467287",  # CVS
    "+18009254733",  # Walgreens
    "+18002077847",  # GEICO
    "+18007828332",  # State Farm
    "+18007764737",  # Progressive
    "+18002557828",  # Allstate
    "+18005315000",  # DirecTV
    "+18003333474",  # DISH
    "+18883973742",  # Experian
    "+18009168800",  # TransUnion
    "+18773824357",  # FTC
    "+18008722657",  # U.S. Bank
    "+18887622265",  # PNC
    "+18887519000",  # TD Bank
    "+18008472911",  # Visa
    "+18006278372",  # Mastercard
)

SKIPCALLS_FLAGGED_CATEGORIES = frozenset({"scam", "robocall", "telemarketer", "fraud"})


@dataclass(frozen=True)
class Answer:
    number: str
    # "flagged" (a category the app acts on), "uncategorized" (is_spam with any
    # other category, which the app shows as neutral), "clean" or "error".
    verdict: str


def classify(status: int, body: str) -> str:
    if status != 200:
        return "error"
    try:
        parsed = json.loads(body)
    except ValueError:
        return "error"
    if not isinstance(parsed, dict) or not isinstance(parsed.get("is_spam"), bool):
        return "error"
    if not parsed["is_spam"]:
        return "clean"
    category = str(parsed.get("status_description", "")).strip().lower()
    return "flagged" if category in SKIPCALLS_FLAGGED_CATEGORIES else "uncategorized"


def summarize(name: str, answers: list[Answer], local_numbers: set[str], local_prefixes: Iterable[str] = ()) -> dict:
    """One group's counts. `adds` is a flag for a number the local database
    doesn't block, neither as a row nor under one of its prefix rules."""
    prefixes = tuple(local_prefixes)
    flagged = [a for a in answers if a.verdict == "flagged"]
    adds = [a for a in flagged if a.number not in local_numbers and not a.number.startswith(prefixes)]
    answered = [a for a in answers if a.verdict != "error"]
    return {
        "group": name,
        "asked": len(answers),
        "answered": len(answered),
        "flagged": len(flagged),
        "adds": len(adds),
        "uncategorized": sum(1 for a in answers if a.verdict == "uncategorized"),
        "errors": len(answers) - len(answered),
        "add_examples": [a.number for a in adds[:5]],
    }


def load_database() -> tuple[set[str], list[str]]:
    """The published numbers and prefix rules."""
    database = json.loads((DATA / "spam_numbers.json").read_text(encoding="utf-8"))
    return {row["number"] for row in database["numbers"]}, [row["prefix"] for row in database.get("prefixes", [])]


def pending_numbers(records: dict[str, dict], published: set[str]) -> list[str]:
    """Numbers users reported as spam that aren't published yet. The ledger
    also holds rows only a Not spam report created, and rows someone has
    since called not spam; neither is a spam report waiting for corroboration."""
    return sorted(
        number
        for number, record in records.items()
        if not record.get("published")
        and number not in published
        and (record.get("events") or record.get("watch_events"))
        and not record.get("not_spam_days")
    )


def ask_all(numbers: Iterable[str], fetcher: Fetch, delay_seconds: float) -> list[Answer]:
    answers = []
    for index, number in enumerate(numbers):
        if index and delay_seconds:
            time.sleep(delay_seconds)
        digits = re.sub(r"\D", "", number)
        try:
            status, body = fetcher(SOURCES[0].url_prefix + digits)
        except (OSError, http.client.HTTPException):
            # A cut-off body or a garbled status line costs one answer, not the run.
            status, body = 0, ""
        answers.append(Answer(number, classify(status, body)))
    return answers


def sample(fetcher: Fetch, seed: int, per_group: int, delay_seconds: float) -> list[dict]:
    published, prefixes = load_database()
    ledger = json.loads((DATA / "community_pending.json").read_text(encoding="utf-8"))
    pending = pending_numbers(ledger["numbers"], published)
    rng = random.Random(seed)
    groups = {
        "database": rng.sample(sorted(published), min(per_group, len(published))),
        "pending": rng.sample(pending, min(per_group, len(pending))),
        "business": list(BUSINESS_LINES),
    }
    return [summarize(name, ask_all(numbers, fetcher, delay_seconds), published, prefixes) for name, numbers in groups.items()]


def percent(count: int, total: int) -> str:
    return f"{100 * count / total:.1f}%" if total else "n/a"


def print_summaries(summaries: list[dict]) -> None:
    for s in summaries:
        print(
            f"{s['group']:<9} asked {s['asked']:>3}, answered {s['answered']:>3}: "
            f"flagged {s['flagged']:>3} ({percent(s['flagged'], s['answered'])}), "
            f"adds to the local database {s['adds']:>3} ({percent(s['adds'], s['answered'])}), "
            f"is_spam without a category the app acts on {s['uncategorized']:>3}, errors {s['errors']}"
        )
        if s["add_examples"]:
            print(f"          adds, first few: {', '.join(s['add_examples'])}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Check or measure the live caller-lookup sources.")
    parser.add_argument("--sample", action="store_true", help="measure what SkipCalls adds instead of checking its shape")
    parser.add_argument("--per-group", type=int, default=200, help="numbers drawn from the database and the pending pool")
    parser.add_argument("--seed", type=int, default=20260930)
    parser.add_argument("--delay", type=float, default=0.5, help="seconds between requests")
    args = parser.parse_args()
    if args.sample:
        print_summaries(sample(fetch, args.seed, args.per_group, args.delay))
        return 0

    configured = app_source_urls(EXTERNAL_LOOKUP.read_text(encoding="utf-8"))
    probed = {source.url_prefix for source in SOURCES}
    failures = 0
    for url in sorted(configured - probed):
        print(f"FAIL {url}: the app calls this source but nothing here probes it")
        failures += 1
    for source in SOURCES:
        ok, detail = check(source, fetch)
        print(f"{'ok  ' if ok else 'FAIL'} {source.name}: {detail}")
        failures += 0 if ok else 1
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
