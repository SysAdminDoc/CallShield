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

from pipeline_io import (
    atomic_write_json,
    report_queue_digest,
    require_matching_derived_feed,
)
from report_dedup import (
    find_burst_duplicates,
    parse_reported_at,
    reporter_day_key,
    validated_reporter_bucket,
)

DATA_DIR = Path(os.environ.get("CALLSHIELD_DATA_DIR", Path(__file__).parent.parent / "data"))
DB_FILE = DATA_DIR / "spam_numbers.json"
REPORTS_DIR = Path(os.environ.get("CALLSHIELD_REPORTS_DIR", DATA_DIR / "reports"))
NOT_SPAM_REVIEW_FILE = DATA_DIR / "not_spam_review.json"

COMMUNITY_DESCRIPTION = "Community reported"
COMMUNITY_SOURCE = "community"
LEGACY_SOURCE = "legacy_import"


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

    report_digest = report_queue_digest(REPORTS_DIR)
    for derived_file in (
        DATA_DIR / "hot_numbers.json",
        DATA_DIR / "hot_ranges.json",
        DATA_DIR / "spam_domains.json",
    ):
        require_matching_derived_feed(derived_file, report_digest=report_digest)

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

    # Migrate legacy rows to an explicit provenance field once. Human-readable
    # descriptions are presentation text and must never decide whether an
    # anonymous vote can weaken an authoritative entry.
    provenance_migrated = 0
    for entry in db["numbers"]:
        sources = entry.get("sources")
        if isinstance(sources, list) and all(isinstance(source, str) for source in sources):
            continue
        entry["sources"] = [
            COMMUNITY_SOURCE if entry.get("description") == COMMUNITY_DESCRIPTION else LEGACY_SOURCE
        ]
        provenance_migrated += 1

    # Repeat submissions of the same number+verdict seconds apart are one
    # reporter, not corroboration. Counting them inflates the shipped `reports`
    # value and, for not_spam, lets a single voter de-list a genuine community
    # row one vote at a time. See report_dedup for why the Worker's own dedup
    # cannot be relied on. Unreadable files are ignored here and quarantined by
    # the main loop below.
    burst_candidates = []
    identity_duplicates = set()
    seen_reporter_days = set()
    for report_file in report_files:
        try:
            with open(report_file) as f:
                peek = json.load(f)
        except (OSError, ValueError):
            continue
        peeked_number = validated_report_number(peek.get("number", ""))
        if not peeked_number:
            continue
        spam_type = peek.get("type", "unknown")
        reported_at = parse_reported_at(peek.get("reported_at"))
        reporter_bucket = validated_reporter_bucket(peek.get("reporter_bucket"))
        day_key = reporter_day_key(reporter_bucket, reported_at)
        if day_key is None:
            burst_candidates.append(((peeked_number, spam_type), reported_at, report_file.name))
        else:
            identity_key = (peeked_number, spam_type, *day_key)
            if identity_key in seen_reporter_days:
                identity_duplicates.add(report_file.name)
            else:
                seen_reporter_days.add(identity_key)
    burst_duplicates = find_burst_duplicates(burst_candidates)

    existing = {n["number"]: n for n in db["numbers"]}
    added = 0
    updated = 0
    skipped = 0
    collapsed = 0
    rejected = 0
    processed_files = []
    not_spam_votes: dict[str, set[str]] = {}
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

            if report_file.name in burst_duplicates or report_file.name in identity_duplicates:
                # Same number and verdict as a report already counted seconds
                # earlier. Consume the file so it does not linger in the queue.
                processed_files.append(report_file)
                collapsed += 1
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
                reporter_bucket = validated_reporter_bucket(report.get("reporter_bucket"))
                if reporter_bucket:
                    not_spam_votes.setdefault(number, set()).add(reporter_bucket)
                else:
                    skipped += 1
            elif number in existing:
                existing[number]["reports"] += 1
                sources = set(existing[number].get("sources", []))
                sources.add(COMMUNITY_SOURCE)
                existing[number]["sources"] = sorted(sources)
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
                    "sources": [COMMUNITY_SOURCE],
                }
                added += 1

            processed_files.append(report_file)

        except Exception as e:  # noqa: BLE001 - malformed report content
            print(f"  Quarantining malformed {report_file.name}: {e}")
            quarantine(report_file, rejected_dir)
            rejected += 1

    # Anonymous false-positive votes never mutate the shipped database. A
    # distinct-source quorum strictly larger than the spam count can only park
    # a community-only row for maintainer review; rows with any authoritative
    # provenance are not candidates at all.
    review_candidates = []
    for number, reporters in sorted(not_spam_votes.items()):
        entry = existing.get(number)
        if entry is None or set(entry.get("sources", [])) != {COMMUNITY_SOURCE}:
            continue
        report_count = max(0, int(entry.get("reports", 0)))
        if len(reporters) > report_count:
            review_candidates.append(
                {
                    "number": number,
                    "not_spam_votes": len(reporters),
                    "spam_reports": report_count,
                    "reported_at": today,
                }
            )

    if review_candidates:
        prior_candidates = []
        if NOT_SPAM_REVIEW_FILE.exists():
            try:
                prior = json.loads(NOT_SPAM_REVIEW_FILE.read_text(encoding="utf-8"))
                if isinstance(prior, dict) and isinstance(prior.get("candidates"), list):
                    prior_candidates = prior["candidates"]
            except (OSError, ValueError):
                prior_candidates = []
        by_number = {
            candidate["number"]: candidate
            for candidate in prior_candidates + review_candidates
            if isinstance(candidate, dict) and isinstance(candidate.get("number"), str)
        }
        atomic_write_json(
            NOT_SPAM_REVIEW_FILE,
            {
                "updated": today,
                "count": len(by_number),
                "candidates": list(by_number.values()),
            },
        )

    db["numbers"] = list(existing.values())
    # Only publish a new version when the contents actually changed. The app
    # re-syncs the whole 6.5 MB database whenever `version` moves, so bumping
    # it on a no-op run costs every device a pointless download.
    changed = added > 0 or updated > 0 or purged > 0 or provenance_migrated > 0
    if changed:
        db["version"] += 1
        db["updated"] = today
        db["numbers"].sort(key=lambda x: x.get("reports", 0), reverse=True)
        atomic_write_json(DB_FILE, db)
    else:
        print("No changes — database version left at", db["version"])

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
