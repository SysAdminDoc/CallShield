#!/usr/bin/env python3
"""Sign the published protection feeds and check their signatures.

Devices download the database manifest, the legacy database, the three hot
feeds and the ML weights from raw.githubusercontent.com. Until now nothing but
TLS pinning stood between a device and a tampered file, and pinning is exactly
what broke from 2026-08-02 when GitHub's certificate moved to a new Let's
Encrypt hierarchy. Each of those files now has a detached signature beside it:
`<file>.sig` holds a base64 DER ECDSA P-256 signature over the file's exact
bytes. The app refuses a file whose signature doesn't verify against a key
compiled into it (data/remote/FeedSignature.kt) and keeps its last good data.

Shards need no signature of their own: the signed manifest carries each
shard's SHA-256, and the app checks every shard against it.

    python scripts/feed_signing.py sign     # after the pipeline, before committing
    python scripts/feed_signing.py verify   # CI and release: every feed must verify
    python scripts/feed_signing.py generate-key PATH   # once, then add the key to the app

The signing key is read from CALLSHIELD_FEED_SIGNING_KEY, or from
~/.callshield/feed-signing-key.pem. It never goes in the repository.
"""

from __future__ import annotations

import argparse
import base64
import os
import re
import sys
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

ROOT = Path(__file__).resolve().parent.parent
DATA_DIR = Path(os.environ.get("CALLSHIELD_DATA_DIR", ROOT / "data"))
KOTLIN_KEYS = ROOT / "app" / "src" / "main" / "java" / "com" / "sysadmindoc" / "callshield" / "data" / "remote" / "FeedSignature.kt"
KEY_ENV = "CALLSHIELD_FEED_SIGNING_KEY"
DEFAULT_KEY = Path.home() / ".callshield" / "feed-signing-key.pem"
SIGNATURE_SUFFIX = ".sig"

# Every file the app fetches and trusts from the repository. Keep in step with
# GitHubDataSource.SIGNED_FEED_PATHS; test_feed_signing.py checks they agree.
SIGNED_FEEDS = (
    "spam_numbers.json",
    "spam_numbers.manifest.json",
    "hot_numbers.json",
    "hot_ranges.json",
    "spam_domains.json",
    "spam_model_weights.json",
)

# The SubjectPublicKeyInfo of every P-256 key starts with these bytes, so a
# quoted base64 string with this prefix inside FeedSignature.TRUSTED_KEYS is a
# trusted key.
_P256_SPKI_PREFIX = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE"
# Matched only after comments are stripped. Base64 has no parenthesis, so the
# first ")" then closes the list.
_TRUSTED_KEYS_BLOCK = re.compile(r"\bTRUSTED_KEYS\b(?:\s*:[^=]*)?\s*=\s*listOf\((.*?)\)", re.S)


def signature_path(feed: Path) -> Path:
    return feed.with_name(feed.name + SIGNATURE_SUFFIX)


def strip_kotlin_comments(text: str) -> str:
    """Kotlin source with its comments removed, the way the compiler reads it.

    String and character literals are copied whole, since "//" can occur
    inside a base64 key. Block comments nest in Kotlin, so "/* /* */ x */" is
    one comment. Each comment keeps its line breaks, which keeps line numbers.
    A string template that nests quotes isn't followed. FeedSignature.kt has
    none, and the one-declaration check below catches a list it would hide.
    """
    out: list[str] = []
    i, n = 0, len(text)
    while i < n:
        if text.startswith('"""', i):
            end = text.find('"""', i + 3)
            end = n if end == -1 else end + 3
            out.append(text[i:end])
            i = end
        elif text[i] in "\"'":
            quote, j = text[i], i + 1
            while j < n and text[j] != quote and text[j] != "\n":
                j += 2 if text[j] == "\\" else 1
            out.append(text[i : j + 1])
            i = j + 1
        elif text.startswith("//", i):
            end = text.find("\n", i)
            i = n if end == -1 else end
        elif text.startswith("/*", i):
            depth, j = 1, i + 2
            while j < n and depth:
                if text.startswith("/*", j):
                    depth, j = depth + 1, j + 2
                elif text.startswith("*/", j):
                    depth, j = depth - 1, j + 2
                else:
                    j += 1
            out.append("\n" * text.count("\n", i, j))
            i = j
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def trusted_public_keys(source: str | None = None) -> list[ec.EllipticCurvePublicKey]:
    """The keys the app accepts, read from the Kotlin list that compiles them in.

    Comments are stripped first, the way the compiler reads the file, so a key
    commented out of the list (the way a rotation retires one), a note beside
    a key, or an old declaration left in a comment doesn't count. Signing with
    a key the app no longer trusts would publish feeds new installs refuse.
    The file must declare the list exactly once.
    """
    text = KOTLIN_KEYS.read_text(encoding="utf-8") if source is None else source
    blocks = _TRUSTED_KEYS_BLOCK.findall(strip_kotlin_comments(text))
    if len(blocks) != 1:
        raise ValueError(f"FeedSignature.kt must declare TRUSTED_KEYS = listOf(...) once, found {len(blocks)}")
    live = blocks[0]
    keys = []
    for encoded in re.findall(rf'"({_P256_SPKI_PREFIX}[A-Za-z0-9+/=]+)"', live):
        key = serialization.load_der_public_key(base64.b64decode(encoded))
        if not isinstance(key, ec.EllipticCurvePublicKey) or key.curve.name != "secp256r1":
            raise ValueError("FeedSignature.kt lists a key that isn't P-256")
        keys.append(key)
    return keys


def public_key_base64(key: ec.EllipticCurvePublicKey) -> str:
    der = key.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return base64.b64encode(der).decode("ascii")


def load_private_key(path: Path | None = None) -> ec.EllipticCurvePrivateKey:
    key_path = Path(os.environ.get(KEY_ENV, DEFAULT_KEY)) if path is None else Path(path)
    if not key_path.is_file():
        raise FileNotFoundError(
            f"No feed signing key at {key_path}. Set {KEY_ENV} or put the key at {DEFAULT_KEY}; "
            "it must never be committed."
        )
    key = serialization.load_pem_private_key(key_path.read_bytes(), password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey) or key.curve.name != "secp256r1":
        raise ValueError(f"{key_path} is not a P-256 private key")
    return key


def sign_bytes(data: bytes, key: ec.EllipticCurvePrivateKey) -> str:
    return base64.b64encode(key.sign(data, ec.ECDSA(hashes.SHA256()))).decode("ascii")


def verifies(data: bytes, signature_text: str, keys: list[ec.EllipticCurvePublicKey]) -> bool:
    try:
        signature = base64.b64decode(signature_text.strip(), validate=True)
    except ValueError:
        return False
    for key in keys:
        try:
            key.verify(signature, data, ec.ECDSA(hashes.SHA256()))
            return True
        except InvalidSignature:
            continue
    return False


def sign_feeds(data_dir: Path, key: ec.EllipticCurvePrivateKey, trusted: list[ec.EllipticCurvePublicKey]) -> list[str]:
    """Sign every published feed whose signature doesn't already verify.

    ECDSA signatures are randomized, so re-signing an unchanged file would
    rewrite its .sig on every run. A signature that still verifies is kept.
    """
    own = public_key_base64(key.public_key())
    if own not in {public_key_base64(k) for k in trusted}:
        raise ValueError(
            "The signing key isn't one the app trusts, so devices would reject everything it signs. "
            f"Add its public key to FeedSignature.kt first: {own}"
        )
    written = []
    for name in SIGNED_FEEDS:
        feed = Path(data_dir) / name
        if not feed.is_file():
            continue
        data = feed.read_bytes()
        sig = signature_path(feed)
        if sig.is_file() and verifies(data, sig.read_text(encoding="ascii", errors="replace"), [key.public_key()]):
            continue
        sig.write_bytes((sign_bytes(data, key) + "\n").encode("ascii"))
        written.append(name)
    return written


def verify_feeds(data_dir: Path, trusted: list[ec.EllipticCurvePublicKey]) -> list[str]:
    """One message per published feed that a device would refuse."""
    if not trusted:
        return [f"{KOTLIN_KEYS.name} lists no trusted keys"]
    problems = []
    for name in SIGNED_FEEDS:
        feed = Path(data_dir) / name
        if not feed.is_file():
            continue
        sig = signature_path(feed)
        if not sig.is_file():
            problems.append(f"{name} has no {sig.name}; run scripts/feed_signing.py sign")
        elif not verifies(feed.read_bytes(), sig.read_text(encoding="ascii", errors="replace"), trusted):
            problems.append(f"{sig.name} doesn't verify {name} under any key the app trusts")
    return problems


def generate_key(path: Path) -> str:
    path = Path(path)
    if path.exists():
        raise FileExistsError(f"{path} already exists; refusing to overwrite a signing key")
    key = ec.generate_private_key(ec.SECP256R1())
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(
        key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
    return public_key_base64(key.public_key())


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Sign or verify the published protection feeds.")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("sign", help="sign every feed whose signature doesn't verify")
    commands.add_parser("verify", help="fail when any feed lacks a signature the app accepts")
    generate = commands.add_parser("generate-key", help="create a new P-256 signing key")
    generate.add_argument("path", type=Path)
    args = parser.parse_args(argv)

    if args.command == "generate-key":
        public = generate_key(args.path)
        print(f"Wrote {args.path}. Add this public key to FeedSignature.kt:\n{public}")
        return 0

    trusted = trusted_public_keys()
    if args.command == "sign":
        written = sign_feeds(DATA_DIR, load_private_key(), trusted)
        print(f"Signed {len(written)} feed(s): {', '.join(written) if written else 'all signatures already current'}")
        return 0

    problems = verify_feeds(DATA_DIR, trusted)
    if problems:
        print("Published feeds a device would refuse:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    print(f"All published feeds verify under the {len(trusted)} key(s) the app trusts.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
