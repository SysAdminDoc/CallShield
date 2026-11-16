#!/usr/bin/env python3
"""
How often a reported call shares the consumer's area code, from the FTC's Do
Not Call complaints.

The neighbor-spoof check (SpamHeuristics.isNeighborSpoof) flags a caller whose
area code and exchange match the phone's own number. FTC complaints record the
consumer's area code but not their exchange, so this measures the area code
half of that match, which bounds how often the check can fire on a reported
call. It also works out what share independent caller and consumer area codes
would give, so the report says how far above chance the match is.

Usage:
    python scripts/neighbor_spoof_report.py               # newest 20,000 complaints
    python scripts/neighbor_spoof_report.py --max 50000
    python scripts/neighbor_spoof_report.py --json out.json

Reads FTC_API_KEY from the environment. Without one, DEMO_KEY's 10 requests a
day hold the sample to 400 complaints.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from import_all_sources import (  # noqa: E402
    FTC_API_URL,
    FTC_DEMO_KEY,
    FTC_DEMO_KEY_REQUEST_BUDGET,
    FTC_PAGE_INTERVAL,
    FTC_PAGE_SIZE,
    _get_with_backoff,
    ftc_api_key,
)
from phone_normalization import normalize_nanp_number  # noqa: E402

DEFAULT_MAX_RECORDS = 20_000
# The app's toll-free set (SpamHeuristics.tollFreePrefixes).
TOLL_FREE_AREA_CODES = frozenset({"800", "888", "877", "866", "855", "844", "833"})
# A subject needs this many complaints in the sample to get its own row.
MIN_SUBJECT_COMPLAINTS = 500
AREA_CODE = re.compile(r"[2-9][0-9]{2}")


def complaint_row(record: dict) -> dict | None:
    """The caller's and consumer's area codes from one FTC record, or None when either is missing."""

    attrs = record.get("attributes") or {}
    if not isinstance(attrs, dict):
        return None
    caller = normalize_nanp_number(str(attrs.get("company-phone-number", "") or ""))
    consumer = str(attrs.get("consumer-area-code", "") or "").strip()
    if not caller or not AREA_CODE.fullmatch(consumer):
        return None
    return {
        "id": str(record.get("id", "") or ""),
        "caller_area_code": caller[2:5],
        "consumer_area_code": consumer,
        "subject": str(attrs.get("subject", "") or "").strip() or "No Subject Provided",
        "robocall": str(attrs.get("recorded-message-or-robocall", "") or "").upper() == "Y",
    }


def share(matches: int, total: int) -> float:
    return round(100.0 * matches / total, 1) if total else 0.0


def chance_share(rows: list[dict]) -> float:
    """The same-area-code share if callers were picked without regard to the consumer."""

    if not rows:
        return 0.0
    callers = Counter(row["caller_area_code"] for row in rows)
    consumers = Counter(row["consumer_area_code"] for row in rows)
    total = len(rows)
    expected = sum(count * callers.get(code, 0) for code, count in consumers.items()) / (total * total)
    return round(100.0 * expected, 2)


def summarize(rows: list[dict], min_subject: int = MIN_SUBJECT_COMPLAINTS) -> dict:
    """Same-area-code shares overall, without toll-free callers, by robocall flag and by subject."""

    def same(row: dict) -> bool:
        return row["caller_area_code"] == row["consumer_area_code"]

    local = [row for row in rows if row["caller_area_code"] not in TOLL_FREE_AREA_CODES]
    local_same = sum(1 for row in local if same(row))
    local_chance = chance_share(local)
    subjects = Counter(row["subject"] for row in local)
    by_subject = []
    for subject, count in subjects.most_common():
        if count < min_subject:
            break
        matches = sum(1 for row in local if row["subject"] == subject and same(row))
        by_subject.append({"subject": subject, "complaints": count, "same_area_code_pct": share(matches, count)})
    by_robocall = {}
    for flag, label in ((True, "robocall"), (False, "live caller")):
        group = [row for row in local if row["robocall"] == flag]
        by_robocall[label] = {
            "complaints": len(group),
            "same_area_code_pct": share(sum(1 for row in group if same(row)), len(group)),
        }
    return {
        "complaints": len(rows),
        "same_area_code_pct": share(sum(1 for row in rows if same(row)), len(rows)),
        "excluding_toll_free": {
            "complaints": len(local),
            "same_area_code_pct": share(local_same, len(local)),
            "chance_pct": local_chance,
            "times_chance": round(share(local_same, len(local)) / local_chance, 1) if local_chance else None,
        },
        "by_robocall": by_robocall,
        "by_subject": by_subject,
    }


def fetch_rows(max_records: int, api_key: str) -> tuple[list[dict], int]:
    """The newest complaints with both area codes, read a page at a time at the importer's pace."""

    if api_key == FTC_DEMO_KEY:
        max_records = min(max_records, FTC_DEMO_KEY_REQUEST_BUDGET * FTC_PAGE_SIZE)
    rows: dict[str, dict] = {}
    read = 0
    offset = 0
    while read < max_records:
        started = time.monotonic()
        page_size = min(FTC_PAGE_SIZE, max_records - read)
        params = {"api_key": api_key, "items_per_page": page_size, "offset": offset, "sort_order": "desc"}
        response, failure = _get_with_backoff(FTC_API_URL, params=params, timeout=30, label="FTC")
        if failure or response is None:
            print(f"  FTC stopped after {read:,} records ({failure}); reporting on those")
            break
        response.raise_for_status()
        records = response.json().get("data", [])
        if not isinstance(records, list) or not records:
            break
        read += len(records)
        offset += len(records)
        for record in records:
            row = complaint_row(record)
            # Newest-first paging shifts as complaints arrive, so a record can come back on the next page.
            if row and row["id"] not in rows:
                rows[row["id"]] = row
        if read % 5_000 < page_size:
            print(f"  read {read:,} complaints")
        time.sleep(max(0.0, FTC_PAGE_INTERVAL - (time.monotonic() - started)))
    return list(rows.values()), read


def main() -> None:
    parser = argparse.ArgumentParser(description=(__doc__ or "").strip().split("\n\n")[0])
    parser.add_argument("--max", type=int, default=DEFAULT_MAX_RECORDS, help="complaints to read, newest first")
    parser.add_argument("--json", type=Path, help="also write the summary here")
    args = parser.parse_args()

    rows, read = fetch_rows(args.max, ftc_api_key())
    if not rows:
        sys.exit("No complaints with both area codes came back.")
    summary = summarize(rows)
    summary["records_read"] = read
    local = summary["excluding_toll_free"]
    print(f"\n{read:,} complaints read, {summary['complaints']:,} with both area codes")
    print(f"Same area code as the consumer: {summary['same_area_code_pct']}%")
    print(
        f"Without toll-free callers: {local['same_area_code_pct']}% of {local['complaints']:,}, "
        f"against {local['chance_pct']}% by chance ({local['times_chance']}x)"
    )
    for label, group in summary["by_robocall"].items():
        print(f"  {label}: {group['same_area_code_pct']}% of {group['complaints']:,}")
    print("By subject, without toll-free callers:")
    for row in summary["by_subject"]:
        print(f"  {row['same_area_code_pct']:5.1f}%  {row['complaints']:>7,}  {row['subject']}")
    print("FTC doesn't publish the consumer's exchange, so these shares bound the exchange match from above.")
    if args.json:
        args.json.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
