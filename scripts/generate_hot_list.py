#!/usr/bin/env python3
"""
CallShield Hot List Generator

Generates data/hot_numbers.json — a lightweight list of the top numbers
reported by the community in the last 24 hours. The Android app syncs
this every 30 minutes so users get protection against trending spam numbers
hours before the nightly full-database merge.

Run locally BEFORE merge_community_reports.py — the merge deletes the pending
report files this generator reads. See data/README.md for the regen sequence.
"""

import json
import os
from datetime import datetime, timezone, timedelta
from pathlib import Path
from phone_normalization import validated_report_number

from pipeline_io import atomic_write_json
from report_dedup import (
    BURST_DUPLICATE_SECONDS,
    find_burst_duplicates,
    parse_reported_at,
    reporter_day_key,
    validated_reporter_bucket,
)

DATA_DIR = Path(os.environ.get("CALLSHIELD_DATA_DIR", Path(__file__).parent.parent / "data"))
REPORTS_DIR = Path(os.environ.get("CALLSHIELD_REPORTS_DIR", DATA_DIR / "reports"))
DB_FILE = DATA_DIR / "spam_numbers.json"
HOT_LIST_FILE = DATA_DIR / "hot_numbers.json"
HOT_RANGES_FILE = DATA_DIR / "hot_ranges.json"

HOT_LIST_SIZE = 500
HOT_WINDOW_HOURS = 24
MIN_REPORTS_HOT = 4
MIN_REPORTERS_HOT = 3
MIN_REPORT_SPAN_MINUTES = 120
CAMPAIGN_THRESHOLD = 4
CAMPAIGN_REPORTS_PER_NUMBER = 5
CAMPAIGN_REPORTERS_PER_NUMBER = 4
CAMPAIGN_MIN_UNION_REPORTERS = 6
CAMPAIGN_MIN_SPAN_MINUTES = 240
MAX_NEW_CAMPAIGN_RANGES = 5



def current_time_utc() -> datetime:
    override = os.environ.get("CALLSHIELD_NOW")
    if override:
        return datetime.fromisoformat(override.replace("Z", "+00:00")).astimezone(timezone.utc)
    return datetime.now(timezone.utc)


def npanxx_of(number: str) -> str:
    """Return the NPA-NXX of a NANP number, or '' for non-NANP input.

    Only +1 numbers have an NPA-NXX. Taking "the first 6 digits" of an
    international number would key the range on its country-code digits —
    inert against the actual foreign campaign (the app matches on
    last-10-digits-first-6) AND colliding with a real US exchange, so three
    hot +44 303 1xx numbers would have flagged every legit caller in
    443-031 (Maryland)."""
    digits = "".join(ch for ch in number if "0" <= ch <= "9")
    if digits.startswith('1') and len(digits) == 11:
        digits = digits[1:]
    elif len(digits) != 10:
        return ""
    return digits[:6] if len(digits) >= 6 else ""


def main():
    print("=== CallShield Hot List Generator ===\n")

    now = current_time_utc()
    cutoff = now - timedelta(hours=HOT_WINDOW_HOURS)

    # ── Tally reports from pending report files ────────────────────────
    # Collected first, then collapsed, because near-simultaneous duplicates have
    # to be recognised against the other reports for the same number rather than
    # in file-glob order.
    pending: list[tuple[str, datetime, str, str, str, str]] = []

    if REPORTS_DIR.exists():
        for report_file in REPORTS_DIR.glob("*.json"):
            try:
                with open(report_file) as f:
                    report = json.load(f)

                number = validated_report_number(report.get("number", ""))
                spam_type = report.get("type", "unknown")
                if not number or spam_type == "not_spam":
                    continue

                reported_at_str = report.get("reported_at", "")
                reported_at = parse_reported_at(reported_at_str)
                reporter_bucket = validated_reporter_bucket(report.get("reporter_bucket"))
                if not reporter_bucket:
                    continue
                if reported_at is None or reported_at < cutoff or reported_at > now + timedelta(minutes=5):
                    continue

                pending.append(
                    (number, reported_at, reported_at_str, spam_type, report_file.name, reporter_bucket)
                )
            except Exception as e:
                print(f"  Skipping {report_file.name}: {e}")

    # Repeat submissions for the same number seconds apart are one reporter, not
    # velocity — see report_dedup for why the Worker cannot be relied on here.
    burst_duplicates = find_burst_duplicates(
        (number, reported_at, token) for number, reported_at, _, _, token, _ in pending
    )

    velocity: dict[str, dict] = {}
    seen_reporter_days: set[tuple[str, str, str]] = set()
    collapsed_identity = 0
    for number, reported_at, reported_at_str, spam_type, token, reporter_bucket in sorted(
        pending, key=lambda entry: (entry[1], entry[4])
    ):
        if token in burst_duplicates:
            continue
        day_key = reporter_day_key(reporter_bucket, reported_at)
        identity_key = (number, *day_key) if day_key is not None else None
        if identity_key is None or identity_key in seen_reporter_days:
            collapsed_identity += 1
            continue
        seen_reporter_days.add(identity_key)
        entry = velocity.get(number)
        if entry is None:
            velocity[number] = {
                "number": number,
                "type": spam_type,
                # `reports` is the count WITHIN the window — that is what
                # "hot" means. Lifetime totals live in total_reports.
                "reports": 1,
                "total_reports": 0,
                "first_seen": reported_at_str,
                "last_seen": reported_at_str,
                "description": "Trending community report",
                "_reporters": {reporter_bucket},
                "_times": [reported_at],
            }
        else:
            entry["reports"] += 1
            entry["_reporters"].add(reporter_bucket)
            entry["_times"].append(reported_at)
            if reported_at_str > entry["last_seen"]:
                entry["last_seen"] = reported_at_str
            if reported_at_str and reported_at_str < entry["first_seen"]:
                entry["first_seen"] = reported_at_str

    if burst_duplicates:
        print(
            f"  Collapsed {len(burst_duplicates)} burst-duplicate report(s) filed within "
            f"{BURST_DUPLICATE_SECONDS}s of a counted report for the same number"
        )
    if collapsed_identity:
        print(f"  Collapsed {collapsed_identity} same-reporter daily duplicate(s)")

    # ── Also include high-velocity numbers from main DB with recent last_seen ──
    if DB_FILE.exists():
        with open(DB_FILE) as f:
            db = json.load(f)

        today = now.strftime("%Y-%m-%d")
        yesterday = (now - timedelta(days=1)).strftime("%Y-%m-%d")

        for entry in db.get("numbers", []):
            last_seen = entry.get("last_seen", "")
            # Include DB numbers updated today or yesterday with 5+ total reports.
            # A plain string compare (`last_seen >= yesterday`) also passes
            # corrupt future dates like "2915-10-15", which would permanently
            # pin garbage rows to the hot list — bound both ends and require a
            # real calendar date.
            if not (yesterday <= last_seen <= today):
                continue
            try:
                datetime.strptime(last_seen[:10], "%Y-%m-%d")
            except ValueError:
                continue
            if entry.get("reports", 0) >= 5:
                num = validated_report_number(entry.get("number", ""))
                if not num:
                    continue
                # Record the lifetime total separately. Adding it to `reports`
                # made an old high-count row that happened to be touched
                # yesterday outrank a genuine burst, and could crowd real
                # trending numbers out of the top-N entirely.
                if num in velocity:
                    velocity[num]["total_reports"] = entry.get("reports", 0)
                else:
                    velocity[num] = {
                        "number": num,
                        "type": entry.get("type", "robocall"),
                        # Seen inside the window, but we cannot know how many of
                        # its lifetime reports landed there — credit it one.
                        "reports": 1,
                        "total_reports": entry.get("reports", 0),
                        "first_seen": entry.get("first_seen", last_seen),
                        "last_seen": last_seen,
                        "description": entry.get("description", "Community reported"),
                    }

    # ── Filter and rank ───────────────────────────────────────────────
    hot_internal = []
    for entry in velocity.values():
        times = entry.get("_times", [])
        reporters = entry.get("_reporters", set())
        if not times:
            continue
        span_minutes = int((max(times) - min(times)).total_seconds() // 60)
        entry["distinct_reporters"] = len(reporters)
        entry["report_span_minutes"] = span_minutes
        if (
            entry["reports"] >= MIN_REPORTS_HOT
            and entry["distinct_reporters"] >= MIN_REPORTERS_HOT
            and span_minutes >= MIN_REPORT_SPAN_MINUTES
        ):
            hot_internal.append(entry)
    # Rank by in-window velocity first; lifetime total only breaks ties.
    hot_internal.sort(key=lambda x: (x.get("reports", 0), x.get("total_reports", 0)), reverse=True)
    hot_internal = hot_internal[:HOT_LIST_SIZE]
    hot = [
        {key: value for key, value in entry.items() if not key.startswith("_")}
        for entry in hot_internal
    ]

    # ── Write hot_numbers.json ────────────────────────────────────────
    output = {
        "generated": now.isoformat(),
        "window_hours": HOT_WINDOW_HOURS,
        "min_reports": MIN_REPORTS_HOT,
        "min_distinct_reporters": MIN_REPORTERS_HOT,
        "min_report_span_minutes": MIN_REPORT_SPAN_MINUTES,
        "count": len(hot),
        "numbers": hot,
    }

    atomic_write_json(HOT_LIST_FILE, output)

    print(f"Hot list: {len(hot)} numbers in last {HOT_WINDOW_HOURS}h")
    print(f"Written to: {HOT_LIST_FILE}")

    if hot:
        print("\nTop 5 trending:")
        for entry in hot[:5]:
            print(f"  {entry['number']} — {entry['reports']} reports — {entry.get('description','')[:40]}")

    # ── Velocity spike alert (print for CI log visibility) ───────────
    spikes = [v for v in hot if v.get("reports", 0) >= 10]
    if spikes:
        print(f"\nWARNING: VELOCITY SPIKES ({len(spikes)} numbers with 10+ reports in 24h):")
        for s in spikes[:10]:
            print(f"  {s['number']} — {s['reports']} reports")

    # ── Campaign detection: NPA-NXX clustering ────────────────────────
    # When 3+ distinct numbers from the same NPA-NXX appear in the hot list,
    # a robocaller is likely running a campaign across that exchange. Flag the
    # entire range so the Android app can score calls from it even if the
    # specific number hasn't been reported yet.
    npanxx_entries: dict[str, list[dict]] = {}
    for entry in hot_internal:
        if (
            entry["reports"] < CAMPAIGN_REPORTS_PER_NUMBER
            or len(entry["_reporters"]) < CAMPAIGN_REPORTERS_PER_NUMBER
            or entry["report_span_minutes"] < CAMPAIGN_MIN_SPAN_MINUTES
        ):
            continue
        npanxx = npanxx_of(entry.get("number", ""))
        if npanxx:
            npanxx_entries.setdefault(npanxx, []).append(entry)

    hot_ranges = []
    for npanxx, entries in sorted(npanxx_entries.items(), key=lambda item: -len(item[1])):
        union_reporters = set().union(*(entry["_reporters"] for entry in entries))
        if len(entries) < CAMPAIGN_THRESHOLD or len(union_reporters) < CAMPAIGN_MIN_UNION_REPORTERS:
            continue
        hot_ranges.append(
            {
                "npanxx": npanxx,
                "count": len(entries),
                "distinct_reporters": len(union_reporters),
            }
        )
        if len(hot_ranges) >= MAX_NEW_CAMPAIGN_RANGES:
            break

    atomic_write_json(
        HOT_RANGES_FILE,
        {
            "generated": now.isoformat(),
            "threshold": CAMPAIGN_THRESHOLD,
            "min_reports_per_number": CAMPAIGN_REPORTS_PER_NUMBER,
            "min_distinct_reporters_per_number": CAMPAIGN_REPORTERS_PER_NUMBER,
            "min_union_reporters": CAMPAIGN_MIN_UNION_REPORTERS,
            "count": len(hot_ranges),
            "ranges": hot_ranges,
        },
    )

    print(f"\nHot campaign ranges: {len(hot_ranges)} NPA-NXX prefixes with {CAMPAIGN_THRESHOLD}+ distinct numbers")


if __name__ == "__main__":
    main()
