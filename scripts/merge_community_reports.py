#!/usr/bin/env python3
"""
Merges community-reported spam numbers from data/reports/ into
the main spam_numbers.json database, then deletes processed files.
"""

import json
import os
from datetime import datetime
from pathlib import Path

DATA_DIR = Path(__file__).parent.parent / "data"
DB_FILE = DATA_DIR / "spam_numbers.json"
REPORTS_DIR = DATA_DIR / "reports"


def main():
    print("=== Merge Community Reports ===\n")

    if not REPORTS_DIR.exists():
        print("No reports directory found.")
        return

    report_files = list(REPORTS_DIR.glob("*.json"))
    if not report_files:
        print("No pending reports.")
        return

    print(f"Found {len(report_files)} report files")

    # Load existing database
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

    for report_file in report_files:
        try:
            with open(report_file) as f:
                report = json.load(f)

            number = report.get("number", "")
            if not number:
                continue

            spam_type = report.get("type", "unknown")
            reported_at = report.get("reported_at", datetime.now().strftime("%Y-%m-%d"))[:10]

            # Handle false positive reports — subtract votes
            if spam_type == "not_spam":
                if number in existing:
                    existing[number]["reports"] = max(0, existing[number]["reports"] - 1)
                    # Remove from database if reports drop to 0
                    if existing[number]["reports"] <= 0:
                        del existing[number]
                        print(f"  Removed {number} (false positive)")
                updated += 1
            elif number in existing:
                existing[number]["reports"] += 1
                if reported_at > existing[number].get("last_seen", ""):
                    existing[number]["last_seen"] = reported_at
                updated += 1
            else:
                existing[number] = {
                    "number": number,
                    "type": spam_type,
                    "reports": 1,
                    "first_seen": reported_at,
                    "last_seen": reported_at,
                    "description": "Community reported",
                }
                added += 1

            # Delete processed report
            os.remove(report_file)

        except Exception as e:
            print(f"  Error processing {report_file.name}: {e}")

    db["numbers"] = list(existing.values())
    db["version"] += 1
    db["updated"] = datetime.now().strftime("%Y-%m-%d")
    db["numbers"].sort(key=lambda x: x.get("reports", 0), reverse=True)

    with open(DB_FILE, "w") as f:
        json.dump(db, f, indent=2)

    # Remove reports dir if empty
    if REPORTS_DIR.exists() and not list(REPORTS_DIR.iterdir()):
        REPORTS_DIR.rmdir()

    print(f"\nMerged: {added} new, {updated} updated")
    print(f"Total database: {len(db['numbers'])} numbers")


if __name__ == "__main__":
    main()
