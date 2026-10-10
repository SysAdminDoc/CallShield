#!/usr/bin/env python3
"""The parts of scripts/device_smoke.py that don't need a phone."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import device_smoke  # noqa: E402

# Trimmed from a real S22 dump: the Lookup tab's title and its tab bar entry,
# a number in Unicode isolates, an escaped ampersand and a node with no label.
DUMP = (
    '<?xml version="1.0" encoding="UTF-8"?><hierarchy rotation="0">'
    '<node index="0" text="Lookup" resource-id="" content-desc="" bounds="[407,128][679,203]" />'
    '<node index="1" text="⁦+33145230001⁩" content-desc="" bounds="[48,860][394,908]" />'
    '<node index="2" text="" content-desc="Tools &amp; support" bounds="[48,280][464,342]" />'
    '<node index="3" text="" content-desc="" bounds="[0,0][1080,2316]" />'
    '<node index="4" text="Lookup" resource-id="" content-desc="" bounds="[478,2088][600,2138]" />'
    "</hierarchy>"
)


class DeviceSmokeTest(unittest.TestCase):
    def test_nodes_are_read_with_their_centers_and_plain_labels(self):
        nodes = device_smoke.parse_nodes(DUMP)

        self.assertEqual(["Lookup", "+33145230001", "Tools & support", "Lookup"], [n.label for n in nodes])
        self.assertEqual((543, 165), (nodes[0].x, nodes[0].y))

    def test_a_tab_is_the_lowest_node_with_its_name(self):
        tab = device_smoke.bottom_most(device_smoke.parse_nodes(DUMP), "Lookup")

        self.assertEqual((539, 2113), (tab.x, tab.y))
        self.assertIsNone(device_smoke.bottom_most(device_smoke.parse_nodes(DUMP), "Rules"))

    def test_the_expected_version_and_lists_come_from_the_repo(self):
        self.assertRegex(device_smoke.app_version(), r"^\d+\.\d+\.\d+$")
        self.assertIn("Lista Hũ", device_smoke.catalog_names())


if __name__ == "__main__":
    unittest.main()
