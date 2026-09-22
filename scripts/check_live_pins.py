#!/usr/bin/env python3
"""Fail when a pinned host serves a certificate chain that holds none of its pins.

The app pins every host it talks to (HttpClient.pinnedEndpointPins). A pin set
that no longer matches the live chain does not show up as an error anywhere
obvious: OkHttp refuses the connection, the sync falls back to the bundled
snapshot, and the only visible symptom is a sync date that stops moving. That
is exactly what happened when GitHub's raw host moved to Let's Encrypt's
Generation Y hierarchy around 2026-08-02; three releases shipped with every
feed download failing.

This reads the pins straight from HttpClient.kt, fetches each host's chain,
and exits non-zero when any host has no pinned key in its chain. A host that
cannot be reached counts as a failure too: a release cut with no evidence that
its pins work is the failure this gate exists to prevent.
"""

from __future__ import annotations

import base64
import hashlib
import re
import shutil
import socket
import ssl
import subprocess
import sys
from pathlib import Path
from typing import Callable

ROOT = Path(__file__).resolve().parent.parent
HTTP_CLIENT = ROOT / "app" / "src" / "main" / "java" / "com" / "sysadmindoc" / "callshield" / "data" / "remote" / "HttpClient.kt"
FETCH_TIMEOUT_SECONDS = 20
_TOKEN = re.compile(r'"([A-Za-z0-9.-]+\.[A-Za-z]{2,})"\s+to\b|"sha256/([A-Za-z0-9+/]{43}=)"')
_PEM = re.compile(rb"-----BEGIN CERTIFICATE-----(.+?)-----END CERTIFICATE-----", re.S)
_LINE_COMMENT = re.compile(r"//[^\n]*")
_OPENSSL_FALLBACKS = (
    Path(r"C:\Program Files\Git\usr\bin\openssl.exe"),
    Path(r"C:\Program Files\Git\mingw64\bin\openssl.exe"),
)


def read_pins(source: str) -> dict[str, list[str]]:
    """Host -> SPKI pins, in declaration order, from HttpClient.kt source."""
    start = source.index("pinnedEndpointPins")
    end = source.index("certificatePinner", start)
    # Line comments name hosts and keys in prose; only code counts.
    block = _LINE_COMMENT.sub("", source[start:end])
    pins: dict[str, list[str]] = {}
    host = None
    for match in _TOKEN.finditer(block):
        if match.group(1):
            host = match.group(1)
            pins[host] = []
        elif host is not None:
            pins[host].append(match.group(2))
    return pins


def _read_tlv(data: bytes, offset: int) -> tuple[int, int, int]:
    """DER tag, header length and content length of the element at offset."""
    tag = data[offset]
    first = data[offset + 1]
    if first < 0x80:
        return tag, 2, first
    count = first & 0x7F
    length = int.from_bytes(data[offset + 2 : offset + 2 + count], "big")
    return tag, 2 + count, length


def spki_sha256(certificate_der: bytes) -> str:
    """Base64 SHA-256 of a certificate's SubjectPublicKeyInfo, the value OkHttp pins."""
    _, header, _ = _read_tlv(certificate_der, 0)
    tbs_offset = header
    _, tbs_header, tbs_length = _read_tlv(certificate_der, tbs_offset)
    cursor = tbs_offset + tbs_header
    tbs_end = cursor + tbs_length
    fields = []
    while cursor < tbs_end:
        tag, field_header, length = _read_tlv(certificate_der, cursor)
        fields.append((tag, cursor, field_header + length))
        cursor += field_header + length
    # version is an optional [0] EXPLICIT tag; then serial, signature, issuer,
    # validity, subject, and the public key info.
    if fields and fields[0][0] == 0xA0:
        fields = fields[1:]
    _, spki_start, spki_size = fields[5]
    spki = certificate_der[spki_start : spki_start + spki_size]
    return base64.b64encode(hashlib.sha256(spki).digest()).decode("ascii")


def _openssl() -> str | None:
    found = shutil.which("openssl")
    if found:
        return found
    for candidate in _OPENSSL_FALLBACKS:
        if candidate.exists():
            return str(candidate)
    return None


def fetch_chain(host: str) -> list[bytes]:
    """DER certificates exactly as the host presents them.

    Nothing here validates the chain against this machine's trust store: the
    question is only which keys the server hands over, because matching them
    against the pins is the trust decision the app makes. Validating locally
    would fail hosts whose root this machine happens not to carry.
    """
    if hasattr(ssl.SSLSocket, "get_unverified_chain"):  # Python 3.13+
        context = ssl.create_default_context()
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        with socket.create_connection((host, 443), timeout=FETCH_TIMEOUT_SECONDS) as raw:
            with context.wrap_socket(raw, server_hostname=host) as tls:
                chain = [bytes(cert) for cert in tls.get_unverified_chain()]
        if chain:
            return chain
    openssl = _openssl()
    if openssl is None:
        raise OSError("no certificate chain API in this Python and no openssl binary to ask instead")
    completed = subprocess.run(
        [openssl, "s_client", "-connect", f"{host}:443", "-servername", host, "-showcerts"],
        input=b"",
        capture_output=True,
        timeout=FETCH_TIMEOUT_SECONDS,
        check=False,
    )
    chain = [base64.b64decode(b"".join(body.split())) for body in _PEM.findall(completed.stdout)]
    if not chain:
        raise OSError(f"openssl returned no certificates for {host}")
    return chain


def evaluate(pins: dict[str, list[str]], fetch: Callable[[str], list[bytes]]) -> list[tuple[str, bool, str]]:
    results = []
    for host, host_pins in pins.items():
        try:
            live = [spki_sha256(der) for der in fetch(host)]
        except (OSError, ssl.SSLError, subprocess.SubprocessError, ValueError, IndexError) as error:
            results.append((host, False, f"could not read the live chain: {error}"))
            continue
        matched = [pin for pin in host_pins if pin in live]
        if matched:
            results.append((host, True, f"matches {len(matched)} of {len(host_pins)} pins"))
        else:
            results.append((host, False, "no pin matches the live chain; live keys: " + ", ".join(live)))
    return results


def main() -> int:
    pins = read_pins(HTTP_CLIENT.read_text(encoding="utf-8"))
    if not pins:
        print(f"no pins found in {HTTP_CLIENT}", file=sys.stderr)
        return 1
    failures = 0
    for host, ok, detail in evaluate(pins, fetch_chain):
        print(f"{'ok  ' if ok else 'FAIL'} {host}: {detail}")
        failures += 0 if ok else 1
    if failures:
        print(f"{failures} pinned host(s) would reject the app's connections.", file=sys.stderr)
        return 1
    print(f"All {len(pins)} pinned hosts serve a chain holding at least one pinned key.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
