"""Burst-duplicate detection shared by the community-report consumers.

The Cloudflare Worker is the intended guard against one reporter submitting the
same number over and over: `checkDedup` rejects a repeat from the same IP within
`DEDUP_WINDOW_S` (300s). But that check is deliberately permissive when the
`RATE_LIMIT` KV binding is missing, so a stale or misconfigured deployment
accepts every repeat. Observed live on 2026-07-30: `+12385233476` was accepted
twice ten seconds apart and went straight onto the hot list, and two fictional
555 numbers were accepted 9 and 12 times each.

Both consumers of `data/reports/` therefore need a backstop:

* `generate_hot_list.py` — duplicates read as velocity and promote the number
  onto the hot list, which every device syncs every 30 minutes into a
  high-priority checker.
* `merge_community_reports.py` — duplicates inflate the shipped `reports` count,
  and a burst of `not_spam` votes can de-list a genuine community row one vote
  at a time.

Reports carry no reporter identity (anonymous by design), so this cannot tell
"one reporter twice" from "two reporters once each". The window is therefore
deliberately much tighter than the Worker's 300s: a genuine campaign routinely
draws two independent reports within five minutes, and collapsing those would
destroy the real signal these feeds exist to carry. Within a minute, a repeat is
far more likely one person double-tapping. Server-side dedup remains the real
guard; this only limits the damage when it is not running.
"""

from datetime import datetime, timezone

BURST_DUPLICATE_SECONDS = 60


def parse_reported_at(value: object) -> datetime | None:
    """Parse a report's `reported_at`, or None when it is missing/unparseable."""
    if not isinstance(value, str) or not value:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    # Naive timestamps are treated as UTC so comparisons never raise.
    return parsed if parsed.tzinfo is not None else parsed.replace(tzinfo=timezone.utc)


def find_burst_duplicates(entries, window_seconds: int = BURST_DUPLICATE_SECONDS) -> set:
    """Return the tokens of entries that repeat an earlier one within the window.

    `entries` is an iterable of `(key, timestamp, token)`:
      key       - what counts as "the same report" (a number, or number+type)
      timestamp - a `datetime` or None; None is never treated as a duplicate,
                  because an unparseable timestamp is no evidence of a burst
      token     - the caller's handle for the entry (filename, index, ...)

    Tokens must be unique and hashable. The first report in a burst is kept; the
    ones following it inside the window are returned.
    """
    grouped: dict[object, list] = {}
    for key, timestamp, token in entries:
        grouped.setdefault(key, []).append((timestamp, token))

    duplicates = set()
    epoch = datetime.min.replace(tzinfo=timezone.utc)
    for group in grouped.values():
        # Unparseable timestamps sort last so they never suppress a real report.
        group.sort(key=lambda item: (item[0] is None, item[0] or epoch))
        last_counted: datetime | None = None
        for timestamp, token in group:
            if (
                timestamp is not None
                and last_counted is not None
                and (timestamp - last_counted).total_seconds() < window_seconds
            ):
                duplicates.add(token)
                continue
            if timestamp is not None:
                last_counted = timestamp
    return duplicates
