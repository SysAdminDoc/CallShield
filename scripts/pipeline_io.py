#!/usr/bin/env python3
"""Shared I/O helpers for the CallShield data pipeline.

Every script here writes files that are published to devices. A crash or a
kill mid-write must never leave a truncated JSON file behind: the auto-commit
that follows would publish it, and clients would fail to parse the feed and
silently stop updating until the next regeneration.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any


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
