#!/usr/bin/env python3
"""Build the content-addressed shard set for the spam database.

The legacy ``data/spam_numbers.json`` file remains the compatibility endpoint
for older CallShield releases. New releases consume the manifest and fetch
only changed shards. Shard membership is based on the first two hexadecimal
characters of a SHA-256 digest, so a normal single-row update changes one small
file instead of rewriting the complete database.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
from typing import Any

SHARD_COUNT = 256
SHARD_DIRECTORY_NAME = "spam_number_shards"
MANIFEST_FILENAME = "spam_numbers.manifest.json"
SHARD_PATH_PREFIX = f"data/{SHARD_DIRECTORY_NAME}/"


def shard_id_for(value: str) -> str:
    """Return a stable two-hex-digit shard id for a number or prefix."""

    digest = hashlib.sha256(str(value).strip().encode("utf-8")).hexdigest()
    return digest[:2]


def _json_bytes(payload: Any) -> bytes:
    return json.dumps(payload, indent=2, ensure_ascii=True).encode("utf-8")


def _write_json_if_changed(path: Path, payload: Any) -> bool:
    encoded = _json_bytes(payload)
    if path.exists() and path.read_bytes() == encoded:
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = path.with_suffix(path.suffix + ".tmp")
    temporary_path.write_bytes(encoded)
    with temporary_path.open(encoding="utf-8") as written:
        json.load(written)
    os.replace(temporary_path, path)
    return True


def build_shard_payloads(database: dict[str, Any]) -> dict[str, dict[str, Any]]:
    """Partition a legacy database into deterministic, independently hashed payloads."""

    shards: dict[str, dict[str, Any]] = {}

    def shard(shard_id: str) -> dict[str, Any]:
        return shards.setdefault(
            shard_id,
            {"shard_id": shard_id, "numbers": [], "prefixes": []},
        )

    for number in database.get("numbers", []):
        value = str(number.get("number", "")).strip()
        if value:
            shard(shard_id_for(value))["numbers"].append(number)

    for prefix in database.get("prefixes", []):
        value = str(prefix.get("prefix", "")).strip()
        if value:
            shard(shard_id_for(value))["prefixes"].append(prefix)

    for payload in shards.values():
        payload["numbers"].sort(key=lambda row: row.get("number", ""))
        payload["prefixes"].sort(key=lambda row: row.get("prefix", ""))
    return dict(sorted(shards.items()))


def build_manifest(
    database: dict[str, Any],
    payloads: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    descriptors = []
    for shard_id, payload in payloads.items():
        encoded = _json_bytes(payload)
        descriptors.append(
            {
                "id": shard_id,
                "path": f"{SHARD_PATH_PREFIX}{shard_id}.json",
                "sha256": hashlib.sha256(encoded).hexdigest(),
                "bytes": len(encoded),
                "numbers": len(payload["numbers"]),
                "prefixes": len(payload["prefixes"]),
            }
        )
    return {
        "format_version": 1,
        "version": int(database.get("version", 0)),
        "updated": str(database.get("updated", "")),
        "legacy_path": "data/spam_numbers.json",
        "shard_directory": f"data/{SHARD_DIRECTORY_NAME}",
        "shard_count": SHARD_COUNT,
        "shards": descriptors,
    }


def write_sharded_database(
    database: dict[str, Any],
    data_dir: Path,
) -> dict[str, Any]:
    """Atomically write shard files and then publish their manifest.

    The manifest is written after all shard payloads. If generation is
    interrupted, the app validates every requested hash before touching Room,
    so an old or mixed shard set cannot be applied as a partial update.
    """

    data_dir = Path(data_dir)
    shard_dir = data_dir / SHARD_DIRECTORY_NAME
    shard_dir.mkdir(parents=True, exist_ok=True)
    payloads = build_shard_payloads(database)

    active_names: set[str] = set()
    for shard_id, payload in payloads.items():
        filename = f"{shard_id}.json"
        active_names.add(filename)
        _write_json_if_changed(shard_dir / filename, payload)

    manifest = build_manifest(database, payloads)
    _write_json_if_changed(data_dir / MANIFEST_FILENAME, manifest)

    # Empty shards are omitted from the manifest. Stale files are harmless if
    # a process dies here, but remove only the explicit two-hex shard names on
    # the next successful generation so the published tree stays tidy.
    for stale in shard_dir.glob("*.json"):
        if stale.name not in active_names and len(stale.stem) == 2:
            stale.unlink()
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description="Build content-addressed spam database shards")
    parser.add_argument(
        "--input",
        type=Path,
        default=Path(__file__).parent.parent / "data" / "spam_numbers.json",
        help="legacy monolithic database JSON",
    )
    parser.add_argument(
        "--data-dir",
        type=Path,
        default=Path(__file__).parent.parent / "data",
        help="directory receiving the manifest and shard set",
    )
    args = parser.parse_args()
    with args.input.open(encoding="utf-8") as database_file:
        database = json.load(database_file)
    manifest = write_sharded_database(database, args.data_dir)
    total_bytes = sum(descriptor["bytes"] for descriptor in manifest["shards"])
    print(
        f"Wrote {len(manifest['shards'])} shards ({total_bytes / 1024:.1f} KB) "
        f"for {sum(item['numbers'] for item in manifest['shards']):,} numbers and "
        f"{sum(item['prefixes'] for item in manifest['shards']):,} prefixes."
    )


if __name__ == "__main__":
    main()
