#!/usr/bin/env python3
"""Security regression tests for the community translation validator."""

import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

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

    def test_floors_round_trip_and_a_broken_file_is_not_fatal(self):
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
                path.write_text("not json", encoding="utf-8")
                self.assertEqual({}, check_translations.load_floors())
                path.write_text('{"floors": {"values-de": "sixty"}}', encoding="utf-8")
                self.assertEqual({}, check_translations.load_floors())
            finally:
                check_translations.FLOORS_FILE = original

    def test_recorded_floor_matches_the_shipped_locale(self):
        # The committed floor must describe reality, or the gate is decorative.
        floors = check_translations.load_floors()
        self.assertIn("values-zh-rCN", floors, floors)


if __name__ == "__main__":
    unittest.main()
