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


if __name__ == "__main__":
    unittest.main()
