#!/usr/bin/env python3
"""
CallShield Hot List Generator

Generates data/hot_numbers.json — a lightweight list of the top numbers
reported by the community in the last 24 hours. The Android app syncs
this every 30 minutes so users get protection against trending spam numbers
hours before the nightly full-database merge.

Called by the merge-reports GitHub Action workflow after each merge run.
"""

import json
import re
from datetime import datetime, timezone, timedelta
from pathlib import Path

DATA_DIR = Path(__file__).parent.parent / "data"
REPORTS_DIR = DATA_DIR / "reports"
DB_FILE = DATA_DIR / "spam_numbers.json"
HOT_LIST_FILE = DATA_DIR / "hot_numbers.json"
HOT_RANGES_FILE = DATA_DIR / "hot_ranges.json"

HOT_LIST_SIZE = 500         # Max numbers to include
HOT_WINDOW_HOURS = 24       # Look back this many hours
MIN_REPORTS_HOT = 2         # Minimum community reports to appear in hot list
CAMPAIGN_THRESHOLD = 3      # Distinct numbers from same NPA-NXX to flag the range


def npanxx_of(number: str) -> str:
    """Return first 6 digits of a US phone number, or '' if invalid."""
    digits = re.sub(r'\D', '', number)
    if digits.startswith('1') and len(digits) == 11:
        digits = digits[1:]
    return digits[:6] if len(digits) >= 6 else ""


def main():
    print("=== CallShield Hot List Generator ===\n")

    now = datetime.now(timezone.utc)
    cutoff = now - timedelta(hours=HOT_WINDOW_HOURS)

    # ── Tally reports from pending report files ────────────────────────
    velocity: dict[str, dict] = {}

    if REPORTS_DIR.exists():
        for report_file in REPORTS_DIR.glob("*.json"):
            try:
                with open(report_file) as f:
                    report = json.load(f)

                number = report.get("number", "")
                spam_type = report.get("type", "unknown")
                if not number or spam_type == "not_spam":
                    continue

                reported_at_str = report.get("reported_at", "")
                try:
                    reported_at = datetime.fromisoformat(
                        reported_at_str.replace("Z", "+00:00")
                    )
                    if reported_at < cutoff:
                        continue  # Too old for hot list
                except (ValueError, AttributeError):
                    pass  # Include if timestamp is unparseable

                if number in velocity:
                    velocity[number]["reports"] += 1
                    velocity[number]["last_seen"] = reported_at_str
                else:
                    velocity[number] = {
                        "number": number,
                        "type": spam_type,
                        "reports": 1,
                        "first_seen": reported_at_str,
                        "last_seen": reported_at_str,
                        "description": "Trending community report",
                    }
            except Exception as e:
                print(f"  Skipping {report_file.name}: {e}")

    # ── Also include high-velocity numbers from main DB with recent last_seen ──
    if DB_FILE.exists():
        with open(DB_FILE) as f:
            db = json.load(f)

        today = now.strftime("%Y-%m-%d")
        yesterday = (now - timedelta(days=1)).strftime("%Y-%m-%d")

        for entry in db.get("numbers", []):
            last_seen = entry.get("last_seen", "")
            # Include DB numbers updated today or yesterday with 5+ total reports
            if last_seen >= yesterday and entry.get("reports", 0) >= 5:
                num = entry["number"]
                if num in velocity:
                    # Already in community reports — merge
                    velocity[num]["reports"] += entry["reports"]
                else:
                    velocity[num] = {
                        "number": num,
                        "type": entry.get("type", "robocall"),
                        "reports": entry.get("reports", 1),
                        "first_seen": entry.get("first_seen", last_seen),
                        "last_seen": last_seen,
                        "description": entry.get("description", "Community reported"),
                    }

    # ── Filter and rank ───────────────────────────────────────────────
    hot = [v for v in velocity.values() if v.get("reports", 0) >= MIN_REPORTS_HOT]
    hot.sort(key=lambda x: x.get("reports", 0), reverse=True)
    hot = hot[:HOT_LIST_SIZE]

    # ── Write hot_numbers.json ────────────────────────────────────────
    output = {
        "generated": now.isoformat(),
        "window_hours": HOT_WINDOW_HOURS,
        "count": len(hot),
        "numbers": hot,
    }

    with open(HOT_LIST_FILE, "w") as f:
        json.dump(output, f, indent=2)

    print(f"Hot list: {len(hot)} numbers in last {HOT_WINDOW_HOURS}h")
    print(f"Written to: {HOT_LIST_FILE}")

    if hot:
        print("\nTop 5 trending:")
        for entry in hot[:5]:
            print(f"  {entry['number']} — {entry['reports']} reports — {entry.get('description','')[:40]}")

    # ── Velocity spike alert (print for CI log visibility) ───────────
    spikes = [v for v in hot if v.get("reports", 0) >= 10]
    if spikes:
        print(f"\n⚠ VELOCITY SPIKES ({len(spikes)} numbers with 10+ reports in 24h):")
        for s in spikes[:10]:
            print(f"  {s['number']} — {s['reports']} reports")

    # ── Campaign detection: NPA-NXX clustering ────────────────────────
    # When 3+ distinct numbers from the same NPA-NXX appear in the hot list,
    # a robocaller is likely running a campaign across that exchange. Flag the
    # entire range so the Android app can score calls from it even if the
    # specific number hasn't been reported yet.
    npanxx_counts: dict[str, int] = {}
    for entry in hot:
        npanxx = npanxx_of(entry.get("number", ""))
        if npanxx:
            npanxx_counts[npanxx] = npanxx_counts.get(npanxx, 0) + 1

    hot_ranges = [
        {"npanxx": npanxx, "count": count}
        for npanxx, count in sorted(npanxx_counts.items(), key=lambda x: -x[1])
        if count >= CAMPAIGN_THRESHOLD
    ]

    with open(HOT_RANGES_FILE, "w") as f:
        json.dump({
            "generated": now.isoformat(),
            "threshold": CAMPAIGN_THRESHOLD,
            "count": len(hot_ranges),
            "ranges": hot_ranges,
        }, f, indent=2)

    print(f"\nHot campaign ranges: {len(hot_ranges)} NPA-NXX prefixes with {CAMPAIGN_THRESHOLD}+ distinct numbers")


if __name__ == "__main__":
    main()
