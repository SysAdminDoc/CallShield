#!/usr/bin/env python3
"""Tests for feed_signing.py: signatures over the exact published bytes, and the app's key list."""

from __future__ import annotations

import re
import tempfile
import unittest
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric import ec

import feed_signing

GITHUB_DATA_SOURCE = feed_signing.KOTLIN_KEYS.with_name("GitHubDataSource.kt")


def kotlin_key_block(*keys: ec.EllipticCurvePublicKey) -> str:
    lines = "\n".join(f'            "{feed_signing.public_key_base64(key)}",' for key in keys)
    return f"internal val TRUSTED_KEYS =\n        listOf(\n{lines}\n        )"


class SigningTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.data = Path(self.directory.name)
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.trusted = feed_signing.trusted_public_keys(kotlin_key_block(self.key.public_key()))
        (self.data / "hot_numbers.json").write_bytes(b'{\r\n  "count": 0,\r\n  "numbers": []\r\n}')
        (self.data / "spam_model_weights.json").write_bytes(b'{"version": 3}\n')

    def tearDown(self) -> None:
        self.directory.cleanup()

    def test_signed_feeds_verify_and_a_flipped_byte_does_not(self):
        written = feed_signing.sign_feeds(self.data, self.key, self.trusted)

        self.assertEqual(["hot_numbers.json", "spam_model_weights.json"], written)
        self.assertEqual([], feed_signing.verify_feeds(self.data, self.trusted))

        feed = self.data / "hot_numbers.json"
        tampered = bytearray(feed.read_bytes())
        tampered[5] ^= 0x01
        feed.write_bytes(bytes(tampered))
        problems = feed_signing.verify_feeds(self.data, self.trusted)
        self.assertEqual(1, len(problems))
        self.assertIn("doesn't verify hot_numbers.json", problems[0])

    def test_line_endings_are_part_of_what_is_signed(self):
        # The app verifies the bytes it downloads, so rewriting CRLF as LF has to fail.
        feed_signing.sign_feeds(self.data, self.key, self.trusted)
        feed = self.data / "hot_numbers.json"
        feed.write_bytes(feed.read_bytes().replace(b"\r\n", b"\n"))

        self.assertEqual(1, len(feed_signing.verify_feeds(self.data, self.trusted)))

    def test_a_missing_signature_is_reported(self):
        problems = feed_signing.verify_feeds(self.data, self.trusted)

        self.assertEqual(2, len(problems))
        self.assertTrue(all("run scripts/feed_signing.py sign" in problem for problem in problems))

    def test_signing_again_leaves_valid_signatures_alone(self):
        # ECDSA signatures are randomized: re-signing would churn every .sig.
        feed_signing.sign_feeds(self.data, self.key, self.trusted)
        before = (self.data / "hot_numbers.json.sig").read_bytes()

        self.assertEqual([], feed_signing.sign_feeds(self.data, self.key, self.trusted))
        self.assertEqual(before, (self.data / "hot_numbers.json.sig").read_bytes())

    def test_a_key_the_app_does_not_trust_is_refused(self):
        stranger = ec.generate_private_key(ec.SECP256R1())

        with self.assertRaises(ValueError):
            feed_signing.sign_feeds(self.data, stranger, self.trusted)
        self.assertFalse((self.data / "hot_numbers.json.sig").exists())

    def test_a_signature_from_another_key_does_not_verify(self):
        stranger = ec.generate_private_key(ec.SECP256R1())
        feed = self.data / "hot_numbers.json"
        feed_signing.signature_path(feed).write_text(feed_signing.sign_bytes(feed.read_bytes(), stranger), encoding="ascii")

        self.assertFalse(feed_signing.verifies(feed.read_bytes(), feed_signing.signature_path(feed).read_text(), self.trusted))
        self.assertFalse(feed_signing.verifies(feed.read_bytes(), "not base64!", self.trusted))


class AppContractTest(unittest.TestCase):
    def test_the_app_trusts_two_keys(self):
        # A primary and a backup, so losing one key doesn't strand every device.
        self.assertEqual(2, len(feed_signing.trusted_public_keys()))

    def test_python_and_the_app_sign_the_same_files(self):
        source = GITHUB_DATA_SOURCE.read_text(encoding="utf-8")
        block = re.search(r"SIGNED_FEED_PATHS\s*=\s*setOf\((.*?)\)", source, re.DOTALL)
        self.assertIsNotNone(block, "GitHubDataSource.SIGNED_FEED_PATHS not found")
        constants = dict(re.findall(r'const val (\w+_PATH) = "data/([^"]+)"', source))
        app_files = {constants[name] for name in re.findall(r"(\w+_PATH)", block.group(1))}
        self.assertEqual(set(feed_signing.SIGNED_FEEDS), app_files)

    def test_the_published_feeds_verify_under_the_app_keys(self):
        # The positive control for the whole scheme: what devices download today verifies.
        self.assertEqual([], feed_signing.verify_feeds(feed_signing.ROOT / "data", feed_signing.trusted_public_keys()))


if __name__ == "__main__":
    unittest.main()
