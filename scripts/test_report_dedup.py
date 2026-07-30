#!/usr/bin/env python3
"""Regression tests for burst-duplicate detection on the community-report queue."""

from datetime import datetime, timedelta, timezone

from report_dedup import BURST_DUPLICATE_SECONDS, find_burst_duplicates, parse_reported_at

BASE = datetime(2026, 7, 30, 12, 0, 0, tzinfo=timezone.utc)


def at(seconds: int) -> datetime:
    return BASE + timedelta(seconds=seconds)


def main() -> None:
    # ── parse_reported_at ────────────────────────────────────────────────
    assert parse_reported_at("2026-07-30T12:00:00Z") == BASE
    assert parse_reported_at("2026-07-30T12:00:00+00:00") == BASE
    # Naive input is read as UTC so comparisons never raise on mixed data.
    assert parse_reported_at("2026-07-30T12:00:00") == BASE
    assert parse_reported_at("") is None
    assert parse_reported_at(None) is None
    assert parse_reported_at("not a timestamp") is None
    assert parse_reported_at(1785431632) is None

    # ── the live incident: same number twice, 10 seconds apart ───────────
    # +12385233476 was accepted twice on 2026-07-30 and went onto the hot list.
    duplicates = find_burst_duplicates(
        [
            ("+12385233476", at(0), "first"),
            ("+12385233476", at(10), "second"),
        ]
    )
    assert duplicates == {"second"}, duplicates

    # ── genuine velocity survives ────────────────────────────────────────
    # Two independent reporters minutes apart are the signal the hot list
    # exists to carry; collapsing them would defeat the feature.
    spaced = find_burst_duplicates(
        [
            ("+12122340101", at(0), "a"),
            ("+12122340101", at(BURST_DUPLICATE_SECONDS + 1), "b"),
        ]
    )
    assert spaced == set(), spaced

    # Exactly at the boundary is not a duplicate (window is exclusive).
    boundary = find_burst_duplicates(
        [
            ("+12122340101", at(0), "a"),
            ("+12122340101", at(BURST_DUPLICATE_SECONDS), "b"),
        ]
    )
    assert boundary == set(), boundary

    # ── the window advances from the last COUNTED report ─────────────────
    # A steady drip 45s apart: "b" falls inside the window opened by "a" and is
    # collapsed, but the window does NOT advance to "b" (it was never counted),
    # so "c" at 90s clears "a" and survives. This rate-limits a persistent
    # reporter instead of silencing the number entirely, which matters because a
    # genuinely active campaign does keep drawing real reports.
    drip = find_burst_duplicates(
        [
            ("+12122340101", at(0), "a"),
            ("+12122340101", at(45), "b"),
            ("+12122340101", at(90), "c"),
        ]
    )
    assert drip == {"b"}, drip

    # ── different keys never suppress each other ─────────────────────────
    distinct = find_burst_duplicates(
        [
            ("+12122340101", at(0), "a"),
            ("+12122340102", at(1), "b"),
        ]
    )
    assert distinct == set(), distinct

    # A spam report and a not_spam report are different verdicts on the same
    # number; merge_community_reports keys on both so they cannot cancel.
    verdicts = find_burst_duplicates(
        [
            (("+12122340101", "spam"), at(0), "a"),
            (("+12122340101", "not_spam"), at(1), "b"),
        ]
    )
    assert verdicts == set(), verdicts

    # ── unparseable timestamps are never treated as duplicates ───────────
    # No timestamp is no evidence of a burst; dropping those reports would let
    # a malformed clock silently delete real ones.
    undated = find_burst_duplicates(
        [
            ("+12122340101", None, "a"),
            ("+12122340101", None, "b"),
            ("+12122340101", at(0), "c"),
        ]
    )
    assert undated == set(), undated

    # ── file-glob order must not change the outcome ──────────────────────
    forward = find_burst_duplicates(
        [
            ("+12385233476", at(0), "first"),
            ("+12385233476", at(10), "second"),
        ]
    )
    reverse = find_burst_duplicates(
        [
            ("+12385233476", at(10), "second"),
            ("+12385233476", at(0), "first"),
        ]
    )
    assert forward == reverse == {"second"}, (forward, reverse)

    assert find_burst_duplicates([]) == set()

    print("report_dedup tests passed")


if __name__ == "__main__":
    main()
