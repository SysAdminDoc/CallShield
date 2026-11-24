"""Print the report Worker's daily counts (GET /stats).

Each day shows how many reports the Worker stored, refused as a repeat, and
dropped: "held" for a held number, "banned" for anything from a network banned
that day. The "app" column under each drop counts the ones whose user agent was
the app's, the reports a ban took from real users rather than a script.

The token is the Worker's STATS_TOKEN secret, read from CALLSHIELD_STATS_TOKEN.
Without it, or with a wrong one, the Worker answers 404.

    python scripts/report_service_stats.py [--days 14] [--json]
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

WORKER_URL = "https://callshield-reports.snafumatthew.workers.dev"
COLUMNS = (
    ("stored", "stored"),
    ("duplicate", "repeat"),
    ("held", "held"),
    ("held_app", "app"),
    ("banned", "banned"),
    ("banned_app", "app"),
)


def fetch_counts(url: str, token: str) -> dict:
    request = urllib.request.Request(
        f"{url.rstrip('/')}/stats",
        headers={"Authorization": f"Bearer {token}", "User-Agent": "callshield-stats"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def format_counts(payload: dict, days: int) -> str:
    by_day = payload.get("days") or {}
    header = "day         " + "".join(f"{label:>8}" for _, label in COLUMNS)
    lines = [header]
    for day in sorted(by_day)[-days:]:
        counts = by_day[day]
        lines.append(day + "  " + "".join(f"{counts.get(kind, 0):>8}" for kind, _ in COLUMNS))
    if len(lines) == 1:
        lines.append("(no counts yet)")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Print the report Worker's daily counts.")
    parser.add_argument("--days", type=int, default=14, help="how many recent days to show (default 14)")
    parser.add_argument("--json", action="store_true", help="print the Worker's JSON as it came")
    parser.add_argument("--url", default=os.environ.get("CALLSHIELD_WORKER_URL", WORKER_URL))
    args = parser.parse_args()
    token = os.environ.get("CALLSHIELD_STATS_TOKEN", "")
    if not token:
        print("Set CALLSHIELD_STATS_TOKEN to the Worker's STATS_TOKEN.", file=sys.stderr)
        return 2
    try:
        payload = fetch_counts(args.url, token)
    except urllib.error.HTTPError as error:
        reason = "the token was refused or the Worker has no marker store" if error.code == 404 else error.reason
        print(f"The Worker answered {error.code}: {reason}.", file=sys.stderr)
        return 1
    except (urllib.error.URLError, TimeoutError, ValueError) as error:
        print(f"Couldn't read the counts: {error}", file=sys.stderr)
        return 1
    print(json.dumps(payload, indent=2) if args.json else format_counts(payload, args.days))
    return 0


if __name__ == "__main__":
    sys.exit(main())
