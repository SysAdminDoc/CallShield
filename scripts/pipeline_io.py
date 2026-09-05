#!/usr/bin/env python3
"""Shared I/O helpers for the CallShield data pipeline.

Every script here writes files that are published to devices. A crash or a
kill mid-write must never leave a truncated JSON file behind: the auto-commit
that follows would publish it, and clients would fail to parse the feed and
silently stop updating until the next regeneration.
"""

from __future__ import annotations

import hashlib
import json
import math
import os
from pathlib import Path
from typing import Any


class FeedCollapseError(RuntimeError):
    """Raised when a derived feed would overwrite healthy data with a collapse."""


def atomic_write_json(path: Path, payload: Any, indent: int = 2) -> None:
    """Write JSON via a temp file + os.replace so readers only ever observe a
    complete file.

    The payload is re-parsed from the temp file before the swap, so a
    serialization bug cannot publish a file that does not load.
    """
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=indent)
    with open(tmp, encoding="utf-8") as f:  # validate before swapping into place
        json.load(f)
    os.replace(tmp, path)


def report_queue_digest(reports_dir: Path) -> str:
    """Return a stable digest of the active report queue.

    The merge step consumes these files, so derived feeds record this digest to
    prove they were generated from the same queue. Rejected reports live in a
    subdirectory and are intentionally excluded.
    """
    digest = hashlib.sha256()
    reports_dir = Path(reports_dir)
    if not reports_dir.exists():
        return digest.hexdigest()
    for report_file in sorted(reports_dir.glob("*.json")):
        digest.update(report_file.name.encode("utf-8"))
        digest.update(b"\0")
        digest.update(report_file.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def _payload_count(payload: Any, item_key: str) -> int:
    if not isinstance(payload, dict):
        raise FeedCollapseError("feed payload must be a JSON object")
    items = payload.get(item_key)
    if isinstance(items, list):
        return len(items)
    count = payload.get("count")
    if isinstance(count, int) and count >= 0:
        return count
    raise FeedCollapseError(f"feed payload has no countable {item_key!r} field")


def ensure_feed_not_collapsed(
    path: Path,
    payload: Any,
    *,
    item_key: str,
    absolute_floor: int,
    previous_ratio: float,
    allow_collapse: bool,
) -> None:
    """Reject a suspiciously small feed before its output is replaced.

    The absolute floor protects a first generated artifact. The relative floor
    protects a previously healthy artifact from a source outage or an ordering
    mistake. An explicit ``--allow-collapse`` is required to publish a known
    empty/small result.
    """
    if allow_collapse:
        return
    current_count = _payload_count(payload, item_key)
    path = Path(path)
    previous_count = 0
    if path.exists():
        try:
            with path.open(encoding="utf-8") as existing_file:
                previous_count = _payload_count(json.load(existing_file), item_key)
        except (OSError, ValueError, FeedCollapseError) as error:
            raise FeedCollapseError(f"cannot safely replace unreadable feed {path}: {error}") from error

    relative_floor = math.ceil(previous_count * previous_ratio)
    floor = max(absolute_floor, relative_floor)
    if current_count < floor:
        raise FeedCollapseError(
            f"refusing to collapse {path}: {current_count} {item_key} would replace "
            f"{previous_count}; minimum is {floor}. Re-run with --allow-collapse "
            "only after verifying the source outage or intentional clear."
        )


def is_deliberate_clear(payload: Any, *, item_key: str, approved: bool) -> bool:
    """Return whether this feed is being published empty on purpose.

    The client distinguishes "the publisher says there is nothing" from "the
    feed did not arrive": `HotDataSync.shouldApplyFeed` only replaces local rows
    with an empty feed when the feed says it was cleared, and otherwise treats
    the feed as unavailable and keeps what it has. Nothing on this side ever
    wrote that flag, so the deliberate-clear path was unreachable and a healthy
    publisher with nothing to report looked identical to an outage.

    ``approved`` must be this feed's own approval, not the run's. They are not
    the same thing: ``--allow-collapse`` forces the collapse guard for every
    feed a run writes, so reading it here made approving a collapse of the
    numbers feed also tell every device to delete its campaign ranges. Naming
    the feed in ``--cleared`` is the only way to assert a deliberate clear.

    Approval alone is not enough - a run that produced rows cleared nothing, so
    the flag stays false whatever was approved.
    """
    return approved and _payload_count(payload, item_key) == 0


def parse_cleared_feeds(value: str | None, *, known: frozenset[str]) -> frozenset[str]:
    """Parse a --cleared list, rejecting names that are not real feeds.

    A typo must not silently mean "approve nothing"; that would look like the
    flag worked while the publisher kept saying the feed merely failed.
    """
    if not value:
        return frozenset()
    named = frozenset(part.strip() for part in value.split(",") if part.strip())
    unknown = named - known
    if unknown:
        raise ValueError(
            f"unknown feed name(s) in --cleared: {', '.join(sorted(unknown))}. "
            f"Known feeds: {', '.join(sorted(known))}"
        )
    return named


def require_matching_derived_feed(
    path: Path,
    *,
    report_digest: str,
) -> None:
    """Require a derived feed generated from the current report queue."""
    path = Path(path)
    try:
        with path.open(encoding="utf-8") as feed_file:
            payload = json.load(feed_file)
    except (OSError, ValueError) as error:
        raise RuntimeError(f"derived feed {path} is missing or unreadable: {error}") from error
    if not isinstance(payload, dict) or payload.get("input_report_digest") != report_digest:
        raise RuntimeError(
            f"derived feed {path} does not match the current report queue; "
            "run generate_hot_list.py and extract_spam_domains.py before merging"
        )
