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


_SOURCE_ID_ALIASES = {
    "community": "community_reports",
}


def _canonical_source_id(source_id: Any) -> str:
    value = str(source_id or "").strip()
    return _SOURCE_ID_ALIASES.get(value, value)


def _entry_source_ids(entry: Mapping[str, Any]) -> set[str]:
    evidence = entry.get("evidence")
    ids = {
        _canonical_source_id(item.get("source_id"))
        for item in evidence or []
        if isinstance(item, Mapping) and item.get("source_id")
    }
    if ids:
        return ids
    sources = entry.get("sources")
    return {
        _canonical_source_id(source)
        for source in sources or []
        if isinstance(source, str) and source.strip()
    }


def _parse_timestamp(value: Any) -> datetime | None:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed.replace(tzinfo=timezone.utc) if parsed.tzinfo is None else parsed


def _freshness_state(source: Mapping[str, Any], generated_at: datetime) -> str:
    if source.get("status") == "error":
        return "failed"
    last_success = _parse_timestamp(source.get("last_success_at"))
    if last_success is None:
        return "never"
    age = max(0.0, (generated_at - last_success).total_seconds())
    stale_after_days = max(1, int(source.get("stale_after_days", 30)))
    return "stale" if age > stale_after_days * 86_400 else "fresh"


def _false_positive_rate(
    not_spam_votes: int,
    spam_reports: int,
) -> float:
    denominator = not_spam_votes + spam_reports
    if denominator <= 0:
        return 0.0
    return round(not_spam_votes / denominator, 4)


def source_health_report(
    snapshot: Mapping[str, Any],
    database: Mapping[str, Any],
    review: Mapping[str, Any] | None = None,
    *,
    quarantined_count: int = 0,
    quarantined_this_run: int = 0,
    generated_at: str | None = None,
) -> dict[str, Any]:
    """Build an aggregate review report without exporting user content.

    The database and review files are read locally by the maintainer. Only
    counts, source IDs, freshness states, and bounded rates are emitted. No
    phone number, SMS text, contact, or audio field is copied into the report.
    """

    timestamp = generated_at or str(snapshot.get("generated_at") or datetime.now(timezone.utc).isoformat())
    generated = _parse_timestamp(timestamp) or datetime.now(timezone.utc)
    entries = [entry for entry in database.get("numbers", []) if isinstance(entry, Mapping)]
    evidence_rows: dict[str, int] = {}
    corroborated_rows: dict[str, int] = {}
    by_number = {
        str(entry.get("number")): entry
        for entry in entries
        if entry.get("number")
    }
    for entry in entries:
        source_ids = _entry_source_ids(entry)
        for source_id in source_ids:
            evidence_rows[source_id] = evidence_rows.get(source_id, 0) + 1
        if len(source_ids) >= 2:
            for source_id in source_ids:
                corroborated_rows[source_id] = corroborated_rows.get(source_id, 0) + 1

    false_positive: dict[str, dict[str, int]] = {}
    for candidate in (review or {}).get("candidates", []):
        if not isinstance(candidate, Mapping):
            continue
        source_ids = {
            _canonical_source_id(source_id)
            for source_id in candidate.get("source_ids", [])
            if isinstance(source_id, str) and source_id.strip()
        }
        if not source_ids:
            source_ids = _entry_source_ids(by_number.get(str(candidate.get("number")), {}))
        if not source_ids:
            source_ids = {"community_reports"}
        try:
            votes = max(0, int(candidate.get("not_spam_votes", 0)))
            reports = max(0, int(candidate.get("spam_reports", 0)))
        except (TypeError, ValueError):
            continue
        for source_id in source_ids:
            stats = false_positive.setdefault(
                source_id,
                {"candidates": 0, "not_spam_votes": 0, "spam_reports": 0},
            )
            stats["candidates"] += 1
            stats["not_spam_votes"] += votes
            stats["spam_reports"] += reports

    source_ids = {
        _canonical_source_id(source.get("id"))
        for source in snapshot.get("sources", [])
        if isinstance(source, Mapping) and source.get("id")
    }
    source_ids.update(evidence_rows)
    source_ids.update(false_positive)
    if quarantined_count:
        source_ids.add("community_reports")

    source_by_id = {
        _canonical_source_id(source.get("id")): source
        for source in snapshot.get("sources", [])
        if isinstance(source, Mapping) and source.get("id")
    }
    source_rows = []
    for source_id in sorted(source_ids):
        source = source_by_id.get(source_id, {})
        fp = false_positive.get(
            source_id,
            {"candidates": 0, "not_spam_votes": 0, "spam_reports": 0},
        )
        state = _freshness_state(source, generated) if source else "unknown"
        source_rows.append(
            {
                "id": source_id,
                "freshness": state,
                "status": source.get("status", "not_in_snapshot"),
                "accepted": int(source.get("accepted", 0)),
                "rejected": int(source.get("rejected", 0)),
                "evidence_rows": evidence_rows.get(source_id, 0),
                "corroborated_rows": corroborated_rows.get(source_id, 0),
                "false_positive_candidates": fp["candidates"],
                "not_spam_votes": fp["not_spam_votes"],
                "spam_reports": fp["spam_reports"],
                "false_positive_rate": _false_positive_rate(fp["not_spam_votes"], fp["spam_reports"]),
                "quarantined": quarantined_count if source_id == "community_reports" else 0,
            }
        )

    freshness_counts = {
        state: sum(1 for source in source_rows if source["freshness"] == state)
        for state in ("fresh", "stale", "failed", "never", "unknown")
    }
    return {
        "schema_version": 1,
        "generated_at": timestamp,
        "privacy": {
            "raw_phone_numbers": False,
            "raw_contacts": False,
            "raw_sms": False,
            "call_audio": False,
        },
        "summary": {
            "source_count": len(source_rows),
            "fresh_source_count": freshness_counts["fresh"],
            "stale_source_count": freshness_counts["stale"],
            "failed_source_count": freshness_counts["failed"],
            "never_requested_source_count": freshness_counts["never"],
            "evidence_row_count": sum(evidence_rows.values()),
            "corroborated_row_count": len(
                {
                    str(entry.get("number"))
                    for entry in entries
                    if len(_entry_source_ids(entry)) >= 2
                }
            ),
            "false_positive_candidate_count": sum(
                stats["candidates"] for stats in false_positive.values()
            ),
            "quarantined_count": quarantined_count,
            "quarantined_this_run": quarantined_this_run,
        },
        "sources": source_rows,
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
