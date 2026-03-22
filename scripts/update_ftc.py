#!/usr/bin/env python3
"""
FTC Complaint Data Importer for CallShield

Pulls Do Not Call complaint data from the FTC public API and merges it
into the CallShield spam_numbers.json database.

Usage:
    python update_ftc.py              # Default: last 90 days, up to 10,000 records
    python update_ftc.py --days 365   # Last 365 days
    python update_ftc.py --max 50000  # Up to 50,000 records

API docs: https://www.ftc.gov/developer/api/v0/endpoints/do-not-call-dnc-reported-calls-data-api
No API key required (DEMO_KEY used, 1000 req/hr limit).
"""

import json
import sys
import time
import argparse
from datetime import datetime
from pathlib import Path
from collections import Counter

try:
    import requests
except ImportError:
    import subprocess
    subprocess.check_call([sys.executable, "-m", "pip", "install", "requests"])
    import requests

API_BASE = "https://api.ftc.gov/v0/dnc-complaints"
API_KEY = "DEMO_KEY"
PAGE_SIZE = 100  # Max allowed by FTC API
DATA_DIR = Path(__file__).parent.parent / "data"
DB_FILE = DATA_DIR / "spam_numbers.json"

# Map FTC subjects to spam types
SUBJECT_MAP = {
    "reducing your debt": "debt_collector",
    "warranties": "scam",
    "medical": "scam",
    "vacations": "telemarketer",
    "impostor": "scam",
    "government": "scam",
    "computer": "scam",
    "energy": "telemarketer",
    "insurance": "telemarketer",
    "home improvement": "telemarketer",
    "buyers club": "telemarketer",
    "investment": "scam",
    "junk": "robocall",
    "no subject": "robocall",
}


def classify_subject(subject: str) -> str:
    lower = subject.lower()
    for keyword, spam_type in SUBJECT_MAP.items():
        if keyword in lower:
            return spam_type
    return "robocall"


def fetch_ftc_data(max_records: int = 10000) -> list[dict]:
    """Fetch DNC complaints from FTC API (newest first)."""
    print(f"Fetching up to {max_records:,} FTC complaints (newest first)...")

    all_records = []
    offset = 0
    consecutive_errors = 0

    while len(all_records) < max_records:
        params = {
            "api_key": API_KEY,
            "items_per_page": PAGE_SIZE,
            "offset": offset,
            "sort_order": "desc",
        }

        try:
            resp = requests.get(API_BASE, params=params, timeout=30)
            if resp.status_code == 429:
                print("  Rate limited, waiting 60s...")
                time.sleep(60)
                continue
            resp.raise_for_status()
            data = resp.json()
        except Exception as e:
            consecutive_errors += 1
            if consecutive_errors >= 3:
                print(f"  3 consecutive errors, stopping. Last: {e}")
                break
            print(f"  Error (attempt {consecutive_errors}): {e}, retrying...")
            time.sleep(5)
            continue

        consecutive_errors = 0
        records = data.get("data", [])
        if not records:
            break

        all_records.extend(records)
        offset += PAGE_SIZE

        if len(all_records) % 1000 == 0 or len(records) < PAGE_SIZE:
            print(f"  Fetched {len(all_records):,} records...")

        if len(records) < PAGE_SIZE:
            break

        # Be polite to the API
        time.sleep(0.5)

    print(f"Total records fetched: {len(all_records):,}")
    return all_records


def parse_records(records: list[dict]) -> list[dict]:
    """Parse FTC API records into spam number entries."""
    numbers = {}

    for record in records:
        attrs = record.get("attributes", {})
        phone = attrs.get("company-phone-number", "")
        if not phone:
            continue

        # Normalize to +1XXXXXXXXXX
        digits = "".join(c for c in phone if c.isdigit())
        if len(digits) == 10:
            digits = "1" + digits
        if not (len(digits) == 11 and digits.startswith("1")):
            continue
        normalized = f"+{digits}"

        # Extract metadata
        subject = attrs.get("subject", "No Subject Provided")
        spam_type = classify_subject(subject)
        is_robocall = attrs.get("recorded-message-or-robocall", "") == "Y"
        if is_robocall and spam_type == "telemarketer":
            spam_type = "robocall"

        created = attrs.get("created-date", "")[:10]

        if normalized in numbers:
            numbers[normalized]["reports"] += 1
            if created and created > numbers[normalized]["last_seen"]:
                numbers[normalized]["last_seen"] = created
            if created and created < numbers[normalized]["first_seen"]:
                numbers[normalized]["first_seen"] = created
        else:
            desc = subject
            if is_robocall:
                desc = f"[Robocall] {subject}"
            numbers[normalized] = {
                "number": normalized,
                "type": spam_type,
                "reports": 1,
                "first_seen": created or datetime.now().strftime("%Y-%m-%d"),
                "last_seen": created or datetime.now().strftime("%Y-%m-%d"),
                "description": desc,
            }

    return list(numbers.values())


def merge_into_database(new_numbers: list[dict]):
    """Merge FTC numbers into existing database."""
    if DB_FILE.exists():
        with open(DB_FILE) as f:
            db = json.load(f)
    else:
        db = {
            "version": 1,
            "updated": datetime.now().strftime("%Y-%m-%d"),
            "description": "CallShield community spam number database",
            "sources": ["ftc_complaints", "fcc_complaints", "community_reports"],
            "numbers": [],
            "prefixes": [],
        }

    existing = {n["number"]: n for n in db["numbers"]}

    added = 0
    updated = 0
    for entry in new_numbers:
        num = entry["number"]
        if num in existing:
            existing[num]["reports"] += entry["reports"]
            if entry["last_seen"] > existing[num].get("last_seen", ""):
                existing[num]["last_seen"] = entry["last_seen"]
            if entry["first_seen"] < existing[num].get("first_seen", "9999"):
                existing[num]["first_seen"] = entry["first_seen"]
            updated += 1
        else:
            existing[num] = entry
            added += 1

    db["numbers"] = list(existing.values())
    db["version"] += 1
    db["updated"] = datetime.now().strftime("%Y-%m-%d")

    # Sort by reports descending
    db["numbers"].sort(key=lambda x: x.get("reports", 0), reverse=True)

    with open(DB_FILE, "w") as f:
        json.dump(db, f, indent=2)

    print(f"\nDatabase updated:")
    print(f"  Added: {added:,} new numbers")
    print(f"  Updated: {updated:,} existing numbers")
    print(f"  Total: {len(db['numbers']):,} numbers")
    print(f"  Version: {db['version']}")
    print(f"  File size: {DB_FILE.stat().st_size / 1024:.1f} KB")

    types = Counter(n["type"] for n in db["numbers"])
    print(f"\nBy type:")
    for t, count in types.most_common():
        print(f"  {t}: {count:,}")

    # Top 10 most reported
    print(f"\nTop 10 most reported:")
    for n in db["numbers"][:10]:
        print(f"  {n['number']} - {n['reports']} reports - {n['description'][:50]}")


def main():
    parser = argparse.ArgumentParser(description="Import FTC DNC complaints into CallShield")
    parser.add_argument("--max", type=int, default=10000, help="Max records to fetch (default: 10000)")
    args = parser.parse_args()

    print("=== CallShield FTC Data Importer ===\n")

    records = fetch_ftc_data(max_records=args.max)
    if not records:
        print("No records fetched.")
        sys.exit(1)

    numbers = parse_records(records)
    print(f"Parsed {len(numbers):,} unique numbers from {len(records):,} complaints")

    merge_into_database(numbers)
    print("\nDone! Commit and push to update the live database.")


if __name__ == "__main__":
    main()
