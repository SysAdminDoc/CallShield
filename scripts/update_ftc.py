#!/usr/bin/env python3
"""
FTC Complaint Data Importer for CallShield

Downloads the latest FTC Do Not Call complaint data and merges it into
the CallShield spam_numbers.json database.

Usage:
    python update_ftc.py

The FTC publishes complaint data as CSV files. This script:
1. Downloads the latest FTC DNC complaint CSV
2. Extracts phone numbers and complaint types
3. Merges them into data/spam_numbers.json
"""

import csv
import json
import os
import sys
import io
from datetime import datetime
from pathlib import Path
from collections import Counter

try:
    import requests
except ImportError:
    import subprocess
    subprocess.check_call([sys.executable, "-m", "pip", "install", "requests"])
    import requests

# FTC DNC Complaint data URL (publicly available)
FTC_DATA_URL = "https://www.ftc.gov/sites/default/files/DNC-Complaint-Numbers.csv"
DATA_DIR = Path(__file__).parent.parent / "data"
DB_FILE = DATA_DIR / "spam_numbers.json"
FTC_CSV = DATA_DIR / "ftc_complaints.csv"


def download_ftc_data() -> str:
    """Download latest FTC complaint data."""
    print(f"Downloading FTC complaint data from {FTC_DATA_URL}...")
    response = requests.get(FTC_DATA_URL, timeout=60)
    response.raise_for_status()
    print(f"Downloaded {len(response.content):,} bytes")

    # Save raw CSV
    FTC_CSV.write_bytes(response.content)
    print(f"Saved to {FTC_CSV}")
    return response.text


def parse_ftc_csv(csv_text: str) -> list[dict]:
    """Parse FTC CSV into spam number entries."""
    numbers = {}
    reader = csv.DictReader(io.StringIO(csv_text))

    for row in reader:
        # FTC CSV columns vary but typically include:
        # Company_Phone_Number, Created_Date, Violation_Type, etc.
        phone = None
        for key in ["Company_Phone_Number", "Phone_Number", "Subject_Phone_Number", "phone"]:
            if key in row and row[key]:
                phone = row[key].strip()
                break

        if not phone:
            continue

        # Normalize
        digits = "".join(c for c in phone if c.isdigit())
        if len(digits) == 10:
            digits = "1" + digits
        if len(digits) == 11 and digits.startswith("1"):
            normalized = f"+{digits}"
        else:
            continue  # Skip non-US numbers for now

        # Determine type from violation
        violation = ""
        for key in ["Violation_Type", "Subject", "Type", "violation"]:
            if key in row and row[key]:
                violation = row[key].lower()
                break

        spam_type = "robocall"
        if "telemarket" in violation:
            spam_type = "telemarketer"
        elif "scam" in violation or "fraud" in violation:
            spam_type = "scam"
        elif "survey" in violation or "poll" in violation:
            spam_type = "survey"
        elif "debt" in violation or "collect" in violation:
            spam_type = "debt_collector"

        # Get date
        date_str = ""
        for key in ["Created_Date", "Date", "created_date"]:
            if key in row and row[key]:
                try:
                    dt = datetime.strptime(row[key].strip()[:10], "%Y-%m-%d")
                    date_str = dt.strftime("%Y-%m-%d")
                except (ValueError, IndexError):
                    try:
                        dt = datetime.strptime(row[key].strip()[:10], "%m/%d/%Y")
                        date_str = dt.strftime("%Y-%m-%d")
                    except (ValueError, IndexError):
                        pass
                break

        if normalized in numbers:
            numbers[normalized]["reports"] += 1
            if date_str and date_str > numbers[normalized].get("last_seen", ""):
                numbers[normalized]["last_seen"] = date_str
        else:
            numbers[normalized] = {
                "number": normalized,
                "type": spam_type,
                "reports": 1,
                "first_seen": date_str or datetime.now().strftime("%Y-%m-%d"),
                "last_seen": date_str or datetime.now().strftime("%Y-%m-%d"),
                "description": f"FTC complaint: {violation}" if violation else "FTC reported number"
            }

    return list(numbers.values())


def merge_into_database(new_numbers: list[dict]):
    """Merge FTC numbers into existing database."""
    # Load existing
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
            "prefixes": []
        }

    # Index existing by number
    existing = {n["number"]: n for n in db["numbers"]}

    added = 0
    updated = 0
    for entry in new_numbers:
        num = entry["number"]
        if num in existing:
            # Update report count and last_seen
            existing[num]["reports"] = max(existing[num]["reports"], entry["reports"])
            if entry["last_seen"] > existing[num].get("last_seen", ""):
                existing[num]["last_seen"] = entry["last_seen"]
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

    # Stats
    types = Counter(n["type"] for n in db["numbers"])
    print(f"\nBy type:")
    for t, count in types.most_common():
        print(f"  {t}: {count:,}")


def main():
    print("=== CallShield FTC Data Importer ===\n")

    try:
        csv_text = download_ftc_data()
    except Exception as e:
        print(f"\nFailed to download FTC data: {e}")
        print("The FTC may have changed the URL. Check https://www.ftc.gov/policy/reports/ for the latest.")
        sys.exit(1)

    numbers = parse_ftc_csv(csv_text)
    print(f"Parsed {len(numbers):,} unique numbers from FTC data")

    merge_into_database(numbers)
    print("\nDone! Commit and push to update the live database.")


if __name__ == "__main__":
    main()
