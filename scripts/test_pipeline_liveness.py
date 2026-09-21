#!/usr/bin/env python3
"""Regression tests for the report-queue liveness gate."""

import json
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

from pipeline_liveness import (
    MAX_QUEUE_AGE_DAYS,
    MAX_QUEUE_DEPTH,
    MIN_BUCKET_SAMPLE,
    evaluate_queue_health,
    evaluate_source_freshness,
    load_database_updated,
    load_queue,
)

BUCKET = "0123456789abcdef"
BASE = datetime(2026, 9, 4, 12, 0, 0, tzinfo=timezone.utc)


def report(offset_days: float = 0.0, bucket: str | None = BUCKET) -> dict:
    entry = {
        "number": "+15125550100",
        "type": "spam",
        "reported_at": (BASE - timedelta(days=offset_days)).isoformat().replace("+00:00", "Z"),
        "source": "community_app",
    }
    if bucket is not None:
        entry["reporter_bucket"] = bucket
    return entry


def main() -> None:
    # ── an empty queue is healthy, not suspicious ────────────────────────
    assert evaluate_queue_health([], "2026-09-04") == []
    # Even with no database date to compare against.
    assert evaluate_queue_health([], None) == []

    # ── a small, fresh, bucketed queue is healthy ────────────────────────
    healthy = [report(offset_days=i * 0.1) for i in range(MAX_QUEUE_DEPTH)]
    assert evaluate_queue_health(healthy, "2026-09-04") == [], evaluate_queue_health(healthy, "2026-09-04")

    # ── depth: one past the cap trips it ─────────────────────────────────
    deep = [report(offset_days=i * 0.1) for i in range(MAX_QUEUE_DEPTH + 1)]
    problems = evaluate_queue_health(deep, "2026-09-04")
    assert len(problems) == 1, problems
    assert "report queue holds" in problems[0], problems

    # Unreadable files still count toward depth: they occupy the queue and the
    # merge has to deal with them.
    problems = evaluate_queue_health(healthy, "2026-09-04", unreadable=1)
    assert len(problems) == 1, problems
    assert f"holds {MAX_QUEUE_DEPTH + 1} files" in problems[0], problems

    # ── age: the database moved on while a report sat unconsumed ─────────
    left_behind = [report(offset_days=MAX_QUEUE_AGE_DAYS + 1)]
    updated = (BASE + timedelta(days=1)).date().isoformat()
    problems = evaluate_queue_health(left_behind, updated)
    assert len(problems) == 1, problems
    assert "older than the published database" in problems[0], problems
    # The message must not claim a cause it cannot know. A completed merge
    # always empties the queue, so "a merge ran and left it behind" was wrong:
    # any database write moves `updated`, including an importer run.
    assert "a merge ran" not in problems[0], problems

    # A report that arrived after the last merge is pending, not stale.
    assert evaluate_queue_health([report(offset_days=0)], "2026-08-24") == []

    # An unreadable or missing database date cannot judge age, and must not
    # raise on data the caller does not control.
    assert evaluate_queue_health([report(offset_days=400)], None) == []
    assert evaluate_queue_health([report(offset_days=400)], "not a date") == []
    assert evaluate_queue_health([report(offset_days=400)], 20260824) == []

    # ── reporter identity ────────────────────────────────────────────────
    # Bucket-less reports are a supported input - the merge admits them at
    # reports:1 and simply never counts them as independent evidence. A
    # handful must not fail the build for every contributor.
    for count in range(1, MIN_BUCKET_SAMPLE):
        few = [report(offset_days=i * 0.1, bucket=None) for i in range(count)]
        assert evaluate_queue_health(few, "2026-09-04") == [], (count, evaluate_queue_health(few, "2026-09-04"))

    # At the sample size the proportion starts to mean something.
    sample = [report(offset_days=i * 0.1, bucket=None) for i in range(MIN_BUCKET_SAMPLE)]
    problems = evaluate_queue_health(sample, "2026-09-04")
    assert len(problems) == 1, problems
    assert "reporter_bucket" in problems[0], problems

    # Exactly half is enough; below half is not.
    half = [report(bucket=BUCKET if i % 2 == 0 else None) for i in range(MIN_BUCKET_SAMPLE)]
    assert evaluate_queue_health(half, "2026-09-04") == []
    third = [report(bucket=BUCKET if i % 3 == 0 else None) for i in range(MIN_BUCKET_SAMPLE + 2)]
    assert len(evaluate_queue_health(third, "2026-09-04")) == 1

    # A malformed bucket is not a bucket.
    malformed = [report(bucket="short") for _ in range(MIN_BUCKET_SAMPLE)]
    assert len(evaluate_queue_health(malformed, "2026-09-04")) == 1

    # Corrupt files say nothing about whether the Worker writes buckets, so
    # they must stay out of the coverage ratio. Ten good bucketed reports plus
    # twenty unreadable files is a depth problem, never an identity one.
    problems = evaluate_queue_health(
        [report(offset_days=i * 0.1) for i in range(MIN_BUCKET_SAMPLE)],
        "2026-09-04",
        unreadable=20,
    )
    assert len(problems) == 1, problems
    assert "reporter_bucket" not in problems[0], problems

    # ── the live 2026-09-05 incident ─────────────────────────────────────
    incident = [report(offset_days=i * 0.05, bucket=None) for i in range(267)]
    problems = evaluate_queue_health(incident, "2026-08-24")
    assert len(problems) == 2, problems
    assert any("report queue holds 267" in p for p in problems), problems
    assert any("reporter_bucket" in p for p in problems), problems

    # ── loaders degrade instead of raising ───────────────────────────────
    assert load_queue("no/such/directory") == ([], 0)
    assert load_database_updated("no/such/file.json") is None

    with tempfile.TemporaryDirectory() as directory:
        queue = Path(directory)
        (queue / "good.json").write_text(json.dumps(report()), encoding="utf-8")
        (queue / "truncated.json").write_text('{"number": "+1512', encoding="utf-8")
        # A JSON document that is valid but not an object is not a report.
        (queue / "array.json").write_text("[]", encoding="utf-8")
        # Only *.json is queue content; the rejected/ subdirectory is not.
        (queue / "notes.txt").write_text("ignore me", encoding="utf-8")
        (queue / "rejected").mkdir()
        (queue / "rejected" / "quarantined.json").write_text("{}", encoding="utf-8")

        loaded, unreadable = load_queue(queue)
        assert len(loaded) == 1, loaded
        assert unreadable == 2, unreadable

    scheduled_checks()
    print("pipeline_liveness tests passed")


def scheduled_checks() -> None:
    """The --scheduled mode: queue age against the clock, and source freshness."""
    # ── the 2026-09-05 second stall: invisible to the database-relative check ──
    # Every report queued after the 09-05 drain is newer than the database, so
    # the age check measured against it can never fire.
    queued_since_drain = [report(offset_days=-i * 0.1) for i in range(5)]
    assert evaluate_queue_health(queued_since_drain, "2026-09-04") == []
    now = BASE + timedelta(days=16)
    problems = evaluate_queue_health(queued_since_drain, "2026-09-04", now=now)
    assert len(problems) == 1 and "has waited 16 days" in problems[0], problems

    # A fresh queue measured against the clock is fine.
    assert evaluate_queue_health(queued_since_drain, "2026-09-04", now=BASE + timedelta(days=MAX_QUEUE_AGE_DAYS)) == []

    # ── upstream source freshness ────────────────────────────────────────
    manifest = {
        "sources": [
            {"id": "ftc_complaints", "cadence": "daily", "stale_after_days": 14},
            {"id": "toastedspam", "cadence": "weekly", "stale_after_days": 30},
            # No timetable, so nothing to fall behind.
            {"id": "phoneblock_bulk", "cadence": "on demand", "stale_after_days": 30},
            {"id": "community_reports", "cadence": "continuous", "stale_after_days": 90},
        ]
    }
    fresh = {
        "last_success": {
            "ftc_complaints": (BASE - timedelta(days=3)).isoformat(),
            "toastedspam": (BASE - timedelta(days=29)).isoformat(),
        }
    }
    assert evaluate_source_freshness(manifest, fresh, BASE) == []

    stale = {"last_success": {"ftc_complaints": "2026-08-01T21:51:56-04:00", "toastedspam": fresh["last_success"]["toastedspam"]}}
    problems = evaluate_source_freshness(manifest, stale, BASE)
    assert len(problems) == 1 and problems[0].startswith("ftc_complaints was last imported 2026-08-02"), problems

    # A source that has never been recorded is stale, not "unknown and fine".
    problems = evaluate_source_freshness(manifest, {"last_success": {}}, BASE)
    assert sorted(p.split(" ")[0] for p in problems) == ["ftc_complaints", "toastedspam"], problems
    # Nor does a missing or unreadable record file hide it.
    assert len(evaluate_source_freshness(manifest, None, BASE)) == 2
    # An unreadable manifest has nothing to judge rather than raising.
    assert evaluate_source_freshness(None, fresh, BASE) == []


if __name__ == "__main__":
    main()
