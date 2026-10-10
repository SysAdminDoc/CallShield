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
current time instead of the database date, upstream sources with a regular
cadence are checked against `stale_after_days`, and published rows whose
evidence expires within a month are reported. A source within a week of its
limit is a warning, exit code 3, which the workflow reports without failing. Both are left out of
`verifyPipelineTests`: that task runs inside `check`, where a clock would fail
every later build of an old tag. Measured against the database date, the age
check cannot fire in the weeks after a drain, because every report queued since
is newer than the database; that is how a second stall ran from 2026-09-05
without the gate noticing.

Measured against the clock, age catches a stall on its own, so the weekly run
leaves every dated report out of the depth count. Reports arrive at about
twenty a day and drains are manual, so the depth cap failed the weekly run
within a day of any drain while nothing was stuck. Files the clock can't date
(unreadable, or no parseable `reported_at`) still count toward depth there,
and the cap stays whole for `verifyPipelineTests`, which is run right after a
drain.
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
# How far ahead the weekly run looks for downloaded rows whose evidence runs out.
EVIDENCE_EXPIRY_WARNING_DAYS = 30
# A source this many days from its stale_after_days limit gets a warning. The
# run is weekly, so a source that only failed once it lapsed could sit stale
# for up to a week first (FTC lapsed 2026-10-07 and the next run was 10-12).
SOURCE_STALE_WARNING_DAYS = 7
# main()'s exit code when nothing failed but a source is close to its limit.
EXIT_WARNING = 3


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
    measured against the clock instead of the database date, and only the
    files the clock can't date count toward depth.
    """
    problems: list[str] = []
    total = len(reports) + unreadable
    if not total:
        return problems

    timestamps = [ts for ts in (parse_reported_at(r.get("reported_at")) for r in reports) if ts is not None]
    depth = total if now is None else total - len(timestamps)
    if depth > MAX_QUEUE_DEPTH:
        undated = "" if now is None else " with no readable report time"
        problems.append(
            f"report queue holds {depth} files{undated}, more than the {MAX_QUEUE_DEPTH} "
            "expected between merges - run the documented pipeline order to drain it"
        )

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
    updates with each source's last successful import and the newest record
    date for FTC and FCC: the complaint date for FTC, and the day FCC published
    the complaint for FCC, whose batches carry complaint dates years old. A
    source with no record has never supplied data since the record began, which
    is stale too.

    A source that only imports with an opt-in flag (its manifest `import_flag`)
    is held to its limit once it has been imported at all. A default import
    never fetches it, so requiring a record would keep the gate red forever.

    A manifest this can't read is a failure rather than a pass: a broken
    manifest would otherwise switch the whole check off.
    """
    return _source_freshness(manifest, freshness, now)[0]


def warn_source_staleness(manifest: object, freshness: object, now: datetime) -> list[str]:
    """One warning per regular-cadence source within SOURCE_STALE_WARNING_DAYS of its limit.

    The source is still fresh, so the weekly run reports it without failing
    (EXIT_WARNING), and a source past its limit is evaluate_source_freshness's.
    """
    return _source_freshness(manifest, freshness, now)[1]


def _source_freshness(manifest: object, freshness: object, now: datetime) -> tuple[list[str], list[str]]:
    """The failures and the warnings for every regular-cadence source."""
    sources = manifest.get("sources") if isinstance(manifest, dict) else None
    if not isinstance(sources, list):
        return [f"{MANIFEST_FILE.name} is missing or unreadable, so upstream freshness can't be checked"], []
    recorded = freshness.get("last_success") if isinstance(freshness, dict) else None
    last_success = recorded if isinstance(recorded, dict) else {}
    recorded_dates = freshness.get("newest_record_date") if isinstance(freshness, dict) else None
    newest_record_date = recorded_dates if isinstance(recorded_dates, dict) else {}
    problems: list[str] = []
    warnings: list[str] = []
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
        record_dated = source_id in {"ftc_complaints", "fcc_complaints"}
        stamp = _parse_instant(
            newest_record_date.get(source_id) if record_dated else last_success.get(source_id)
        )
        if stamp is None:
            if not flag:
                problems.append(
                    f"{source_id} ({cadence} source) has no {'record date' if record_dated else 'successful import'} recorded in "
                    f"{FRESHNESS_FILE.name} - {refresh}"
                )
            continue
        age_description = "newest record is from" if record_dated else "was last imported"
        age = f"{source_id} {age_description} {stamp.date().isoformat()}, {(now - stamp).days} days ago"
        if now - stamp > timedelta(days=limit):
            problems.append(f"{age}, past its {limit:g}-day limit - {refresh}")
        # A limit of a week or less would warn the day it was imported, so a
        # short limit warns from its halfway point instead.
        elif now - stamp >= timedelta(days=max(limit - SOURCE_STALE_WARNING_DAYS, limit / 2)):
            stale_on = (stamp + timedelta(days=limit)).date().isoformat()
            warnings.append(f"{age}, and passes its {limit:g}-day limit after {stale_on} - {refresh}")
    return problems, warnings


def row_expiry_epoch_ms(row: dict) -> int | None:
    """When a published row stops blocking on phones, or None if it never does.

    A row stays live while any of its evidence is live, so the latest expiry
    decides, and a record without one (or no evidence at all) keeps it live.
    The app's SourceEvidenceCodec.rowExpiry applies the same rule, and both are
    tested against evidence_expiry_fixtures.json.
    """
    stamps = []
    for item in row.get("evidence") or []:
        stamp = _epoch_ms(item.get("expires_at_epoch_ms") if isinstance(item, dict) else None)
        if stamp is None:
            return None
        stamps.append(stamp)
    return max(stamps) if stamps else None


def _epoch_ms(value: object) -> int | None:
    """An expiry stamp read the way the app's Moshi reads a Long, or None when undated.

    Moshi takes an integer, a float with no fractional part, or a string holding
    either, so 1.8e12 and "1800000000000" are dates on phones too.
    """
    if isinstance(value, bool):
        return None
    if isinstance(value, str):
        try:
            value = float(value) if any(c in value for c in ".eE") else int(value)
        except ValueError:
            return None
    if isinstance(value, float):
        return int(value) if value.is_integer() else None
    return value if isinstance(value, int) else None


def evaluate_evidence_expiry(database: object, now: datetime) -> list[str]:
    """One message per kind of published row that is about to stop blocking.

    The app drops a downloaded number or range once its last evidence expires
    (see row_expiry_epoch_ms). Until 2026-09-28 every row's evidence ran out within a month of the
    import that stamped it and nothing looked, so protection would have ended
    without a word whenever imports paused. Expired rows count too: they
    already match nothing on phones. Evidence that hasn't expired is fine
    however old it is.
    """
    if not isinstance(database, dict):
        return [f"{DB_FILE.name} is missing or unreadable, so evidence expiry can't be checked"]
    horizon = now + timedelta(days=EVIDENCE_EXPIRY_WARNING_DAYS)
    problems: list[str] = []
    for kind, label in (("numbers", "numbers"), ("prefixes", "ranges")):
        rows = database.get(kind)
        if not isinstance(rows, list):
            continue
        expiring = 0
        earliest: datetime | None = None
        for row in rows:
            if not isinstance(row, dict):
                continue
            stamp = row_expiry_epoch_ms(row)
            if stamp is None:
                continue
            expires = datetime.fromtimestamp(stamp / 1000, timezone.utc)
            if expires <= horizon:
                expiring += 1
                earliest = expires if earliest is None else min(earliest, expires)
        if earliest is not None:
            problems.append(
                f"{expiring} published {label} stop blocking on phones from {earliest.date().isoformat()}, "
                "when their evidence expires - run scripts/import_all_sources.py, then merge, sign and publish"
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
    warnings: list[str] = []
    if now is not None:
        manifest, freshness = _load_json(MANIFEST_FILE), _load_json(FRESHNESS_FILE)
        problems += evaluate_source_freshness(manifest, freshness, now)
        problems += evaluate_evidence_expiry(_load_json(DB_FILE), now)
        warnings = warn_source_staleness(manifest, freshness, now)
    if problems:
        print(f"Report pipeline is not healthy ({total} queued file(s) in {REPORTS_DIR}):", file=sys.stderr)
        for problem in problems + [f"warning: {warning}" for warning in warnings]:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    if warnings:
        print(f"Report pipeline warning, a source is close to stale ({total} queued file(s) in {REPORTS_DIR}):")
        for warning in warnings:
            print(f"  - {warning}")
        return EXIT_WARNING
    suffix = f", {unreadable} unreadable" if unreadable else ""
    print(f"Report queue liveness OK ({total} file(s) pending{suffix}).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
