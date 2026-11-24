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
"""

import json
from datetime import datetime
from pathlib import Path

from phone_normalization import validated_report_number

HELD_NUMBERS_FILENAME = "spam_numbers_held.json"


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


def _is_day(value: str) -> bool:
    try:
        datetime.strptime(value, "%Y-%m-%d")
    except ValueError:
        return False
    return True
