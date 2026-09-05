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


def _parse_updated(value: object) -> datetime | None:
    """Parse the database's `updated` date (``YYYY-MM-DD``) as a UTC instant."""
    if not isinstance(value, str) or not value:
        return None
    try:
        parsed = datetime.strptime(value.strip(), "%Y-%m-%d")
    except ValueError:
        return None
    return parsed.replace(tzinfo=timezone.utc)


def evaluate_queue_health(reports: list[dict], database_updated: object) -> list[str]:
    """Return one message per liveness problem; an empty list means healthy.

    `reports` is the parsed contents of the queue files. `database_updated` is
    the published database's `updated` field, in whatever shape it was read, so
    an unreadable or missing date degrades to "cannot judge age" rather than
    raising on data the caller has no control over.
    """
    problems: list[str] = []
    if not reports:
        return problems

    if len(reports) > MAX_QUEUE_DEPTH:
        problems.append(
            f"report queue holds {len(reports)} files, more than the {MAX_QUEUE_DEPTH} "
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
                    f"oldest queued report ({oldest.date().isoformat()}) predates the published "
                    f"database ({updated.date().isoformat()}) by {stale_by.days} days - a merge "
                    "ran and left it behind"
                )

    with_bucket = sum(1 for r in reports if validated_reporter_bucket(r.get("reporter_bucket")))
    coverage = with_bucket / len(reports)
    if coverage < MIN_BUCKET_COVERAGE:
        problems.append(
            f"only {with_bucket} of {len(reports)} queued reports carry a reporter_bucket "
            f"({coverage:.0%}); hot-list and spam-domain promotion need it to count independent "
            "sources, so the queue cannot promote anything - the deployed Worker is stale"
        )

    return problems


def load_queue(reports_dir: Path) -> list[dict]:
    """Read every queued report. Unparseable files count as reports with no fields."""
    reports_dir = Path(reports_dir)
    if not reports_dir.exists():
        return []
    loaded: list[dict] = []
    for report_file in sorted(reports_dir.glob("*.json")):
        try:
            with report_file.open(encoding="utf-8") as handle:
                payload = json.load(handle)
        except (OSError, ValueError):
            payload = None
        loaded.append(payload if isinstance(payload, dict) else {})
    return loaded


def load_database_updated(db_file: Path) -> object:
    try:
        with Path(db_file).open(encoding="utf-8") as handle:
            return json.load(handle).get("updated")
    except (OSError, ValueError, AttributeError):
        return None


def main() -> int:
    reports = load_queue(REPORTS_DIR)
    problems = evaluate_queue_health(reports, load_database_updated(DB_FILE))
    if problems:
        print(f"Report queue is not being consumed ({len(reports)} file(s) in {REPORTS_DIR}):", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    print(f"Report queue liveness OK ({len(reports)} file(s) pending).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
