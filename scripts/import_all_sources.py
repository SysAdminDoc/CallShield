#!/usr/bin/env python3
"""
CallShield Multi-Source Database Builder

Aggregates spam phone numbers from ALL available free public sources
and merges them into data/spam_numbers.json.

Sources:
  1. FTC Do Not Call API (api.ftc.gov) — no key, DEMO_KEY
  2. FCC Unwanted Calls Dataset (opendata.fcc.gov) — Socrata API, no key
  3. PhoneBlock.net community database — no key
  4. Existing CallShield database — preserves community reports

Usage:
    python import_all_sources.py
    python import_all_sources.py --max 50000
"""

import json
import re
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

DATA_DIR = Path(__file__).parent.parent / "data"
DB_FILE = DATA_DIR / "spam_numbers.json"


def normalize_phone(raw: str) -> str | None:
    digits = re.sub(r'\D', '', raw)
    if len(digits) == 10:
        digits = '1' + digits
    if len(digits) == 11 and digits.startswith('1'):
        return f'+{digits}'
    return None


# ── Source 1: FTC API ──────────────────────────────────────────────────
def fetch_ftc(max_records: int = 5000) -> list[dict]:
    print("\n[FTC Do Not Call API]")
    API_BASE = "https://api.ftc.gov/v0/dnc-complaints"
    numbers = {}
    offset = 0

    while len(numbers) < max_records:
        try:
            resp = requests.get(API_BASE, params={
                "api_key": "DEMO_KEY",
                "items_per_page": 50,
                "offset": offset,
                "sort_order": "desc",
            }, timeout=30)
            if resp.status_code == 429:
                print("  Rate limited, waiting 60s...")
                time.sleep(60)
                continue
            resp.raise_for_status()
            records = resp.json().get("data", [])
            if not records:
                break
        except Exception as e:
            print(f"  Error: {e}")
            break

        for r in records:
            attrs = r.get("attributes", {})
            phone = attrs.get("company-phone-number", "")
            if not phone:
                continue
            normalized = normalize_phone(phone)
            if not normalized:
                continue

            subject = attrs.get("subject", "")
            is_robo = attrs.get("recorded-message-or-robocall", "") == "Y"
            created = attrs.get("created-date", "")[:10]

            if normalized in numbers:
                numbers[normalized]["reports"] += 1
            else:
                numbers[normalized] = {
                    "number": normalized,
                    "type": "robocall" if is_robo else "telemarketer",
                    "reports": 1,
                    "first_seen": created or datetime.now().strftime("%Y-%m-%d"),
                    "last_seen": created or datetime.now().strftime("%Y-%m-%d"),
                    "description": f"FTC: {subject}" if subject else "FTC complaint",
                }

        offset += 50
        if len(records) < 50:
            break
        time.sleep(0.5)

    print(f"  Fetched {len(numbers):,} unique numbers")
    return list(numbers.values())


# ── Source 2: FCC Unwanted Calls (Socrata) ─────────────────────────────
def fetch_fcc(max_records: int = 50000) -> list[dict]:
    print("\n[FCC Unwanted Calls Dataset]")
    numbers = {}
    offset = 0
    batch = 5000

    while offset < max_records:
        try:
            url = f"https://opendata.fcc.gov/resource/vakf-fz8e.json?$limit={batch}&$offset={offset}"
            resp = requests.get(url, timeout=60)
            resp.raise_for_status()
            records = resp.json()
            if not records:
                break
        except Exception as e:
            print(f"  Error at offset {offset}: {e}")
            break

        for r in records:
            phone = r.get("caller_id_number", "") or r.get("advertiser_business_phone_number", "")
            if not phone:
                continue
            normalized = normalize_phone(phone)
            if not normalized:
                continue

            issue = r.get("issue", "Unwanted Calls")
            date = (r.get("issue_date", "") or "")[:10]

            if normalized in numbers:
                numbers[normalized]["reports"] += 1
                if date and date > numbers[normalized]["last_seen"]:
                    numbers[normalized]["last_seen"] = date
            else:
                spam_type = "robocall"
                if "telemarket" in issue.lower():
                    spam_type = "telemarketer"
                elif "text" in issue.lower() or "sms" in issue.lower():
                    spam_type = "sms_spam"
                numbers[normalized] = {
                    "number": normalized,
                    "type": spam_type,
                    "reports": 1,
                    "first_seen": date or datetime.now().strftime("%Y-%m-%d"),
                    "last_seen": date or datetime.now().strftime("%Y-%m-%d"),
                    "description": f"FCC: {issue}",
                }

        offset += batch
        if len(records) < batch:
            break
        print(f"  {offset:,} records processed, {len(numbers):,} unique...")
        time.sleep(0.3)

    print(f"  Fetched {len(numbers):,} unique numbers")
    return list(numbers.values())


# ── Source 3: PhoneBlock.net ───────────────────────────────────────────
def fetch_phoneblock() -> list[dict]:
    print("\n[PhoneBlock.net Community Database]")
    numbers = {}

    # PhoneBlock bulk blocklist requires auth — skip for now
    # Per-number lookup is available without auth and is used in the app
    print("  Bulk blocklist requires auth — skipped")
    print("  (Per-number lookup is available in-app without auth)")

    print(f"  Fetched {len(numbers):,} US numbers")
    return list(numbers.values())


# ── Merge ──────────────────────────────────────────────────────────────
def merge_into_database(all_numbers: list[dict]):
    if DB_FILE.exists():
        with open(DB_FILE) as f:
            db = json.load(f)
    else:
        db = {
            "version": 1,
            "updated": datetime.now().strftime("%Y-%m-%d"),
            "description": "CallShield community spam number database",
            "sources": ["ftc_complaints", "fcc_complaints", "phoneblock", "community_reports"],
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
            if entry.get("last_seen", "") > existing[num].get("last_seen", ""):
                existing[num]["last_seen"] = entry["last_seen"]
            if entry.get("first_seen", "9999") < existing[num].get("first_seen", "9999"):
                existing[num]["first_seen"] = entry["first_seen"]
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

    print(f"\n{'='*50}")
    print(f"Database updated:")
    print(f"  Added:   {added:,}")
    print(f"  Updated: {updated:,}")
    print(f"  Total:   {len(db['numbers']):,}")
    print(f"  Size:    {DB_FILE.stat().st_size / 1024:.1f} KB")
    print(f"  Version: {db['version']}")

    types = Counter(n["type"] for n in db["numbers"])
    print(f"\nBy type:")
    for t, count in types.most_common():
        print(f"  {t}: {count:,}")

    print(f"\nTop 10:")
    for n in db["numbers"][:10]:
        print(f"  {n['number']} — {n['reports']} reports — {n['description'][:50]}")


def main():
    parser = argparse.ArgumentParser(description="Import spam numbers from all sources")
    parser.add_argument("--max", type=int, default=50000, help="Max records per source")
    args = parser.parse_args()

    print("=" * 50)
    print("CallShield Multi-Source Database Builder")
    print("=" * 50)

    all_numbers = []

    # Source 1: FTC
    ftc = fetch_ftc(max_records=min(args.max, 5000))  # FTC caps at ~50 per day
    all_numbers.extend(ftc)

    # Source 2: FCC (biggest source)
    fcc = fetch_fcc(max_records=args.max)
    all_numbers.extend(fcc)

    # Source 3: PhoneBlock
    pb = fetch_phoneblock()
    all_numbers.extend(pb)

    # Deduplicate
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
