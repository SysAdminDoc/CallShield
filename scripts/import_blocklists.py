#!/usr/bin/env python3
"""
Multi-source blocklist aggregator for CallShield.
Fetches spam phone numbers from multiple open-source blocklist repos
and community databases, then merges into data/spam_numbers.json.

Usage:
    python import_blocklists.py
"""

import json
import re
import sys
from datetime import datetime
from pathlib import Path
from collections import Counter

try:
    import requests
except ImportError:
    import subprocess
    subprocess.check_call([sys.executable, "-m", "pip", "install", "requests"])
    import requests

DATA_DIR = Path(__file__).parent.parent / "data"
DB_FILE = DATA_DIR / "spam_numbers.json"

# Phone number extraction regex
PHONE_RE = re.compile(r'\+?1?\s*\(?(\d{3})\)?[\s\-]*(\d{3})[\s\-]*(\d{4})')

# Sources: publicly accessible blocklist data
SOURCES = [
    {
        "name": "FCC_CGB_Complaints",
        "url": "https://opendata.fcc.gov/api/views/sr6c-syda/rows.json?accessType=DOWNLOAD",
        "type": "json",
        "parser": "fcc_json",
    },
]


def normalize_phone(raw: str) -> str | None:
    """Normalize to +1XXXXXXXXXX format."""
    digits = re.sub(r'\D', '', raw)
    if len(digits) == 10:
        digits = '1' + digits
    if len(digits) == 11 and digits.startswith('1'):
        return f'+{digits}'
    return None


def fetch_fcc_json(url: str) -> list[dict]:
    """Parse FCC open data JSON format."""
    numbers = {}
    print(f"  Fetching FCC data...")
    try:
        resp = requests.get(url, timeout=120)
        resp.raise_for_status()
        data = resp.json()
    except Exception as e:
        print(f"  Failed: {e}")
        return []

    rows = data.get("data", [])
    print(f"  Processing {len(rows):,} FCC complaint rows...")

    for row in rows:
        # FCC JSON structure: row is a list, phone number is typically at index 8-12
        phone = None
        subject = "FCC complaint"
        for field in row:
            if isinstance(field, str):
                match = PHONE_RE.search(field)
                if match:
                    phone = f"{match.group(1)}{match.group(2)}{match.group(3)}"
                    break

        if not phone:
            continue

        normalized = normalize_phone(phone)
        if not normalized:
            continue

        if normalized in numbers:
            numbers[normalized]["reports"] += 1
        else:
            numbers[normalized] = {
                "number": normalized,
                "type": "robocall",
                "reports": 1,
                "first_seen": datetime.now().strftime("%Y-%m-%d"),
                "last_seen": datetime.now().strftime("%Y-%m-%d"),
                "description": subject,
            }

    return list(numbers.values())


def fetch_text_list(url: str, source_name: str) -> list[dict]:
    """Fetch a plain text list of phone numbers (one per line)."""
    numbers = {}
    print(f"  Fetching {source_name}...")
    try:
        resp = requests.get(url, timeout=60)
        resp.raise_for_status()
    except Exception as e:
        print(f"  Failed: {e}")
        return []

    for line in resp.text.splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        normalized = normalize_phone(line)
        if normalized:
            if normalized not in numbers:
                numbers[normalized] = {
                    "number": normalized,
                    "type": "robocall",
                    "reports": 1,
                    "first_seen": datetime.now().strftime("%Y-%m-%d"),
                    "last_seen": datetime.now().strftime("%Y-%m-%d"),
                    "description": f"From {source_name}",
                }

    return list(numbers.values())


def merge_into_database(all_numbers: list[dict]):
    """Merge new numbers into existing database."""
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
    for entry in all_numbers:
        num = entry["number"]
        if num in existing:
            existing[num]["reports"] += entry["reports"]
            if entry["last_seen"] > existing[num].get("last_seen", ""):
                existing[num]["last_seen"] = entry["last_seen"]
            updated += 1
        else:
            existing[num] = entry
            added += 1

    db["numbers"] = list(existing.values())
    db["version"] += 1
    db["updated"] = datetime.now().strftime("%Y-%m-%d")
    db["numbers"].sort(key=lambda x: x.get("reports", 0), reverse=True)

    with open(DB_FILE, "w") as f:
        json.dump(db, f, indent=2)

    print(f"\nDatabase updated:")
    print(f"  Added: {added:,}")
    print(f"  Updated: {updated:,}")
    print(f"  Total: {len(db['numbers']):,}")
    print(f"  Size: {DB_FILE.stat().st_size / 1024:.1f} KB")

    types = Counter(n["type"] for n in db["numbers"])
    print(f"\nBy type:")
    for t, count in types.most_common():
        print(f"  {t}: {count:,}")


def main():
    print("=== CallShield Multi-Source Blocklist Aggregator ===\n")

    all_numbers = []

    for source in SOURCES:
        print(f"\n[{source['name']}]")
        if source.get("parser") == "fcc_json":
            nums = fetch_fcc_json(source["url"])
        elif source["type"] == "text":
            nums = fetch_text_list(source["url"], source["name"])
        else:
            print(f"  Unknown parser type: {source.get('parser')}")
            continue

        print(f"  Got {len(nums):,} numbers")
        all_numbers.extend(nums)

    if not all_numbers:
        print("\nNo numbers fetched from any source.")
        sys.exit(1)

    # Deduplicate across sources
    deduped = {}
    for n in all_numbers:
        num = n["number"]
        if num in deduped:
            deduped[num]["reports"] += n["reports"]
        else:
            deduped[num] = n

    print(f"\nTotal unique numbers from all sources: {len(deduped):,}")
    merge_into_database(list(deduped.values()))
    print("\nDone! Commit and push to update the live database.")


if __name__ == "__main__":
    main()
