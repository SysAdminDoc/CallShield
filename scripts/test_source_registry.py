"""Unit tests for source provenance and freshness snapshots."""

import json
import tempfile
import unittest
from pathlib import Path

import source_registry


class SourceRegistryTest(unittest.TestCase):
    def test_manifest_contains_required_public_and_restricted_sources(self):
        manifest = source_registry.load_source_manifest(
            Path(__file__).parent.parent / "data" / "source-manifest.json"
        )
        ids = {entry["id"] for entry in manifest["sources"]}
        self.assertIn("ftc_complaints", ids)
        self.assertIn("phoneblock_bulk", ids)
        self.assertTrue(
            next(entry for entry in manifest["sources"] if entry["id"] == "ftc_complaints")[
                "redistributable"
            ]
        )
        self.assertFalse(
            next(entry for entry in manifest["sources"] if entry["id"] == "phoneblock_bulk")[
                "redistributable"
            ]
        )

    def test_snapshot_marks_unrequested_sources_and_preserves_stats(self):
        manifest = {
            "version": 1,
            "sources": [
                {
                    "id": "public",
                    "access_mode": "public_api",
                    "geography": "US",
                    "license": "public",
                    "attribution": "Example",
                    "cadence": "daily",
                    "parser_version": "v1",
                    "evidence_type": "complaint",
                    "confidence_tier": "unverified",
                    "redistributable": True,
                    "stale_after_days": 7,
                },
                {
                    "id": "restricted",
                    "access_mode": "operator_key",
                    "geography": "global",
                    "license": "restricted",
                    "attribution": "Example",
                    "cadence": "on demand",
                    "parser_version": "v1",
                    "evidence_type": "reputation",
                    "confidence_tier": "corroborated",
                    "redistributable": False,
                    "stale_after_days": 30,
                },
            ],
        }
        snapshot = source_registry.source_snapshot(
            manifest,
            {"public": {"status": "ok", "accepted": 12, "rejected": 2}},
            fetched_at="2026-08-02T12:00:00+00:00",
        )
        self.assertEqual(snapshot["generated_at"], "2026-08-02T12:00:00+00:00")
        self.assertEqual(snapshot["sources"][0]["accepted"], 12)
        self.assertIsNone(snapshot["sources"][0]["checksum"])
        self.assertEqual(snapshot["sources"][1]["status"], "not_requested")
        self.assertIsNone(snapshot["sources"][1]["fetched_at"])

    def test_source_evidence_carries_expiry_and_manifest_metadata(self):
        manifest = {
            "version": 1,
            "sources": [
                {
                    "id": "public",
                    "access_mode": "public_api",
                    "geography": "US",
                    "license": "public",
                    "attribution": "Example",
                    "cadence": "daily",
                    "parser_version": "v1",
                    "evidence_type": "complaint",
                    "confidence_tier": "unverified",
                    "redistributable": True,
                    "stale_after_days": 7,
                }
            ],
        }
        evidence = source_registry.source_evidence(
            manifest,
            "public",
            {"first_seen": "2026-08-01", "last_seen": "2026-08-02"},
            retrieved_at="2026-08-02T12:00:00+00:00",
        )
        self.assertEqual(evidence["source_id"], "public")
        self.assertEqual(evidence["evidence_type"], "complaint")
        self.assertGreater(evidence["expires_at_epoch_ms"], 0)

    def test_merge_evidence_replaces_a_source_on_rerun_and_keeps_independent_sources(self):
        first = {"source_id": "fcc", "retrieved_at": "old"}
        refreshed = {"source_id": "fcc", "retrieved_at": "new"}
        independent = {"source_id": "ftc", "retrieved_at": "new"}
        merged = source_registry.merge_evidence([first], [refreshed, independent])
        self.assertEqual(merged, [refreshed, independent])

    def test_invalid_manifest_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            path.write_text(json.dumps({"version": 1, "sources": [{"id": "broken"}]}))
            with self.assertRaises(ValueError):
                source_registry.load_source_manifest(path)


if __name__ == "__main__":
    unittest.main()
