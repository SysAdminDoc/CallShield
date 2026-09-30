#!/usr/bin/env python3
"""The recommended-list catalog phones show in Settings (data/list_catalog.json).

The app skips an entry it can't use rather than refusing the file, so a bad
entry would quietly vanish from every phone. These checks catch it here.
"""

from __future__ import annotations

import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CATALOG = ROOT / "data" / "list_catalog.json"
SOURCE_MANIFEST = ROOT / "data" / "source-manifest.json"


class ListCatalogTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
        cls.lists = cls.catalog["lists"]

    def test_the_schema_and_revision_are_ones_the_app_reads(self):
        self.assertEqual(1, self.catalog["version"])
        self.assertIsInstance(self.catalog["revision"], int)
        self.assertGreater(self.catalog["revision"], 0)
        self.assertTrue(self.lists)

    def test_every_list_is_usable_by_the_app(self):
        for entry in self.lists:
            with self.subTest(entry.get("id")):
                self.assertRegex(entry["id"], r"^[a-z0-9][a-z0-9-]{0,39}$")
                self.assertTrue(0 < len(entry["name"].strip()) <= 60)
                self.assertRegex(entry["country"], r"^[A-Z]{2}$")
                self.assertRegex(entry["calling_code"], r"^[1-9][0-9]{0,2}$")
                self.assertRegex(entry["trunk_prefix"], r"^[0-9]{0,2}$")
                self.assertTrue(entry["national_lengths"])
                self.assertTrue(all(4 <= n <= 14 for n in entry["national_lengths"]))
                self.assertTrue(0 < len(entry["license"]) <= 40)
                self.assertIn(entry["format"], {"json", "csv", "txt"})
                for field in ("url", "homepage", "license_url"):
                    self.assertTrue(entry[field].startswith("https://"), field)
                    self.assertNotRegex(entry[field], r"^https://[^/]*@", field)

    def test_nothing_is_on_until_someone_adds_it(self):
        for entry in self.lists:
            self.assertIs(False, entry["enabled_by_default"], entry["id"])

    def test_ids_and_links_are_unique(self):
        ids = [entry["id"] for entry in self.lists]
        urls = [entry["url"] for entry in self.lists]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertEqual(len(urls), len(set(urls)))

    def test_no_catalog_list_is_a_database_source(self):
        # Catalog lists are downloaded by the phones that add them. Their
        # licenses (GPL, for some) don't allow folding them into the database.
        manifest = SOURCE_MANIFEST.read_text(encoding="utf-8")
        for entry in self.lists:
            repository = re.sub(r"^https://github\.com/", "", entry["homepage"])
            self.assertNotIn(repository, manifest, entry["id"])
            self.assertNotIn(
                re.sub(r"^https://", "", entry["url"]), manifest, entry["id"]
            )


if __name__ == "__main__":
    unittest.main()
