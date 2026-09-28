"""Unit tests for source provenance and freshness snapshots."""

import json
import tempfile
import unittest
from datetime import datetime
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

    def test_health_report_is_aggregate_and_tracks_freshness_corroboration_and_reviews(self):
        snapshot = {
            "generated_at": "2026-08-10T12:00:00+00:00",
            "sources": [
                {
                    "id": "public",
                    "status": "ok",
                    "accepted": 12,
                    "rejected": 1,
                    "last_success_at": "2026-08-10T00:00:00+00:00",
                    "stale_after_days": 7,
                },
                {
                    "id": "old",
                    "status": "ok",
                    "accepted": 4,
                    "rejected": 0,
                    "last_success_at": "2026-07-01T00:00:00+00:00",
                    "stale_after_days": 7,
                },
            ],
        }
        database = {
            "numbers": [
                {
                    "number": "+15551234567",
                    "evidence": [{"source_id": "public"}, {"source_id": "old"}],
                },
                {"number": "+15557654321", "sources": ["public"]},
            ]
        }
        review = {
            "candidates": [
                {
                    "number": "+15557654321",
                    "source_ids": ["public"],
                    "not_spam_votes": 3,
                    "spam_reports": 2,
                }
            ]
        }

        report = source_registry.source_health_report(
            snapshot,
            database,
            review,
            quarantined_count=2,
            quarantined_this_run=1,
            generated_at="2026-08-10T12:00:00+00:00",
        )
        by_id = {source["id"]: source for source in report["sources"]}

        self.assertEqual(by_id["public"]["freshness"], "fresh")
        self.assertEqual(by_id["old"]["freshness"], "stale")
        self.assertEqual(by_id["public"]["corroborated_rows"], 1)
        self.assertEqual(by_id["public"]["false_positive_candidates"], 1)
        self.assertEqual(by_id["public"]["false_positive_rate"], 0.6)
        self.assertEqual(by_id["community_reports"]["quarantined"], 2)
        self.assertEqual(report["summary"]["quarantined_this_run"], 1)
        self.assertFalse(any(report["privacy"].values()))
        serialized = json.dumps(report)
        self.assertNotIn("+15551234567", serialized)
        self.assertNotIn("+15557654321", serialized)

    def test_invalid_manifest_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            path.write_text(json.dumps({"version": 1, "sources": [{"id": "broken"}]}))
            with self.assertRaises(ValueError):
                source_registry.load_source_manifest(path)

    @staticmethod
    def _source(source_id: str, stale_after_days: int, ttl: int | None = None) -> dict:
        source = {
            "id": source_id,
            "access_mode": "public_api",
            "geography": "US",
            "license": "public",
            "attribution": "Example",
            "cadence": "daily",
            "parser_version": "v1",
            "evidence_type": "complaint",
            "confidence_tier": "unverified",
            "redistributable": True,
            "stale_after_days": stale_after_days,
        }
        if ttl is not None:
            source["evidence_ttl_days"] = ttl
        return source

    @staticmethod
    def _epoch_ms(timestamp: str) -> int:
        return int(datetime.fromisoformat(timestamp).timestamp() * 1000)

    def test_evidence_lives_for_its_ttl_not_the_import_freshness_limit(self):
        # 2026-09-28: evidence expired at stale_after_days, so every row stopped
        # blocking 14 to 30 days after the import that stamped it.
        manifest = {"version": 1, "sources": [self._source("fcc", 14, ttl=365), self._source("legacy", 7)]}
        fcc = source_registry.source_evidence(manifest, "fcc", {}, retrieved_at="2026-09-26T00:00:00+00:00")
        legacy = source_registry.source_evidence(manifest, "legacy", {}, retrieved_at="2026-09-26T00:00:00+00:00")
        self.assertEqual(fcc["expires_at_epoch_ms"], self._epoch_ms("2027-09-26T00:00:00+00:00"))
        # A source without its own lifetime keeps the old one.
        self.assertEqual(legacy["expires_at_epoch_ms"], self._epoch_ms("2026-10-03T00:00:00+00:00"))

    def test_manifest_rejects_a_ttl_shorter_than_the_freshness_limit(self):
        for ttl in (13, 0, "365", True):
            with self.subTest(ttl=ttl), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "manifest.json"
                path.write_text(json.dumps({"version": 1, "sources": [self._source("fcc", 14, ttl=ttl)]}))
                with self.assertRaises(ValueError):
                    source_registry.load_source_manifest(path)

    def test_real_manifest_keeps_number_evidence_for_at_least_a_year(self):
        manifest = source_registry.load_source_manifest(Path(__file__).parent.parent / "data" / "source-manifest.json")
        ttl = {source["id"]: source_registry.evidence_ttl_days(source) for source in manifest["sources"]}
        for source_id in ("ftc_complaints", "fcc_complaints", "community_reports", "github_database"):
            self.assertGreaterEqual(ttl[source_id], 365, source_id)
        self.assertGreaterEqual(ttl["saracroche_prefixes"], 90)

    def test_refresh_recomputes_expiry_from_retrieval_time_once(self):
        manifest = {"version": 1, "sources": [self._source("fcc", 14, ttl=365)]}
        rows = [
            {
                "number": "+18056377456",
                "evidence": [
                    {"source_id": "fcc", "retrieved_at": "2026-09-26T16:53:57+00:00", "expires_at_epoch_ms": 1791651237000},
                    {"source_id": "unknown", "retrieved_at": "2026-09-26T00:00:00+00:00", "expires_at_epoch_ms": 1},
                    {"source_id": "fcc", "retrieved_at": "not a time", "expires_at_epoch_ms": 2},
                ],
            },
            {"number": "+15125550100"},
        ]
        self.assertEqual(source_registry.refresh_evidence_expiry(rows, manifest), 1)
        evidence = rows[0]["evidence"]
        self.assertEqual(evidence[0]["expires_at_epoch_ms"], self._epoch_ms("2027-09-26T16:53:57+00:00"))
        # Sources the manifest doesn't know and unreadable times are left alone.
        self.assertEqual(evidence[1]["expires_at_epoch_ms"], 1)
        self.assertEqual(evidence[2]["expires_at_epoch_ms"], 2)
        self.assertEqual(source_registry.refresh_evidence_expiry(rows, manifest), 0)


if __name__ == "__main__":
    unittest.main()
