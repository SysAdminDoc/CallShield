#!/usr/bin/env python3
"""Offline tests for check_live_pins.py. The live check itself needs the network."""

import base64
import unittest

import check_live_pins

# ISRG Root YR, cross-signed by ISRG Root X1, exactly as raw.githubusercontent.com
# served it on 2026-09-21. Public certificate; its SPKI pin is known independently.
ROOT_YR_PEM = """
MIIF9DCCA9ygAwIBAgIRAPJLbRf52a18scn+p4eCaZ8wDQYJKoZIhvcNAQELBQAw
TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh
cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMjYwNTEzMDAwMDAw
WhcNMzIwOTAyMjM1OTU5WjAuMQswCQYDVQQGEwJVUzENMAsGA1UEChMESVNSRzEQ
MA4GA1UEAxMHUm9vdCBZUjCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIB
ANvGJnN78CTJdWL3+eGfsLN5TrNBJs+VH9hRXqRbwxu9sGNiB0BD1fcOxbSUQCJI
M1xE13Db+5Cw1w0s0EBYsvuIP/6joF0w8cuImbgR1OGgYbSQ4OpzI+DG8SGuTlcE
873OCS+kh3srlo6vl43M5OJg4Aeo1sfHp6kTJDoIiFBNJAY+OKfX/FUvYKuhjT+n
o49lmqmupSBI5PkBQiqrEGtWU5uxU/cQWHGu8jSjFBznZqvbNPLMXMLFxCb3WTfr
JBXXjqvWG+v4bjzxjjeAtOlU7qarRDvNOyAuQYLln904M+faKx8hnLCpJ15ZqaEg
cNlY+9MMWcC5yvL2A2j3l9+2buggZX+dOE91zYmIdawTvSZuVvlbRrAlLxIB6pwM
BjneXCjYQ8+3BCCjssbSNpZU3hTcBDdhfAlEDlYr6pEatnMdmDT5BqnKC92bd0Eh
M1fbLHioLccLCuievT8ZkPhZrq7Mii7gNXAcUEAR8+lzYal+9zTg7C5DALyVOeG/
CqfRAMn1KSHCR0NSA6P8tn/mGRlnCct5rtVCLnVySVpU6H1qGg3DgTOuskf8eahT
MiYbI5ezPJmO5ertalskQ1utp74+eDy92PI4ftHKTbq9IWhH4YZKh3WnJEIt+oQv
lYZbY8tpEroKrFB6PFGzrJIDRyts4HqvuH52RFj2zv/BAgMBAAGjgeswgegwDgYD
VR0PAQH/BAQDAgEGMBMGA1UdJQQMMAoGCCsGAQUFBwMBMA8GA1UdEwEB/wQFMAMB
Af8wHQYDVR0OBBYEFN7nW2DQIm1AKH0/DQH+pLVStFGUMB8GA1UdIwQYMBaAFHm0
WeZ7tuXkAXOACIjIGlj26ZtuMDIGCCsGAQUFBwEBBCYwJDAiBggrBgEFBQcwAoYW
aHR0cDovL3gxLmkubGVuY3Iub3JnLzATBgNVHSAEDDAKMAgGBmeBDAECATAnBgNV
HR8EIDAeMBygGqAYhhZodHRwOi8veDEuYy5sZW5jci5vcmcvMA0GCSqGSIb3DQEB
CwUAA4ICAQA8spSI95KKfn2W6GMmDpHBJSPaLbsS3W93cijJCRCYAc1fsJgL1FIL
7C0C9ecPOdcwB2fi0Dk2p94j9iTJCxmt5CFSKLRWwnXT2MMSXexVxqoVB79BdWPx
VXETkVme/qYSAuKVHh5Ps+5BixgmwS1JkjSAc+MfrUbNssVEEnH0aEiAh+rotXAV
JSP/Ye7LJPEwD9DWG72vVWbhAcuOf5OLjz57Ctk7MgQHynZ7+PlHJtajroCaIbtC
r6tcZZaAwUQm+jQyeWdV+2hv9deOYFmKeQyjjcSrN5Nadrw+L9DZJLbA1HqeNvLh
BgqpP0fvJq2N6EtD574N6eMI7uMsJTnji2UDz9el5XLSv9fqJMuDQtYVb2oTNoKp
oUqhxPVC0aq4eG5MESaIdn8b5ZGSSeAJLMHXljEdlNza+ncfkviXk1POLnnFdvx8
/gk6M374WbLWFXw8N141B/Rl/tINGfl1TxOIiqtiMYkL02RSGb1kq34BL9NPP27z
RGMuHGnzS3hFIrRTfKxrzUZ9RzQWzEG3K6fJ3r2nqSltkeytis9DIBoFY9VmVyjL
M71DMi+y1+TRSJVClEMwvA4yL++7q9XZx5r5wBRWB4kQTKH5qyoZnDw7iiuh1lID
yDFx8r7i9vIJU5HS3moZLkYWAOilMaV9N56A9Bgb6dNcHkvg3NoaYA==
"""
ROOT_YR_PIN = "fk6IOKit1ild5647BH06ujSIq5XbCgqlbYl6ANhhi88="
ROOT_YR_DER = base64.b64decode("".join(ROOT_YR_PEM.split()))

# The raw host's pins before 2026-09-21: a retired leaf and the LE R12 intermediate.
RETIRED_RAW_PINS = [
    "W+jBdq3o4qj8cXXBURwKqofJk8BG59NEPXOEgMh53sA=",
    "kZwN96eHtZftBWrOZUsd6cA4es80n3NzSk/XtYz2EqQ=",
]


class SpkiTest(unittest.TestCase):
    def test_hash_matches_the_independently_known_pin(self):
        self.assertEqual(ROOT_YR_PIN, check_live_pins.spki_sha256(ROOT_YR_DER))


class ReadPinsTest(unittest.TestCase):
    def test_reads_every_host_and_pin_from_the_real_client(self):
        pins = check_live_pins.read_pins(check_live_pins.HTTP_CLIENT.read_text(encoding="utf-8"))
        self.assertIn("raw.githubusercontent.com", pins)
        self.assertIn(ROOT_YR_PIN, pins["raw.githubusercontent.com"])
        for host, host_pins in pins.items():
            self.assertGreaterEqual(len(host_pins), 2, host)

    def test_comments_and_code_between_entries_do_not_confuse_the_reader(self):
        source = """
        internal val pinnedEndpointPins: Map<String, List<String>> =
            mapOf(
                // "decoy.example" to nothing
                "a.example.com" to
                    listOf(
                        // Root one
                        "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                    ),
                "b.example.org" to listOf("sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="),
            )
        internal val certificatePinner = "sha256/CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC="
        """
        self.assertEqual(
            {
                "a.example.com": ["AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="],
                "b.example.org": ["BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="],
            },
            check_live_pins.read_pins(source),
        )


class EvaluateTest(unittest.TestCase):
    def test_a_chain_holding_a_pinned_key_passes(self):
        results = check_live_pins.evaluate({"raw.githubusercontent.com": [ROOT_YR_PIN]}, lambda host: [ROOT_YR_DER])
        self.assertEqual([("raw.githubusercontent.com", True)], [(host, ok) for host, ok, _ in results])

    def test_the_retired_raw_pins_fail_against_the_live_chain(self):
        # Negative control: this is the state three releases shipped in.
        results = check_live_pins.evaluate({"raw.githubusercontent.com": RETIRED_RAW_PINS}, lambda host: [ROOT_YR_DER])
        ((_, ok, detail),) = results
        self.assertFalse(ok)
        self.assertIn(ROOT_YR_PIN, detail)

    def test_an_unreachable_host_fails_rather_than_passing_quietly(self):
        def offline(host):
            raise OSError("network unreachable")

        ((_, ok, detail),) = check_live_pins.evaluate({"raw.githubusercontent.com": [ROOT_YR_PIN]}, offline)
        self.assertFalse(ok)
        self.assertIn("network unreachable", detail)


if __name__ == "__main__":
    unittest.main()
