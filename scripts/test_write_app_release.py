#!/usr/bin/env python3
"""The release notice the release step writes for phones to read."""

from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

import feed_signing
import write_app_release


class WriteAppReleaseTest(unittest.TestCase):
    def test_versions_come_from_the_gradle_file(self):
        text = 'android {\n    defaultConfig {\n        versionCode = 71\n        versionName = "1.11.0"\n    }\n}\n'
        self.assertEqual((71, "1.11.0"), write_app_release.gradle_version(text))

    def test_the_real_gradle_file_parses(self):
        code, name = write_app_release.gradle_version(write_app_release.GRADLE_FILE.read_text(encoding="utf-8"))
        self.assertGreater(code, 0)
        self.assertRegex(name, r"^\d+\.\d+\.\d+$")

    def test_a_gradle_file_without_versions_is_refused(self):
        with self.assertRaises(ValueError):
            write_app_release.gradle_version("android {}\n")

    def test_the_notice_names_the_tag_page_and_the_apk_hash(self):
        sha = "ab" * 32
        notice = write_app_release.release_notice(71, "1.11.0", sha)
        self.assertEqual(
            {
                "version_code": 71,
                "version_name": "1.11.0",
                "release_url": "https://github.com/SysAdminDoc/CallShield/releases/tag/v1.11.0",
                "apk_sha256": sha,
            },
            notice,
        )

    def test_a_bad_hash_or_version_code_is_refused(self):
        with self.assertRaises(ValueError):
            write_app_release.release_notice(71, "1.11.0", "AB" * 32)
        with self.assertRaises(ValueError):
            write_app_release.release_notice(0, "1.11.0", "ab" * 32)

    def test_the_apk_is_hashed_into_the_written_file(self):
        with tempfile.TemporaryDirectory() as directory:
            data = Path(directory)
            apk = data / "app-release.apk"
            apk.write_bytes(b"not really an apk")
            self.assertEqual(0, write_app_release.main(["--apk", str(apk), "--data-dir", str(data), "--no-sign"]))
            written = json.loads((data / write_app_release.FILE_NAME).read_text(encoding="utf-8"))
            self.assertEqual(hashlib.sha256(b"not really an apk").hexdigest(), written["apk_sha256"])
            self.assertEqual(1, write_app_release.main(["--apk", str(data / "missing.apk"), "--data-dir", str(data), "--no-sign"]))

    def test_the_notice_is_a_signed_feed(self):
        self.assertIn(write_app_release.FILE_NAME, feed_signing.SIGNED_FEEDS)

    def test_the_published_notice_matches_the_shape_the_app_accepts(self):
        published = json.loads((write_app_release.DATA_DIR / write_app_release.FILE_NAME).read_text(encoding="utf-8"))
        rebuilt = write_app_release.release_notice(published["version_code"], published["version_name"], published["apk_sha256"])
        self.assertEqual(rebuilt, published)


if __name__ == "__main__":
    unittest.main()
