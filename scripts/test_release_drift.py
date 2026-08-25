#!/usr/bin/env python3
"""Tests for the repository release-drift gate."""

from __future__ import annotations

import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

import verify_release_drift


ROOT = Path(__file__).resolve().parents[1]


class ReleaseDriftTest(unittest.TestCase):
    def test_checkout_is_synchronized(self) -> None:
        snapshot = json.loads((ROOT / "data/source-snapshot.json").read_text(encoding="utf-8"))
        snapshot_time = verify_release_drift.parse_iso_timestamp(snapshot["generated_at"])
        report = verify_release_drift.audit(
            ROOT,
            # Keep the checkout assertion deterministic relative to the generated
            # fixture. A wall-clock value from an earlier release made a healthy
            # snapshot appear to be in the future after the pipeline refreshed it.
            now=snapshot_time + timedelta(minutes=1),
        )
        self.assertEqual([], report["issues"], report)
        self.assertEqual("1.7.37", report["version_name"])
        self.assertEqual(65, report["version_code"])
        self.assertEqual(9, report["sources"]["source_count"])
        self.assertEqual(3, len(report["advisories"]))

    def test_source_snapshot_drift_is_actionable(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = {
                "version": 1,
                "sources": [
                    {
                        "id": "fixture",
                        "access_mode": "public_feed",
                        "geography": "US",
                        "license": "MIT",
                        "attribution": "Fixture source",
                        "cadence": "daily",
                        "parser_version": "fixture-v1",
                        "evidence_type": "curated",
                        "confidence_tier": "curated",
                        "redistributable": True,
                        "stale_after_days": 7,
                    }
                ],
            }
            snapshot = {
                "schema_version": 1,
                "generated_at": "2026-08-10T12:00:00+00:00",
                "sources": [
                    {
                        **manifest["sources"][0],
                        "parser_version": "fixture-v0",
                        "status": "not_requested",
                        "accepted": 0,
                        "rejected": 0,
                    }
                ],
            }
            (root / "data").mkdir()
            (root / "data/source-manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            (root / "data/source-snapshot.json").write_text(json.dumps(snapshot), encoding="utf-8")
            report, issues = verify_release_drift.source_audit(
                root,
                datetime(2026, 8, 10, 12, 0, tzinfo=timezone.utc),
            )
            self.assertEqual(1, report["source_count"])
            self.assertIn("Source snapshot drift for fixture.parser_version.", issues)

    def test_old_snapshot_fails_the_release_window(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = {
                "version": 1,
                "sources": [
                    {
                        "id": "fixture",
                        "access_mode": "public_feed",
                        "geography": "US",
                        "license": "MIT",
                        "attribution": "Fixture source",
                        "cadence": "daily",
                        "parser_version": "fixture-v1",
                        "evidence_type": "curated",
                        "confidence_tier": "curated",
                        "redistributable": True,
                        "stale_after_days": 7,
                    }
                ],
            }
            snapshot = {
                "schema_version": 1,
                "generated_at": "2026-06-01T12:00:00+00:00",
                "sources": [
                    {
                        **manifest["sources"][0],
                        "status": "not_requested",
                        "accepted": 0,
                        "rejected": 0,
                    }
                ],
            }
            (root / "data").mkdir()
            (root / "data/source-manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            (root / "data/source-snapshot.json").write_text(json.dumps(snapshot), encoding="utf-8")
            _, issues = verify_release_drift.source_audit(
                root,
                datetime(2026, 8, 10, 12, 0, tzinfo=timezone.utc),
            )
            self.assertIn("Source snapshot is older than the 30-day release-review window.", issues)

    def test_advisory_manifest_requires_explicit_disposition(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scripts").mkdir()
            (root / "gradle").mkdir()
            advisory_path = root / "scripts/release_advisories.json"
            advisory_path.write_text(
                json.dumps(
                    {
                        "schema_version": 1,
                        "advisories": [
                            {
                                "id": "GHSA-fixture",
                                "component": "fixture",
                                "dependency_key": "kotlin",
                                "fixed_in": "2.4.20",
                                "disposition": "unreviewed",
                                "evidence_files": ["gradle.properties"],
                                "source": "https://example.invalid/GHSA-fixture",
                                "rationale": "fixture",
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )
            (root / "gradle.properties").write_text("fixture", encoding="utf-8")
            report, issues = verify_release_drift.advisory_audit(root, {"versions": {"kotlin": "2.3.21"}})
            self.assertEqual("GHSA-fixture", report[0]["id"])
            self.assertIn("Advisory GHSA-fixture has unsupported disposition unreviewed.", issues)


if __name__ == "__main__":
    unittest.main()
