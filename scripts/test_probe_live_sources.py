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


CLEAN_ANSWER = '{"number":"8443218090","is_spam":false,"response_time_ms":0.01}'
SPAM_ANSWER = '{"number":"8333041447","is_spam":true,"status_code":110,"status_description":"scam"}'


def answering(spam_answer):
    """A source that answers the clean sample normally and the spam sample with `spam_answer`."""

    def fetcher(url):
        return (200, spam_answer) if url.endswith(SKIPCALLS.spam_sample_digits) else (200, CLEAN_ANSWER)

    return fetcher


class CheckTest(unittest.TestCase):
    def test_a_real_answer_passes(self):
        ok, _ = probe_live_sources.check(SKIPCALLS, answering(SPAM_ANSWER))
        self.assertTrue(ok)

    def test_a_known_spam_number_answered_as_clean_fails(self):
        # The clean sample alone would pass this: the shape is right, the verdict is gone.
        ok, detail = probe_live_sources.check(SKIPCALLS, answering('{"number":"8333041447","is_spam":false}'))
        self.assertFalse(ok)
        self.assertIn("no longer reads as spam", detail)

    def test_an_uncategorized_is_spam_answer_is_not_treated_as_a_spam_verdict(self):
        answer = '{"number":"8333041447","is_spam":true,"status_description":"unknown"}'
        ok, detail = probe_live_sources.check(SKIPCALLS, answering(answer))
        self.assertFalse(ok)
        self.assertIn("no longer reads as spam", detail)

    def test_a_spam_verdict_moved_to_another_field_fails(self):
        ok, _ = probe_live_sources.check(SKIPCALLS, answering('{"number":"8333041447","verdict":"spam"}'))
        self.assertFalse(ok)

    def test_the_spam_marker_is_the_verdict_the_kotlin_parser_reads_as_spam(self):
        self.assertIsNotNone(SKIPCALLS.marker.search(SPAM_ANSWER))
        self.assertIsNotNone(SKIPCALLS.spam_marker.search(SPAM_ANSWER))
        self.assertIsNone(SKIPCALLS.spam_marker.search(CLEAN_ANSWER))

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


class SampleTest(unittest.TestCase):
    def test_only_the_categories_the_app_acts_on_count_as_flagged(self):
        self.assertEqual("flagged", probe_live_sources.classify(200, SPAM_ANSWER))
        self.assertEqual(
            "uncategorized",
            probe_live_sources.classify(200, '{"is_spam":true,"status_description":"unknown"}'),
        )
        self.assertEqual("clean", probe_live_sources.classify(200, CLEAN_ANSWER))
        self.assertEqual("error", probe_live_sources.classify(429, ""))
        self.assertEqual("error", probe_live_sources.classify(200, PARKED_PAGE))

    def test_a_flag_adds_only_when_the_local_database_lacks_the_number(self):
        answers = [
            probe_live_sources.Answer("+18333041447", "flagged"),
            probe_live_sources.Answer("+15550100000", "flagged"),
            probe_live_sources.Answer("+18002752273", "uncategorized"),
            probe_live_sources.Answer("+15550100001", "clean"),
            probe_live_sources.Answer("+15550100002", "error"),
        ]
        summary = probe_live_sources.summarize("pending", answers, {"+18333041447"})
        self.assertEqual(5, summary["asked"])
        self.assertEqual(4, summary["answered"])
        self.assertEqual(2, summary["flagged"])
        self.assertEqual(1, summary["adds"])
        self.assertEqual(["+15550100000"], summary["add_examples"])
        self.assertEqual(1, summary["uncategorized"])
        self.assertEqual(1, summary["errors"])

    def test_numbers_are_asked_as_digits_and_a_dropped_connection_is_an_error(self):
        asked = []

        def fetcher(url):
            asked.append(url)
            if url.endswith("15550100002"):
                raise OSError("reset")
            return 200, SPAM_ANSWER

        answers = probe_live_sources.ask_all(["+18333041447", "+15550100002"], fetcher, 0)
        self.assertEqual(SKIPCALLS.url_prefix + "18333041447", asked[0])
        self.assertEqual(["flagged", "error"], [a.verdict for a in answers])

    def test_business_lines_are_distinct_e164_numbers(self):
        lines = probe_live_sources.BUSINESS_LINES
        self.assertEqual(50, len(lines))
        self.assertEqual(len(lines), len(set(lines)))
        for line in lines:
            self.assertRegex(line, r"^\+1\d{10}$")


if __name__ == "__main__":
    unittest.main()
