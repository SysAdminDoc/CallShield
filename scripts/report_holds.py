"""Numbers whose community reports are set aside.

A hold is the maintainer's answer to a scripted flood. The pipeline tells
reporters apart by a daily network bucket and by the id the app gives each
report. A script that changes address and makes up an id for every request
passes as a crowd on both counts, so nothing in the reports themselves gives it
away. What does is the pattern: the same few numbers, around the clock, from
networks that report nothing else.

Once a number is listed in data/spam_numbers_held.json, the two consumers that
can put a number on phones without a review skip its reports: the merge (the
database and the watch list) and the hot list. The report Worker reads the same
file from GitHub and drops those reports before they're committed, along with
everything else from the sending network until UTC midnight. The hold is about the reports,
not the number. A row another source backs (FCC, FTC, a maintainer review)
stays where it is, and a number can't be approved and held at once.

flood_candidates finds numbers that look sent together by a script, so the
merge and the hot list can stop and list them before a hold exists.
"""

import json
from collections import Counter, defaultdict
from collections.abc import Iterable
from datetime import datetime, timezone
from pathlib import Path

from phone_normalization import validated_report_number
from report_dedup import counted_reporter, parse_reported_at

HELD_NUMBERS_FILENAME = "spam_numbers_held.json"

# The flood check. Real reporters each report the numbers that called them, so
# two people rarely report the same two numbers on the same day. The script
# behind the 2026-11 flood sent a handful of numbers together from network
# after network. Over every report from 2026-10-16 to 2026-11-24 (3,928), a
# number reported by at least four reporter-days, at least half of which also
# reported one same other number, was always part of that flood: the five held
# numbers, a sixth +86 mobile sent with two of them on 2026-10-27, and an old
# Worker's +1 misread of one. The last two had no app ids, so they count as one
# reporter and never got that far. No number real people reported came close,
# in any window up to 14 days.
FLOOD_MIN_REPORTERS = 4
FLOOD_LOOKBACK_DAYS = 14


def load_held_numbers(data_dir: Path, today: str) -> set[str]:
    """The held numbers; a missing file means none.

    A file that exists but can't be read, or an entry that doesn't check out,
    stops the run. Skipping it would count reports the maintainer set aside.
    Each entry needs the number, a held_at date and a note on what was seen.
    To lift a hold, delete its entry: reports that arrive after that count.
    """
    held_file = data_dir / HELD_NUMBERS_FILENAME
    if not held_file.exists():
        return set()
    payload = json.loads(held_file.read_text(encoding="utf-8"))
    holds = payload.get("held") if isinstance(payload, dict) else None
    if not isinstance(holds, list):
        raise TypeError(f"{held_file.name} must hold a 'held' list")
    numbers: set[str] = set()
    for hold in holds:
        if not isinstance(hold, dict):
            raise TypeError(f"{held_file.name}: every hold must be an object")
        number = validated_report_number(str(hold.get("number", "")))
        if not number:
            raise ValueError(f"{held_file.name}: {hold.get('number')!r} is not a plausible number")
        if number in numbers:
            raise ValueError(f"{held_file.name}: {number} is listed more than once. Keep one entry per number")
        held_at = hold.get("held_at")
        if not isinstance(held_at, str) or not _is_day(held_at) or held_at > today:
            raise ValueError(f"{held_file.name}: {number} needs a held_at date no later than today")
        note = hold.get("note")
        if not isinstance(note, str) or not note.strip():
            raise ValueError(f"{held_file.name}: {number} needs a note saying what was seen")
        numbers.add(number)
    return numbers


def reporter_day(report: dict) -> tuple[str, str, str] | None:
    """The reporter a report counts as, on its UTC day: (day, group, device), or None.

    The reporter is report_dedup.counted_reporter, so every report without an
    app id is one reporter a day, as the merge and the hot list count them.
    """
    reported_at = parse_reported_at(report.get("reported_at"))
    identity = counted_reporter(report)
    if reported_at is None or identity is None:
        return None
    return (reported_at.astimezone(timezone.utc).date().isoformat(), *identity)


def flood_candidates(reports: Iterable[tuple[str, tuple[str, str, str] | None]]) -> dict[str, dict]:
    """Numbers whose reports look sent together by one script, with what they were sent with.

    `reports` holds (normalized number, reporter_day) pairs, every vote
    included; a None reporter is skipped. A number is a candidate when at least
    FLOOD_MIN_REPORTERS reporters sent it and at least half of them also sent
    some one other number. Each candidate maps to {"reporters": n, "partners":
    [(other number, how many of its reporters sent it), ...]}, most first.
    Nothing here changes a count: the scripts that call it stop so a hold can
    go in first.
    """
    numbers_by_reporter: dict[tuple, set[str]] = defaultdict(set)
    reporters_by_number: dict[str, set[tuple]] = defaultdict(set)
    for number, reporter in reports:
        if reporter is None:
            continue
        numbers_by_reporter[reporter].add(number)
        reporters_by_number[number].add(reporter)
    candidates: dict[str, dict] = {}
    for number, reporters in reporters_by_number.items():
        if len(reporters) < FLOOD_MIN_REPORTERS:
            continue
        together = Counter(other for reporter in reporters for other in numbers_by_reporter[reporter] if other != number)
        partners = sorted(
            ((other, count) for other, count in together.items() if count * 2 >= len(reporters)),
            key=lambda pair: (-pair[1], pair[0]),
        )
        if partners:
            candidates[number] = {"reporters": len(reporters), "partners": partners}
    return candidates


def flood_report(candidates: dict[str, dict], held: set[str]) -> str:
    """The message for flood candidates that aren't held, or "" when there are none."""
    unheld = sorted(number for number in candidates if number not in held)
    if not unheld:
        return ""
    lines = [
        f"Flood check: {len(unheld)} number(s) were sent together with other numbers by the same reporters,",
        "the shape a script leaves. Nothing was written.",
    ]
    for number in unheld:
        candidate = candidates[number]
        partners = ", ".join(
            f"{other}{' (held)' if other in held else ''} by {count}" for other, count in candidate["partners"][:4]
        )
        lines.append(f"  {number}: {candidate['reporters']} reporters, who also sent {partners}")
    lines.append(
        f"Hold them in data/{HELD_NUMBERS_FILENAME} and rerun, or rerun with --allow-floods if real people sent them."
    )
    return "\n".join(lines)


def _is_day(value: str) -> bool:
    try:
        datetime.strptime(value, "%Y-%m-%d")
    except ValueError:
        return False
    return True
