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
"""

from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

from report_dedup import parse_reported_at, validated_reporter_bucket

DATA_DIR = Path(os.environ.get("CALLSHIELD_DATA_DIR", Path(__file__).parent.parent / "data"))
REPORTS_DIR = Path(os.environ.get("CALLSHIELD_REPORTS_DIR", DATA_DIR / "reports"))
DB_FILE = DATA_DIR / "spam_numbers.json"

MAX_QUEUE_DEPTH = 20
MAX_QUEUE_AGE_DAYS = 7
MIN_BUCKET_COVERAGE = 0.5
# Bucket-less reports are a supported input: the pipeline merges them into the
# database at reports:1 and simply never counts them as independent evidence.
# A handful of them is normal, so the coverage ratio only becomes meaningful
# once there is enough of a queue for the proportion to mean anything.
MIN_BUCKET_SAMPLE = 10


def _parse_updated(value: object) -> datetime | None:
    """Parse the database's `updated` date (``YYYY-MM-DD``) as a UTC instant."""
    if not isinstance(value, str) or not value:
        return None
    try:
        parsed = datetime.strptime(value.strip(), "%Y-%m-%d")
    except ValueError:
        return None
    return parsed.replace(tzinfo=timezone.utc)


def evaluate_queue_health(
    reports: list[dict],
    database_updated: object,
    unreadable: int = 0,
) -> list[str]:
    """Return one message per liveness problem; an empty list means healthy.

    `reports` is the parsed contents of the readable queue files. `unreadable`
    is how many files failed to parse - they are counted toward depth but kept
    out of the reporter-identity ratio, because a corrupt file says nothing
    about whether the Worker is writing buckets, and the merge quarantines it
    on the next run anyway. `database_updated` is the published database's
    `updated` field in whatever shape it was read, so an unreadable or missing
    date degrades to "cannot judge age" rather than raising.
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

    updated = _parse_updated(database_updated)
    if updated is not None:
        timestamps = [ts for ts in (parse_reported_at(r.get("reported_at")) for r in reports) if ts is not None]
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


def main() -> int:
    reports, unreadable = load_queue(REPORTS_DIR)
    total = len(reports) + unreadable
    problems = evaluate_queue_health(reports, load_database_updated(DB_FILE), unreadable)
    if problems:
        print(f"Report queue is not being consumed ({total} file(s) in {REPORTS_DIR}):", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    suffix = f", {unreadable} unreadable" if unreadable else ""
    print(f"Report queue liveness OK ({total} file(s) pending{suffix}).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
