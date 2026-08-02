"""Evidence and freshness rules for public spam-data sources.

The registry is deliberately data-driven. Importers can add a source only when
its access mode, licence, geography, redistribution terms, and freshness policy
are explicit. Runtime callers never need to know whether a source is public or
operator-gated; the snapshot records that distinction for review.
"""

from __future__ import annotations

from datetime import datetime, timezone
import json
from pathlib import Path
from typing import Any, Mapping


REQUIRED_FIELDS = {
    "id",
    "access_mode",
    "geography",
    "license",
    "attribution",
    "cadence",
    "parser_version",
    "redistributable",
    "stale_after_days",
}


def load_source_manifest(path: Path) -> dict[str, Any]:
    """Load and validate the source registry before a network run starts."""

    with path.open(encoding="utf-8") as handle:
        manifest = json.load(handle)
    if not isinstance(manifest, dict) or not isinstance(manifest.get("sources"), list):
        raise ValueError("source manifest must contain a sources array")
    if not isinstance(manifest.get("version"), int):
        raise ValueError("source manifest version must be an integer")

    seen: set[str] = set()
    for source in manifest["sources"]:
        if not isinstance(source, dict) or not REQUIRED_FIELDS.issubset(source):
            raise ValueError(f"source entry is missing required fields: {source!r}")
        source_id = source["id"]
        if not isinstance(source_id, str) or not source_id.strip() or source_id in seen:
            raise ValueError(f"source ids must be unique non-empty strings: {source_id!r}")
        seen.add(source_id)
        if not isinstance(source["redistributable"], bool):
            raise ValueError(f"{source_id}: redistributable must be boolean")
        if not isinstance(source["stale_after_days"], int) or source["stale_after_days"] <= 0:
            raise ValueError(f"{source_id}: stale_after_days must be positive")
    return manifest


def source_snapshot(
    manifest: Mapping[str, Any],
    stats: Mapping[str, Mapping[str, Any]],
    *,
    fetched_at: str | None = None,
) -> dict[str, Any]:
    """Return a deterministic, reviewable snapshot for one importer run."""

    timestamp = fetched_at or datetime.now(timezone.utc).replace(microsecond=0).isoformat()
    rows = []
    for source in manifest["sources"]:
        source_id = source["id"]
        result = dict(stats.get(source_id, {}))
        rows.append(
            {
                "id": source_id,
                "access_mode": source["access_mode"],
                "geography": source["geography"],
                "license": source["license"],
                "attribution": source["attribution"],
                "cadence": source["cadence"],
                "parser_version": source["parser_version"],
                "redistributable": source["redistributable"],
                "stale_after_days": source["stale_after_days"],
                "fetched_at": timestamp if result else None,
                "status": result.get("status", "not_requested"),
                "accepted": int(result.get("accepted", 0)),
                "rejected": int(result.get("rejected", 0)),
                "error": result.get("error"),
            }
        )
    return {
        "schema_version": 1,
        "generated_at": timestamp,
        "sources": rows,
    }
