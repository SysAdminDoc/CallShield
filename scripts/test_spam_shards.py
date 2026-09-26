#!/usr/bin/env python3
"""Tests for deterministic, incremental spam database shards."""

from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

import spam_shards
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

    def test_plain_text_export_lists_redistributable_numbers_only(self) -> None:
        database = {
            "version": 49,
            "updated": "2026-09-26",
            "numbers": [
                {"number": "+12125550199", "evidence": [{"source_id": "fcc_complaints"}]},
                {"number": "+12125550101", "evidence": [{"source_id": "community_reports"}]},
                {"number": "+12125550150", "evidence": [{"source_id": "phoneblock_bulk"}]},
                {"number": "+12125550101", "evidence": [{"source_id": "ftc_complaints"}]},
            ],
            "prefixes": [{"prefix": "+33162", "evidence": [{"source_id": "saracroche_prefixes"}]}],
        }
        manifest = {"sources": [{"id": "phoneblock_bulk", "redistributable": False}]}

        text = spam_shards.plain_text_export(database, manifest)

        lines = text.splitlines()
        self.assertTrue(lines[0].startswith("# CallShield spam numbers, database version 49"))
        self.assertEqual(["+12125550101", "+12125550199"], [line for line in lines if not line.startswith("#")])
        self.assertTrue(text.endswith("\n"))

    def test_the_published_plain_text_export_matches_the_database(self) -> None:
        database = json.loads((ROOT / "data" / "spam_numbers.json").read_text(encoding="utf-8"))
        manifest = json.loads((ROOT / "data" / "source-manifest.json").read_text(encoding="utf-8"))
        published = (ROOT / "data" / spam_shards.PLAIN_TEXT_FILENAME).read_text(encoding="utf-8")

        self.assertEqual(spam_shards.plain_text_export(database, manifest), published)

    def test_shard_key_is_stable_and_distinguishes_values(self) -> None:
        self.assertEqual(shard_id_for("+12125550101"), shard_id_for("+12125550101"))
        self.assertNotEqual(shard_id_for("+12125550101"), shard_id_for("+12125550102"))
        self.assertEqual(len(shard_id_for("+12125550101")), 2)
        self.assertTrue(all(len(shard_id) == 2 for shard_id in build_shard_payloads(self.database)))


if __name__ == "__main__":
    unittest.main()
