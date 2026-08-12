#!/usr/bin/env python3
"""Unit tests for release SBOM/provenance binding and tamper detection."""

import tempfile
import unittest
from pathlib import Path

from release_sbom import generate_bundle, verify_bundle


class ReleaseSbomTest(unittest.TestCase):
    def test_generated_bundle_matches_artifact_and_runtime_lock(self):
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "app-release.apk"
            artifact.write_bytes(b"test release artifact")
            output_dir = Path(directory) / "outputs"
            paths = generate_bundle(artifact, output_dir)

            self.assertTrue(all(path.is_file() for path in paths))
            verify_bundle(artifact, output_dir)

    def test_artifact_tampering_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "app-release.apk"
            artifact.write_bytes(b"test release artifact")
            output_dir = Path(directory) / "outputs"
            generate_bundle(artifact, output_dir)
            artifact.write_bytes(b"tampered release artifact")

            with self.assertRaisesRegex(ValueError, "SBOM metadata|Provenance subject|sidecar"):
                verify_bundle(artifact, output_dir)


if __name__ == "__main__":
    unittest.main()
