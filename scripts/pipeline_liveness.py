#!/usr/bin/env python3
"""Liveness checks for the community-report pipeline.

Every other pipeline suite tests correctness: given a queue, does the merge
produce the right rows. None of them notice when the queue stops being
consumed at all. Between 2026-08-24 and 2026-09-04 sixty reports accumulated in
`data/reports/` while `hot_numbers.json`, `hot_ranges.json`, and
`spam_domains.json` published `"count": 0`, and all fourteen suites stayed
green throughout, because each one was asked a question about a queue it was
handed rather than about the queue that actually exists.

`ensure_feed_not_collapsed` cannot cover this either: its relative floor is a
ratio of the *previous* feed, so once a feed is empty every later empty feed
clears the bar.

Three signals separate a healthy queue from a stalled one:

* **Depth.** Reports are consumed by a merge. A queue deeper than a normal
  inter-merge arrival rate means no merge has run.
* **Age against the database.** A report still sitting in the queue long after
  the database it should have entered was published was skipped, not pending.
* **Reporter identity.** The hot-list and spam-domain promotions need
  `reporter_bucket` to count independent sources. A queue where most reports
  lack it cannot promote anything no matter how many reports arrive, which is
  the exact shape of a Worker deployment that predates the field.

An empty queue is healthy: nothing has arrived, nothing is stuck.

With `--scheduled` (the weekly workflow) the age check measures against the
current time instead of the database date, and upstream sources with a regular
cadence are checked against `stale_after_days`. Both are left out of
`verifyPipelineTests`: that task runs inside `check`, where a clock would fail
every later build of an old tag. Measured against the database date, the age
check cannot fire in the weeks after a drain, because every report queued since
is newer than the database; that is how a second stall ran from 2026-09-05
without the gate noticing.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

from report_dedup import parse_reported_at, validated_reporter_bucket

DATA_DIR = Path(os.environ.get("CALLSHIELD_DATA_DIR", Path(__file__).parent.parent / "data"))
REPORTS_DIR = Path(os.environ.get("CALLSHIELD_REPORTS_DIR", DATA_DIR / "reports"))
DB_FILE = DATA_DIR / "spam_numbers.json"
MANIFEST_FILE = DATA_DIR / "source-manifest.json"
FRESHNESS_FILE = DATA_DIR / "source-freshness.json"

MAX_QUEUE_DEPTH = 20
MAX_QUEUE_AGE_DAYS = 7
MIN_BUCKET_COVERAGE = 0.5
# Bucket-less reports are a supported input: the pipeline merges them into the
# database at reports:1 and simply never counts them as independent evidence.
# A handful of them is normal, so the coverage ratio only becomes meaningful
# once there is enough of a queue for the proportion to mean anything.
MIN_BUCKET_SAMPLE = 10
# Sources on a timetable. "on demand" sources need credentials or a manual
# decision and have no schedule to fall behind; community reports and the
# database itself are covered by the queue checks.
REGULAR_CADENCES = frozenset({"daily", "weekly"})


def _parse_updated(value: object) -> datetime | None:
    """Parse the database's `updated` date (``YYYY-MM-DD``) as a UTC instant."""
    if not isinstance(value, str) or not value:
        return None
    try:
        parsed = datetime.strptime(value.strip(), "%Y-%m-%d")
    except ValueError:
        return None
    return parsed.replace(tzinfo=timezone.utc)


def _parse_instant(value: object) -> datetime | None:
    """Parse an ISO-8601 instant into UTC; a value without an offset is taken as UTC.

    Normalising matters for the dates printed in problems: every other date the
    gate reports is a UTC date, so a stamp written with a local offset must not
    print its local calendar day.
    """
    if not isinstance(value, str) or not value:
        return None
    try:
        parsed = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    except ValueError:
        return None
    return (parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)).astimezone(timezone.utc)


def evaluate_queue_health(
    reports: list[dict],
    database_updated: object,
    unreadable: int = 0,
    now: datetime | None = None,
) -> list[str]:
    """Return one message per liveness problem; an empty list means healthy.

    `reports` is the parsed contents of the readable queue files. `unreadable`
    is how many files failed to parse - they are counted toward depth but kept
    out of the reporter-identity ratio, because a corrupt file says nothing
    about whether the Worker is writing buckets, and the merge quarantines it
    on the next run anyway. `database_updated` is the published database's
    `updated` field in whatever shape it was read, so an unreadable or missing
    date degrades to "cannot judge age" rather than raising. With `now`, age is
    measured against the clock instead of the database date.
    """
    problems: list[str] = []
    total = len(reports) + unreadable
    if not total:
        return problems

    if total > MAX_QUEUE_DEPTH:
        problems.append(
            f"report queue holds {total} files, more than the {MAX_QUEUE_DEPTH} "
            "expected between merges - run the documented pipeline order to drain it"
        )

    timestamps = [ts for ts in (parse_reported_at(r.get("reported_at")) for r in reports) if ts is not None]
    updated = _parse_updated(database_updated)
    if now is not None:
        if timestamps:
            oldest = min(timestamps)
            waited = now - oldest
            if waited > timedelta(days=MAX_QUEUE_AGE_DAYS):
                problems.append(
                    f"oldest queued report ({oldest.date().isoformat()}) has waited {waited.days} days, "
                    f"more than {MAX_QUEUE_AGE_DAYS} - no merge has consumed it"
                )
    elif updated is not None:
        if timestamps:
            oldest = min(timestamps)
            stale_by = updated - oldest
            if stale_by > timedelta(days=MAX_QUEUE_AGE_DAYS):
                problems.append(
                    f"oldest queued report ({oldest.date().isoformat()}) is more than "
                    f"{MAX_QUEUE_AGE_DAYS} days older than the published database "
                    f"({updated.date().isoformat()}) - the database has moved on while this "
                    "report sat unconsumed"
                )

    if len(reports) >= MIN_BUCKET_SAMPLE:
        with_bucket = sum(1 for r in reports if validated_reporter_bucket(r.get("reporter_bucket")))
        coverage = with_bucket / len(reports)
        if coverage < MIN_BUCKET_COVERAGE:
            problems.append(
                f"only {with_bucket} of {len(reports)} readable reports carry a reporter_bucket "
                f"({coverage:.0%}); hot-list and spam-domain promotion need it to count independent "
                "sources, so the queue cannot promote anything - check whether the deployed Worker "
                "predates the field"
            )

    return problems


def evaluate_source_freshness(manifest: object, freshness: object, now: datetime) -> list[str]:
    """One message per regular-cadence source past its `stale_after_days`.

    `freshness` is `data/source-freshness.json`, which import_all_sources.py
    updates with each source's last successful import. A source with no record
    has never been imported since the record began, which is stale too.

    A source that only imports with an opt-in flag (its manifest `import_flag`)
    is held to its limit once it has been imported at all. A default import
    never fetches it, so requiring a record would keep the gate red forever.

    A manifest this can't read is a failure rather than a pass: a broken
    manifest would otherwise switch the whole check off.
    """
    sources = manifest.get("sources") if isinstance(manifest, dict) else None
    if not isinstance(sources, list):
        return [f"{MANIFEST_FILE.name} is missing or unreadable, so upstream freshness can't be checked"]
    recorded = freshness.get("last_success") if isinstance(freshness, dict) else None
    last_success = recorded if isinstance(recorded, dict) else {}
    problems: list[str] = []
    for source in sources:
        if not isinstance(source, dict):
            problems.append(f"{MANIFEST_FILE.name} has a source entry that isn't an object")
            continue
        cadence = str(source.get("cadence", "")).strip().lower()
        if cadence not in REGULAR_CADENCES:
            continue
        source_id = source.get("id")
        limit = source.get("stale_after_days")
        if not isinstance(source_id, str) or isinstance(limit, bool) or not isinstance(limit, (int, float)):
            problems.append(f"{source_id or 'a source'} has no usable stale_after_days in {MANIFEST_FILE.name}")
            continue
        flag = source.get("import_flag")
        refresh = f"run scripts/import_all_sources.py {flag}" if flag else "run scripts/import_all_sources.py"
        stamp = _parse_instant(last_success.get(source_id))
        if stamp is None:
            if not flag:
                problems.append(
                    f"{source_id} ({cadence} source) has no successful import recorded in "
                    f"{FRESHNESS_FILE.name} - {refresh}"
                )
        elif now - stamp > timedelta(days=limit):
            problems.append(
                f"{source_id} was last imported {stamp.date().isoformat()}, {(now - stamp).days} days ago, "
                f"past its {limit:g}-day limit - {refresh}"
            )
    return problems


def _load_json(path: Path) -> object:
    try:
        with Path(path).open(encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return None


def load_queue(reports_dir: Path) -> tuple[list[dict], int]:
    """Read every queued report.

    Returns the readable reports and a count of the files that would not parse.
    They are kept apart so a corrupt file cannot masquerade as a report the
    Worker wrote without a reporter bucket.
    """
    reports_dir = Path(reports_dir)
    if not reports_dir.exists():
        return [], 0
    loaded: list[dict] = []
    unreadable = 0
    for report_file in sorted(reports_dir.glob("*.json")):
        try:
            with report_file.open(encoding="utf-8") as handle:
                payload = json.load(handle)
        except (OSError, ValueError):
            unreadable += 1
            continue
        if isinstance(payload, dict):
            loaded.append(payload)
        else:
            unreadable += 1
    return loaded, unreadable


def load_database_updated(db_file: Path) -> object:
    try:
        with Path(db_file).open(encoding="utf-8") as handle:
            return json.load(handle).get("updated")
    except (OSError, ValueError, AttributeError):
        return None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Report-pipeline liveness checks.")
    parser.add_argument(
        "--scheduled",
        action="store_true",
        help="measure queue age against the clock and check upstream source freshness (weekly workflow only)",
    )
    args = parser.parse_args(argv)
    now = datetime.now(timezone.utc) if args.scheduled else None

    reports, unreadable = load_queue(REPORTS_DIR)
    total = len(reports) + unreadable
    problems = evaluate_queue_health(reports, load_database_updated(DB_FILE), unreadable, now=now)
    if now is not None:
        problems += evaluate_source_freshness(_load_json(MANIFEST_FILE), _load_json(FRESHNESS_FILE), now)
    if problems:
        print(f"Report pipeline is not healthy ({total} queued file(s) in {REPORTS_DIR}):", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    suffix = f", {unreadable} unreadable" if unreadable else ""
    print(f"Report queue liveness OK ({total} file(s) pending{suffix}).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
