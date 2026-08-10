#!/usr/bin/env python3
"""Tests for deterministic, incremental spam database shards."""

from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from spam_shards import (
    MANIFEST_FILENAME,
    SHARD_DIRECTORY_NAME,
    build_shard_payloads,
    shard_id_for,
    write_sharded_database,
)

ROOT = Path(__file__).parent.parent
DATABASE = ROOT / "data" / "spam_numbers.json"


class SpamShardTests(unittest.TestCase):
    def setUp(self) -> None:
        with DATABASE.open(encoding="utf-8") as database_file:
            self.database = json.load(database_file)

    def test_real_database_manifest_hashes_every_payload(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            data_dir = Path(temporary_directory)
            manifest = write_sharded_database(self.database, data_dir)
            self.assertGreater(len(manifest["shards"]), 1)
            self.assertEqual(manifest["shard_count"], 256)

            for descriptor in manifest["shards"]:
                payload_path = data_dir / SHARD_DIRECTORY_NAME / f"{descriptor['id']}.json"
                payload = payload_path.read_bytes()
                self.assertEqual(descriptor["bytes"], len(payload))
                self.assertEqual(descriptor["sha256"], hashlib.sha256(payload).hexdigest())

            self.assertTrue((data_dir / MANIFEST_FILENAME).exists())

    def test_single_row_change_stays_under_one_percent_of_shard_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            data_dir = Path(temporary_directory)
            before = write_sharded_database(self.database, data_dir)
            before_hashes = {descriptor["id"]: descriptor["sha256"] for descriptor in before["shards"]}
            total_bytes = sum(descriptor["bytes"] for descriptor in before["shards"])

            changed = json.loads(json.dumps(self.database))
            changed["numbers"][0]["description"] += "; shard regression fixture"
            after = write_sharded_database(changed, data_dir)
            changed_descriptors = [
                descriptor
                for descriptor in after["shards"]
                if before_hashes.get(descriptor["id"]) != descriptor["sha256"]
            ]
            changed_bytes = sum(descriptor["bytes"] for descriptor in changed_descriptors)

            self.assertGreater(changed_bytes, 0)
            self.assertLess(changed_bytes / total_bytes, 0.01)

    def test_shard_key_is_stable_and_distinguishes_values(self) -> None:
        self.assertEqual(shard_id_for("+12125550101"), shard_id_for("+12125550101"))
        self.assertNotEqual(shard_id_for("+12125550101"), shard_id_for("+12125550102"))
        self.assertEqual(len(shard_id_for("+12125550101")), 2)
        self.assertTrue(all(len(shard_id) == 2 for shard_id in build_shard_payloads(self.database)))


if __name__ == "__main__":
    unittest.main()
