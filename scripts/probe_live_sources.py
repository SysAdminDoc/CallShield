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
"""

from __future__ import annotations

import re
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

ROOT = Path(__file__).resolve().parent.parent
EXTERNAL_LOOKUP = ROOT / "app" / "src" / "main" / "java" / "com" / "sysadmindoc" / "callshield" / "data" / "remote" / "ExternalLookup.kt"
FETCH_TIMEOUT_SECONDS = 20


@dataclass(frozen=True)
class Source:
    name: str
    url_prefix: str
    sample_digits: str
    # Must match what the Kotlin parser treats as "this is a lookup result".
    marker: re.Pattern[str]


SOURCES = (
    Source(
        name="SkipCalls",
        url_prefix="https://spam.skipcalls.app/check/",
        sample_digits="8443218090",
        marker=re.compile(r'"is_spam"\s*:\s*(true|false)'),
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
    return True, "answers in the shape the app parses"


def main() -> int:
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
