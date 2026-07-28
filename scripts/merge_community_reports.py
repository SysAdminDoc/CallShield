#!/usr/bin/env python3
"""
Merges community-reported spam numbers from data/reports/ into
the main spam_numbers.json database, then deletes processed files.
"""

import json
import os
from datetime import datetime
from pathlib import Path
from phone_normalization import is_plausible_number, validated_report_number

DATA_DIR = Path(os.environ.get("CALLSHIELD_DATA_DIR", Path(__file__).parent.parent / "data"))
DB_FILE = DATA_DIR / "spam_numbers.json"
REPORTS_DIR = Path(os.environ.get("CALLSHIELD_REPORTS_DIR", DATA_DIR / "reports"))

COMMUNITY_DESCRIPTION = "Community reported"


def atomic_write_json(path: Path, payload: dict) -> None:
    """Write JSON via a temp file + os.replace so a crash mid-write can never
    leave a truncated database that a later auto-commit would publish."""
    tmp = path.with_suffix(path.suffix + ".tmp")
    with open(tmp, "w") as f:
        json.dump(payload, f, indent=2)
    with open(tmp) as f:  # validate it re-parses before swapping into place
        json.load(f)
    os.replace(tmp, path)


def quarantine(report_file: Path, rejected_dir: Path) -> None:
    """Move an unreadable/malformed report out of the active queue so it is
    not silently reprocessed (and re-erroring) on every future run."""
    try:
        rejected_dir.mkdir(parents=True, exist_ok=True)
        report_file.rename(rejected_dir / report_file.name)
    except OSError:
        pass


def sanitize_dates(db: dict, today: str) -> None:
    """Clamp structurally-impossible first_seen/last_seen values that older
    imports introduced (years far in the past or future). The entry is kept —
    only the dates are repaired — so the hot-list recency filter and any date
    display stay sane."""
    lo, hi = "2000-01-01", today
    for entry in db.get("numbers", []):
        fs = entry.get("first_seen", "")
        ls = entry.get("last_seen", "")
        fs_ok = lo <= fs <= hi
        ls_ok = lo <= ls <= hi
        if not fs_ok:
            entry["first_seen"] = ls if ls_ok else lo
        if not ls_ok:
            fs2 = entry.get("first_seen", "")
            entry["last_seen"] = fs2 if lo <= fs2 <= hi else hi


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

    today = datetime.now().strftime("%Y-%m-%d")
    sanitize_dates(db, today)

    # Self-heal: drop fictional/implausible rows that older bulk imports let in
    # (e.g. FCC-complaint entries with area/exchange 555). The report ingest
    # path already validates, but these predate that guard.
    before = len(db["numbers"])
    db["numbers"] = [n for n in db["numbers"] if is_plausible_number(n.get("number", ""))]
    purged = before - len(db["numbers"])
    if purged:
        print(f"Purged {purged} implausible existing rows")

    existing = {n["number"]: n for n in db["numbers"]}
    added = 0
    updated = 0
    skipped = 0
    rejected = 0
    processed_files = []
    rejected_dir = REPORTS_DIR / "rejected"

    for report_file in report_files:
        try:
            with open(report_file) as f:
                report = json.load(f)
        except (OSError, ValueError) as e:
            print(f"  Quarantining unreadable {report_file.name}: {e}")
            quarantine(report_file, rejected_dir)
            rejected += 1
            continue

        try:
            number = validated_report_number(report.get("number", ""))
            if not number:
                # Junk / fictional / malformed number — drop the noise report.
                processed_files.append(report_file)
                skipped += 1
                continue

            spam_type = report.get("type", "unknown")
            reported_raw = report.get("reported_at")
            if not isinstance(reported_raw, str) or not reported_raw:
                reported_raw = today
            reported_at = reported_raw[:10]

            # Handle false-positive reports — subtract votes.
            # SECURITY: anonymous not_spam votes may only weaken COMMUNITY rows.
            # Authoritative FCC/FTC entries are immune to anonymous removal,
            # otherwise a stream of not_spam reports could de-list real spammers.
            if spam_type == "not_spam":
                entry = existing.get(number)
                if entry is not None and entry.get("description") == COMMUNITY_DESCRIPTION:
                    entry["reports"] = max(0, entry["reports"] - 1)
                    if entry["reports"] <= 0:
                        del existing[number]
                        print(f"  Removed {number} (community false positive)")
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
                    "description": COMMUNITY_DESCRIPTION,
                }
                added += 1

            processed_files.append(report_file)

        except Exception as e:  # noqa: BLE001 - malformed report content
            print(f"  Quarantining malformed {report_file.name}: {e}")
            quarantine(report_file, rejected_dir)
            rejected += 1

    db["numbers"] = list(existing.values())
    db["version"] += 1
    db["updated"] = today
    db["numbers"].sort(key=lambda x: x.get("reports", 0), reverse=True)

    atomic_write_json(DB_FILE, db)

    # Delete processed report files only after DB is safely persisted
    for report_file in processed_files:
        try:
            os.remove(report_file)
        except OSError:
            pass

    # Remove reports dir if empty (rejected/ subdir keeps it around if any)
    if REPORTS_DIR.exists() and not list(REPORTS_DIR.iterdir()):
        REPORTS_DIR.rmdir()

    print(f"\nMerged: {added} new, {updated} updated, {skipped} skipped (implausible), {rejected} quarantined")
    print(f"Total database: {len(db['numbers'])} numbers")


if __name__ == "__main__":
    main()
