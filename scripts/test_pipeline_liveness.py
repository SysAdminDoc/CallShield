#!/usr/bin/env python3
"""Regression tests for the report-queue liveness gate."""

from datetime import datetime, timedelta, timezone

from pipeline_liveness import (
    MAX_QUEUE_AGE_DAYS,
    MAX_QUEUE_DEPTH,
    evaluate_queue_health,
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

    # ── age: a report left behind by a later merge ───────────────────────
    # The database was published after the report arrived, so the merge saw it
    # and skipped it rather than consuming it.
    left_behind = [report(offset_days=MAX_QUEUE_AGE_DAYS + 1)]
    updated = (BASE + timedelta(days=1)).date().isoformat()
    problems = evaluate_queue_health(left_behind, updated)
    assert len(problems) == 1, problems
    assert "predates the published" in problems[0], problems

    # A report that arrived after the last merge is pending, not stale.
    assert evaluate_queue_health([report(offset_days=0)], "2026-08-24") == []

    # An unreadable or missing database date cannot judge age, and must not
    # raise on data the caller does not control.
    assert evaluate_queue_health([report(offset_days=400)], None) == []
    assert evaluate_queue_health([report(offset_days=400)], "not a date") == []
    assert evaluate_queue_health([report(offset_days=400)], 20260824) == []

    # ── reporter identity: the live 2026-09-04 shape ─────────────────────
    # Every queued report carried {number, type, reported_at, source} and no
    # reporter_bucket, so no promotion could ever clear the corroboration
    # thresholds however many reports arrived.
    bucketless = [report(offset_days=i * 0.1, bucket=None) for i in range(5)]
    problems = evaluate_queue_health(bucketless, "2026-09-04")
    assert len(problems) == 1, problems
    assert "reporter_bucket" in problems[0], problems

    # Exactly half is enough; below half is not.
    half = [report(bucket=BUCKET), report(bucket=None)]
    assert evaluate_queue_health(half, "2026-09-04") == []
    third = [report(bucket=BUCKET), report(bucket=None), report(bucket=None)]
    assert len(evaluate_queue_health(third, "2026-09-04")) == 1

    # A malformed bucket is not a bucket.
    assert len(evaluate_queue_health([report(bucket="short")], "2026-09-04")) == 1

    # ── the full live incident: depth and identity together ──────────────
    incident = [report(offset_days=i * 0.05, bucket=None) for i in range(60)]
    problems = evaluate_queue_health(incident, "2026-08-24")
    assert len(problems) == 2, problems
    assert any("report queue holds 60" in p for p in problems), problems
    assert any("reporter_bucket" in p for p in problems), problems

    # ── loaders degrade instead of raising ───────────────────────────────
    assert load_queue("no/such/directory") == []
    assert load_database_updated("no/such/file.json") is None

    print("pipeline_liveness tests passed")


if __name__ == "__main__":
    main()
