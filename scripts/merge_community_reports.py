#!/usr/bin/env python3
"""
Merges community-reported spam numbers from data/reports/ into
the main spam_numbers.json database, then deletes processed files.
"""

import argparse
import json
import os
from collections import Counter
from datetime import datetime, timezone
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
from source_registry import source_health_report

DATA_DIR = Path(os.environ.get("CALLSHIELD_DATA_DIR", Path(__file__).parent.parent / "data"))
DB_FILE = DATA_DIR / "spam_numbers.json"
REPORTS_DIR = Path(os.environ.get("CALLSHIELD_REPORTS_DIR", DATA_DIR / "reports"))
NOT_SPAM_REVIEW_FILE = DATA_DIR / "not_spam_review.json"
SOURCE_SNAPSHOT_FILE = DATA_DIR / "source-snapshot.json"

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


def load_review_candidates() -> list[dict]:
    if not NOT_SPAM_REVIEW_FILE.exists():
        return []
    try:
        payload = json.loads(NOT_SPAM_REVIEW_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    candidates = payload.get("candidates") if isinstance(payload, dict) else None
    if not isinstance(candidates, list):
        return []
    return [candidate for candidate in candidates if isinstance(candidate, dict)]


def canonical_source_ids(entry: dict) -> set[str]:
    source_ids = set()
    for source in entry.get("sources", []):
        if isinstance(source, str) and source.strip():
            source_ids.add("community_reports" if source == COMMUNITY_SOURCE else source)
    for evidence in entry.get("evidence", []):
        if isinstance(evidence, dict) and evidence.get("source_id"):
            source_ids.add(str(evidence["source_id"]))
    return source_ids


def apply_approved_corrections(
    existing: dict[str, dict],
    candidates: list[dict],
    today: str,
) -> tuple[int, int]:
    """Apply only explicit maintainer approvals to community-only rows.

    Anonymous votes create review candidates but do not change the shipped
    database. An operator may set ``approved: true`` in the local review file;
    the next merge then decays the community report count or removes the row.
    Authoritative or mixed-source rows are never changed by this path.
    """

    decayed = 0
    removed = 0
    for candidate in candidates:
        if candidate.get("approved") is not True or candidate.get("applied_at"):
            continue
        number = candidate.get("number")
        entry = existing.get(number) if isinstance(number, str) else None
        if entry is None:
            candidate["skipped_reason"] = "row_missing"
            candidate["reviewed_at"] = today
            continue
        if canonical_source_ids(entry) != {"community_reports"}:
            candidate["skipped_reason"] = "authoritative_source_present"
            candidate["reviewed_at"] = today
            continue
        try:
            votes = max(0, int(candidate.get("not_spam_votes", 0)))
            reports = max(0, int(entry.get("reports", 0)))
        except (TypeError, ValueError):
            candidate["skipped_reason"] = "invalid_counts"
            candidate["reviewed_at"] = today
            continue
        if votes <= 0 or reports <= 0:
            candidate["skipped_reason"] = "no_active_contribution"
            candidate["reviewed_at"] = today
            continue
        remaining = max(0, reports - votes)
        if remaining == 0:
            del existing[number]
            removed += 1
        else:
            entry["reports"] = remaining
            decayed += 1
        candidate["applied_at"] = today
        candidate["applied_not_spam_votes"] = votes
    return decayed, removed


def update_source_health_snapshot(
    database: dict,
    review_candidates: list[dict],
    *,
    quarantined_count: int,
    quarantined_this_run: int,
) -> None:
    if not SOURCE_SNAPSHOT_FILE.exists():
        return
    try:
        snapshot = json.loads(SOURCE_SNAPSHOT_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return
    if not isinstance(snapshot, dict) or not isinstance(snapshot.get("sources"), list):
        return
    snapshot["health"] = source_health_report(
        snapshot,
        database,
        {"candidates": review_candidates},
        quarantined_count=quarantined_count,
        quarantined_this_run=quarantined_this_run,
        generated_at=datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
    )
    atomic_write_json(SOURCE_SNAPSHOT_FILE, snapshot)


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


def main(argv: list[str] | None = None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--apply-reviewed-corrections",
        action="store_true",
        help="apply only review candidates explicitly marked approved: true",
    )
    args = parser.parse_args(argv)
    print("=== Merge Community Reports ===\n")

    if not REPORTS_DIR.exists() and not args.apply_reviewed_corrections:
        print("No reports directory found.")
        return

    report_files = list(REPORTS_DIR.glob("*.json")) if REPORTS_DIR.exists() else []
    if not report_files and not args.apply_reviewed_corrections:
        if DB_FILE.exists():
            try:
                database = json.loads(DB_FILE.read_text(encoding="utf-8"))
                rejected_dir = REPORTS_DIR / "rejected"
                update_source_health_snapshot(
                    database,
                    load_review_candidates(),
                    quarantined_count=len(list(rejected_dir.glob("*.json")))
                    if rejected_dir.exists()
                    else 0,
                    quarantined_this_run=0,
                )
            except (OSError, ValueError):
                pass
        print("No pending reports.")
        return

    if report_files:
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
    unattributed_votes = 0
    collapsed = 0
    rejected = 0
    # Implausible submissions are consumed silently otherwise, so a drain that
    # dropped a third of the queue reads the same as one that dropped nothing.
    # Counting them by raw value keeps the run auditable without reprinting one
    # line per file for a burst of the same fictional number.
    implausible: Counter[str] = Counter()
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
                raw = report.get("number")
                implausible[raw if isinstance(raw, str) and raw else "<missing>"] += 1
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
                    # A vote with no reporter identity cannot be counted as an
                    # independent source, so it is dropped. Tracked separately
                    # from implausible numbers: the number was fine, the
                    # provenance was not, and reporting both under one
                    # "implausible" total hides a stale Worker.
                    unattributed_votes += 1
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

    # Anonymous false-positive votes never mutate the shipped database by
    # default. A distinct-source quorum strictly larger than the spam count can
    # only park a community-only row for maintainer review; rows with any
    # authoritative provenance are not candidates at all.
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
                    "source_ids": ["community_reports"],
                }
            )

    prior_candidates = load_review_candidates()
    by_number = {}
    for candidate in prior_candidates + review_candidates:
        number = candidate.get("number")
        if not isinstance(number, str):
            continue
        merged = dict(by_number.get(number, {}))
        merged.update(candidate)
        by_number[number] = merged
    review_candidates = list(by_number.values())

    decayed, removed = (0, 0)
    if args.apply_reviewed_corrections:
        decayed, removed = apply_approved_corrections(existing, review_candidates, today)

    if review_candidates and (
        review_candidates != prior_candidates or args.apply_reviewed_corrections
    ):
        atomic_write_json(
            NOT_SPAM_REVIEW_FILE,
            {
                "updated": today,
                "count": len(by_number),
                "candidates": review_candidates,
            },
        )

    db["numbers"] = list(existing.values())
    # Only publish a new version when the contents actually changed. The app
    # re-syncs the whole 6.5 MB database whenever `version` moves, so bumping
    # it on a no-op run costs every device a pointless download.
    changed = (
        added > 0
        or updated > 0
        or purged > 0
        or provenance_migrated > 0
        or decayed > 0
        or removed > 0
    )
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

    quarantined_count = len(list(rejected_dir.glob("*.json"))) if rejected_dir.exists() else 0
    update_source_health_snapshot(
        db,
        review_candidates,
        quarantined_count=quarantined_count,
        quarantined_this_run=rejected,
    )

    if implausible:
        print(f"\nRejected as implausible ({skipped} report(s), {len(implausible)} distinct):")
        for raw, count in sorted(implausible.items(), key=lambda item: (-item[1], item[0])):
            print(f"  {raw} x{count}")

    print(
        f"\nMerged: {added} new, {updated} updated, {skipped} skipped (implausible), "
        f"{collapsed} collapsed (duplicate), {unattributed_votes} not_spam votes dropped "
        f"(no reporter identity), {rejected} quarantined, "
        f"{decayed} corrections decayed, {removed} rows removed"
    )
    print(f"Total database: {len(db['numbers'])} numbers")


if __name__ == "__main__":
    main()
