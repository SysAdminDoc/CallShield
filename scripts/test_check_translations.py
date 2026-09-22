#!/usr/bin/env python3
"""Security regression tests for the community translation validator."""

import contextlib
import io
import json
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest import mock

import check_translations


class TranslationCheckerSecurityTest(unittest.TestCase):
    def test_format_scanner_preserves_supported_printf_semantics(self):
        self.assertEqual(["1:d", "2:s"], check_translations.specifiers("%2$08s / %1$d / %%"))
        self.assertEqual(["1:s", "2:f"], check_translations.specifiers("%s %.2f"))

    def test_long_unterminated_format_token_is_handled_linearly(self):
        self.assertEqual([], check_translations.specifiers("%" + ("0" * 200_000)))

    def test_dtd_and_internal_entities_are_rejected_before_parsing(self):
        document = b"""<?xml version="1.0"?>
<!DOCTYPE resources [<!ENTITY repeated "expanded">]>
<resources><string name="unsafe">&repeated;</string></resources>
"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "strings.xml"
            path.write_bytes(document)

            with self.assertRaisesRegex(ET.ParseError, "DTD and entity declarations"):
                check_translations.parse_resource_root(path)

    def test_utf16_dtd_is_also_rejected_before_parsing(self):
        document = """<?xml version="1.0" encoding="utf-16"?>
<!DOCTYPE resources [<!ENTITY repeated "expanded">]>
<resources><string name="unsafe">&repeated;</string></resources>
"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "strings.xml"
            path.write_bytes(document.encode("utf-16"))

            with self.assertRaisesRegex(ET.ParseError, "DTD and entity declarations"):
                check_translations.parse_resource_root(path)

    def test_oversized_resource_is_rejected_before_parsing(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "strings.xml"
            path.write_bytes(b" " * (check_translations.MAX_RESOURCE_XML_BYTES + 1))

            with self.assertRaisesRegex(ET.ParseError, "resource XML exceeds"):
                check_translations.parse_resource_root(path)


class TranslationCoverageFloorTest(unittest.TestCase):
    """A shipped locale must not silently decay as English strings are added."""

    def test_locale_at_or_above_its_floor_is_clean(self):
        self.assertEqual([], check_translations.floor_report({"values-zh-rCN": 76.1}, {"values-zh-rCN": 76.1}))
        self.assertEqual([], check_translations.floor_report({"values-zh-rCN": 91.4}, {"values-zh-rCN": 76.1}))

    def test_coverage_below_the_floor_is_an_error(self):
        report = check_translations.floor_report(
            {"values-zh-rCN": 71.0}, {"values-zh-rCN": 76.1}
        )
        self.assertEqual(1, len(report), report)
        message, is_error = report[0]
        self.assertTrue(is_error)
        self.assertIn("71.0%", message)
        self.assertIn("76.1%", message)

    def test_rounding_noise_does_not_fail_the_build(self):
        # Floors are stored to one decimal place, so a locale sitting exactly
        # on its floor must not fail on float representation alone.
        self.assertEqual(
            [],
            check_translations.floor_report({"values-zh-rCN": 76.06}, {"values-zh-rCN": 76.1}),
        )
        self.assertEqual(
            1,
            len(check_translations.floor_report({"values-zh-rCN": 76.0}, {"values-zh-rCN": 76.1})),
        )

    def test_locale_without_a_floor_warns_but_does_not_fail(self):
        report = check_translations.floor_report({"values-de": 40.0}, {})
        self.assertEqual(1, len(report), report)
        message, is_error = report[0]
        self.assertFalse(is_error)
        self.assertIn("--update-floors", message)

    def test_floors_round_trip_and_a_broken_file_fails_closed(self):
        # A missing file is "no floors yet". A file that is there but unreadable
        # used to load as empty too, which switched the gate off for a
        # merge-conflicted file. It is an error now.
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "translation_floors.json"
            original = check_translations.FLOORS_FILE
            check_translations.FLOORS_FILE = path
            try:
                self.assertEqual({}, check_translations.load_floors())
                check_translations.write_floors({"values-zh-rCN": 76.14, "values-de": 40.0})
                self.assertEqual(
                    {"values-de": 40.0, "values-zh-rCN": 76.1},
                    check_translations.load_floors(),
                )
                for broken in (
                    "not json",
                    "[]",
                    '{"description": "no floors object"}',
                    '{"floors": {"values-de": "60.0"}}',
                    '{"floors": {"values-de": true}}',
                    '{"floors": {"values-de": -1}}',
                    '{"floors": {"values-de": 101}}',
                    '{"floors": {"de": 50.0}}',
                    '{"floors": {"zh-rCN": 50.0}}',
                ):
                    path.write_text(broken, encoding="utf-8")
                    with self.assertRaises(check_translations.FloorsFileError, msg=broken):
                        check_translations.load_floors()
            finally:
                check_translations.FLOORS_FILE = original

    def test_update_floors_leaves_an_unreadable_file_alone(self):
        conflicted = (
            "{\n<<<<<<< HEAD\n"
            '  "floors": {"values-zh-rCN": 76.5}\n'
            "=======\n"
            '  "floors": {"values-zh-rCN": 76.1}\n'
            ">>>>>>> branch\n}\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "translation_floors.json"
            path.write_text(conflicted, encoding="utf-8")
            original = check_translations.FLOORS_FILE
            check_translations.FLOORS_FILE = path
            try:
                with self.assertRaises(check_translations.FloorsFileError):
                    check_translations.write_floors({"values-zh-rCN": 70.0})
                self.assertEqual(conflicted, path.read_text(encoding="utf-8"))
            finally:
                check_translations.FLOORS_FILE = original

    def test_update_floors_only_ever_raises_a_floor(self):
        # Recording coverage after adding untranslated strings lowered the
        # zh-rCN floor from 76.1 to 75.6 in one step before this was fixed.
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "translation_floors.json"
            original = check_translations.FLOORS_FILE
            check_translations.FLOORS_FILE = path
            try:
                check_translations.write_floors({"values-zh-rCN": 76.1, "values-de": 40.0})
                check_translations.write_floors({"values-zh-rCN": 75.6})
                self.assertEqual({"values-de": 40.0, "values-zh-rCN": 76.1}, check_translations.load_floors())
                check_translations.write_floors({"values-zh-rCN": 76.54})
                self.assertEqual({"values-de": 40.0, "values-zh-rCN": 76.5}, check_translations.load_floors())
            finally:
                check_translations.FLOORS_FILE = original

    def test_recorded_floor_matches_the_shipped_locale(self):
        # The committed floor must describe reality, or the gate is decorative.
        floors = check_translations.load_floors()
        self.assertIn("values-zh-rCN", floors, floors)


class TranslationCheckerMainTest(unittest.TestCase):
    """Drives main() over a throwaway resource tree with one German locale."""

    NAMES = ("a", "b", "c", "d")

    def make_tree(self, directory: str, translated: int) -> Path:
        res = Path(directory) / "res"
        (res / "values").mkdir(parents=True)
        (res / "values-de").mkdir()
        english = "".join(f'<string name="{name}">{name}</string>' for name in self.NAMES)
        german = "".join(f'<string name="{name}">{name}-de</string>' for name in self.NAMES[:translated])
        (res / "values" / "strings.xml").write_text(f"<resources>{english}</resources>", encoding="utf-8")
        (res / "values-de" / "strings.xml").write_text(f"<resources>{german}</resources>", encoding="utf-8")
        return res

    def run_checker(self, res: Path, floors_file: Path, *argv: str) -> int:
        with (
            mock.patch.object(check_translations, "RES_DIR", res),
            mock.patch.object(check_translations, "BASE_DIR", res / "values"),
            mock.patch.object(check_translations, "FLOORS_FILE", floors_file),
            mock.patch.object(sys, "argv", ["check_translations.py", *argv]),
            contextlib.redirect_stdout(io.StringIO()),
            contextlib.redirect_stderr(io.StringIO()),
        ):
            return check_translations.main()

    @staticmethod
    def recorded(floors_file: Path) -> dict:
        return json.loads(floors_file.read_text(encoding="utf-8"))["floors"]

    def test_update_floors_still_fails_when_a_locale_is_below_its_floor(self):
        # It used to skip the gate and report success at 25% against a 50% floor.
        with tempfile.TemporaryDirectory() as directory:
            res = self.make_tree(directory, translated=1)
            floors = Path(directory) / "translation_floors.json"
            floors.write_text('{"floors": {"values-de": 50.0}}', encoding="utf-8")

            self.assertEqual(1, self.run_checker(res, floors, "--update-floors"))
            self.assertEqual({"values-de": 50.0}, self.recorded(floors))
            self.assertEqual(1, self.run_checker(res, floors))

    def test_update_floors_raises_a_floor_the_locale_has_passed(self):
        with tempfile.TemporaryDirectory() as directory:
            res = self.make_tree(directory, translated=3)
            floors = Path(directory) / "translation_floors.json"
            floors.write_text('{"floors": {"values-de": 50.0}}', encoding="utf-8")

            self.assertEqual(0, self.run_checker(res, floors, "--update-floors"))
            self.assertEqual({"values-de": 75.0}, self.recorded(floors))
            self.assertEqual(0, self.run_checker(res, floors))

    def test_an_unreadable_floors_file_fails_the_check_and_is_not_rewritten(self):
        with tempfile.TemporaryDirectory() as directory:
            res = self.make_tree(directory, translated=3)
            floors = Path(directory) / "translation_floors.json"
            floors.write_text("not json", encoding="utf-8")

            self.assertEqual(1, self.run_checker(res, floors))
            self.assertEqual(1, self.run_checker(res, floors, "--update-floors"))
            self.assertEqual("not json", floors.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
