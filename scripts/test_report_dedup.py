#!/usr/bin/env python3
"""Regression tests for burst-duplicate detection on the community-report queue."""

from datetime import datetime, timedelta, timezone

from generate_hot_list import CAMPAIGN_MIN_UNION_REPORTERS, CAMPAIGN_REPORTERS_PER_NUMBER, MIN_REPORTERS_HOT
from report_dedup import (
    BURST_DUPLICATE_SECONDS,
    MAX_DEVICES_PER_GROUP,
    busiest_day_reporters,
    capped_reporter_count,
    find_burst_duplicates,
    find_resent_reports,
    parse_reported_at,
    reporter_day_key,
    reporter_identity,
    validated_report_id,
    validated_reporter_bucket,
)

BASE = datetime(2026, 7, 30, 12, 0, 0, tzinfo=timezone.utc)


def at(seconds: int) -> datetime:
    return BASE + timedelta(seconds=seconds)


def main() -> None:
    assert validated_reporter_bucket("0123456789abcdef") == "0123456789abcdef"
    assert validated_reporter_bucket("ABCDEF0123456789") == "abcdef0123456789"
    assert validated_reporter_bucket("short") == ""
    assert validated_reporter_bucket(None) == ""
    assert reporter_day_key("0123456789abcdef", BASE) == (
        "0123456789abcdef",
        "2026-07-30",
    )
    assert reporter_day_key("", BASE) is None
    assert reporter_day_key("0123456789abcdef", None) is None

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

    # ── one report resent under its id ───────────────────────────────────
    report_id = "3F1C9A52-7D4E-4B8A-9C1D-2E5F6A7B8C9D"
    assert validated_report_id(report_id) == report_id.lower()
    assert validated_report_id("not-a-uuid") == ""
    assert validated_report_id(None) == ""
    # Hours apart and from another reporter bucket, it is still the same report;
    # the earliest copy is the one kept, whatever order the files come in.
    resent = find_resent_reports(
        [
            (validated_report_id(report_id), at(7200), "resend"),
            (validated_report_id(report_id), at(0), "original"),
            ("", at(1), "no-id-a"),
            ("", at(2), "no-id-b"),
        ]
    )
    assert resent == {"resend"}, resent

    # ── reporters count per /64 device, at most two per /48 group ─────────
    group, device = "00000000000a0001", "00000000000d0001"
    assert reporter_identity({"reporter_bucket": group, "reporter_device": device}) == (group, device)
    # A report stored before device buckets is one device of its group.
    assert reporter_identity({"reporter_bucket": group}) == (group, group)
    assert reporter_identity({"reporter_bucket": group, "reporter_device": "junk"}) == (group, group)
    assert reporter_identity({"reporter_device": device}) is None
    # Two phones on one carrier /48 are two reporters.
    assert capped_reporter_count([(group, "00000000000d0001"), (group, "00000000000d0002")]) == 2
    # A delegated /48 rotating /64s counts for MAX_DEVICES_PER_GROUP at most.
    rotating = [(group, f"{index:016x}") for index in range(1, 50)]
    assert capped_reporter_count(rotating) == MAX_DEVICES_PER_GROUP == 2
    assert capped_reporter_count(rotating + [("00000000000a0002", "00000000000d00ff")]) == 3
    assert capped_reporter_count([(group, device), (group, device)]) == 1
    # So one /48 must never meet a hot-list reporter minimum on its own.
    for minimum in (MIN_REPORTERS_HOT, CAMPAIGN_REPORTERS_PER_NUMBER, CAMPAIGN_MIN_UNION_REPORTERS):
        assert minimum > MAX_DEVICES_PER_GROUP, minimum

    # ── buckets rotate at UTC midnight, so only one day's count as distinct ──
    before, after = "2026-07-29", "2026-07-30"
    one_reporter_either_side = [(before, (group, device)), (after, ("00000000000a0009", "00000000000d0009"))]
    assert busiest_day_reporters(one_reporter_either_side) == 1
    three_on_one_day = [(after, (f"00000000000a000{n}", f"00000000000d000{n}")) for n in range(1, 4)]
    # The day after midnight holds the three plus the second of those buckets.
    assert busiest_day_reporters(three_on_one_day + one_reporter_either_side) == 4
    # The /48 cap still applies within the day.
    assert busiest_day_reporters([(after, identity) for identity in rotating]) == MAX_DEVICES_PER_GROUP
    assert busiest_day_reporters([(before, "a"), (after, "b"), (after, "c")], count=len) == 2
    assert busiest_day_reporters([]) == 0

    print("report_dedup tests passed")


if __name__ == "__main__":
    main()
