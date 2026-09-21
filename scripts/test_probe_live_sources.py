#!/usr/bin/env python3
"""Offline tests for probe_live_sources.py. The live probe itself needs the network."""

import re
import unittest

import probe_live_sources

SKIPCALLS = probe_live_sources.SOURCES[0]
PARKED_PAGE = """<!DOCTYPE html><html><head><script>window.onload=function(){window.location.href="/lander"}</script></head></html>"""
PARSER_KT = probe_live_sources.EXTERNAL_LOOKUP.with_name("ExternalLookupParsers.kt")


class ConfiguredSourcesTest(unittest.TestCase):
    def test_every_source_the_app_calls_is_probed(self):
        configured = probe_live_sources.app_source_urls(probe_live_sources.EXTERNAL_LOOKUP.read_text(encoding="utf-8"))
        self.assertTrue(configured, "expected at least one lookup URL in ExternalLookup.kt")
        self.assertEqual(configured, {source.url_prefix for source in probe_live_sources.SOURCES})

    def test_probe_marker_is_the_one_the_kotlin_parser_keys_on(self):
        field = re.search(r'"(\w+)"', SKIPCALLS.marker.pattern).group(1)
        self.assertIn(f'""""{field}"', PARSER_KT.read_text(encoding="utf-8"))


class CheckTest(unittest.TestCase):
    def test_a_real_answer_passes(self):
        ok, _ = probe_live_sources.check(SKIPCALLS, lambda url: (200, '{"number":"8443218090","is_spam":false}'))
        self.assertTrue(ok)

    def test_a_parked_page_served_with_200_fails(self):
        ok, detail = probe_live_sources.check(SKIPCALLS, lambda url: (200, PARKED_PAGE))
        self.assertFalse(ok)
        self.assertIn("without the field", detail)

    def test_an_account_wall_fails(self):
        ok, detail = probe_live_sources.check(SKIPCALLS, lambda url: (401, '{"err":"Login Required"}'))
        self.assertFalse(ok)
        self.assertEqual("HTTP 401", detail)

    def test_an_unreachable_source_fails(self):
        def offline(url):
            raise OSError("no route to host")

        ok, detail = probe_live_sources.check(SKIPCALLS, offline)
        self.assertFalse(ok)
        self.assertIn("no route to host", detail)


if __name__ == "__main__":
    unittest.main()
