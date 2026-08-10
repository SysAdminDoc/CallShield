"""Evidence and freshness rules for public spam-data sources.

The registry is deliberately data-driven. Importers can add a source only when
its access mode, licence, geography, redistribution terms, and freshness policy
are explicit. Runtime callers never need to know whether a source is public or
operator-gated; the snapshot records that distinction for review.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
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
    "evidence_type",
    "confidence_tier",
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
                "evidence_type": source["evidence_type"],
                "confidence_tier": source["confidence_tier"],
                "fetched_at": timestamp if result else None,
                "status": result.get("status", "not_requested"),
                "accepted": int(result.get("accepted", 0)),
                "rejected": int(result.get("rejected", 0)),
                "checksum": result.get("checksum"),
                "last_success_at": result.get("last_success_at"),
                "last_failure_at": result.get("last_failure_at"),
                "error": result.get("error"),
                "cursor": result.get("cursor"),
            }
        )
    return {
        "schema_version": 1,
        "generated_at": timestamp,
        "sources": rows,
    }


def source_evidence(
    manifest: Mapping[str, Any],
    source_id: str,
    entry: Mapping[str, Any],
    *,
    retrieved_at: str | None = None,
) -> dict[str, Any]:
    """Build the immutable evidence record attached to one imported row."""

    source = next((item for item in manifest["sources"] if item["id"] == source_id), None)
    if source is None:
        raise ValueError(f"unknown source id: {source_id}")
    timestamp = retrieved_at or datetime.now(timezone.utc).replace(microsecond=0).isoformat()
    try:
        fetched = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ValueError(f"invalid retrieved_at timestamp: {timestamp}") from exc
    expires_at = fetched + timedelta(days=source["stale_after_days"])
    evidence = {
        "source_id": source_id,
        "evidence_type": source["evidence_type"],
        "license": source["license"],
        "attribution": source["attribution"],
        "first_seen": str(entry.get("first_seen", "")),
        "last_seen": str(entry.get("last_seen", "")),
        "retrieved_at": timestamp,
        "geography": source["geography"],
        "confidence_tier": source["confidence_tier"],
        "parser_version": source["parser_version"],
        "expires_at_epoch_ms": int(expires_at.timestamp() * 1000),
    }
    for field in ("complaint_role", "spoof_signal"):
        value = entry.get(field)
        if value not in (None, ""):
            evidence[field] = str(value)
    return evidence


def attach_source_evidence(
    entries: list[dict[str, Any]],
    manifest: Mapping[str, Any],
    source_id: str,
    *,
    retrieved_at: str | None = None,
) -> list[dict[str, Any]]:
    """Attach a fresh evidence record to every row returned by an adapter."""

    for entry in entries:
        evidence = list(entry.get("evidence", []))
        evidence.append(source_evidence(manifest, source_id, entry, retrieved_at=retrieved_at))
        entry["evidence"] = evidence
    return entries


def merge_evidence(
    current: list[dict[str, Any]] | None,
    incoming: list[dict[str, Any]] | None,
) -> list[dict[str, Any]]:
    """Merge one source's refreshed evidence without duplicating a rerun."""

    def evidence_key(item: Mapping[str, Any]) -> tuple[str, str, str]:
        return (
            str(item.get("source_id", "")),
            str(item.get("complaint_role", "")),
            str(item.get("spoof_signal", "")),
        )

    by_source = {
        evidence_key(item): item
        for item in current or []
        if item.get("source_id")
    }
    for item in incoming or []:
        source_id = item.get("source_id")
        if source_id:
            by_source[evidence_key(item)] = item
    return sorted(
        by_source.values(),
        key=lambda item: (
            item["source_id"],
            item.get("complaint_role", ""),
            item.get("spoof_signal", ""),
        ),
    )
