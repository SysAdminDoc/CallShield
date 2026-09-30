#!/usr/bin/env python3
"""Write and sign data/app_release.json, the release notice phones read on their sync.

Phones download it with the other signed feeds every six hours. A build whose
versionCode is lower shows a Home card that links to the release page, so a
fix reaches people who never open GitHub. The app refuses the file unless its
signature verifies and its release URL is this repo's tag page for that
version, so it can't send anyone elsewhere.

Run it once the release APK is built:

    python scripts/write_app_release.py                      # hashes app-release.apk
    python scripts/write_app_release.py --apk CallShield-v1.11.0.apk
    python scripts/write_app_release.py --sha256 <hex>       # hash from the .sha256 sidecar

Commit data/app_release.json and its .sig only after `gh release create` has
published the tag, or the card links to a page that doesn't exist yet.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

import feed_signing

ROOT = Path(__file__).resolve().parent.parent
GRADLE_FILE = ROOT / "app" / "build.gradle.kts"
DEFAULT_APK = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
DATA_DIR = ROOT / "data"
FILE_NAME = "app_release.json"
RELEASE_URL = "https://github.com/SysAdminDoc/CallShield/releases/tag/v{name}"
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")


def gradle_version(text: str) -> tuple[int, str]:
    """(versionCode, versionName) from app/build.gradle.kts."""
    code = re.search(r"^\s*versionCode\s*=\s*(\d+)\s*$", text, re.MULTILINE)
    name = re.search(r'^\s*versionName\s*=\s*"(\d+\.\d+\.\d+)"\s*$', text, re.MULTILINE)
    if not code or not name:
        raise ValueError("versionCode and versionName weren't found in app/build.gradle.kts")
    return int(code.group(1)), name.group(1)


def release_notice(version_code: int, version_name: str, apk_sha256: str) -> dict:
    """The file's content, in the shape GitHubDataSource.parseAppReleaseNotice accepts."""
    if version_code <= 0:
        raise ValueError("versionCode must be positive")
    if not SHA256_PATTERN.fullmatch(apk_sha256):
        raise ValueError("the APK SHA-256 must be 64 lowercase hex digits")
    return {
        "version_code": version_code,
        "version_name": version_name,
        "release_url": RELEASE_URL.format(name=version_name),
        "apk_sha256": apk_sha256,
    }


def write_notice(data_dir: Path, notice: dict) -> Path:
    path = Path(data_dir) / FILE_NAME
    path.write_bytes((json.dumps(notice, indent=2) + "\n").encode("utf-8"))
    return path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Write and sign the release notice phones read on their sync.")
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--apk", type=Path, help=f"release APK to hash (default {DEFAULT_APK.relative_to(ROOT)})")
    source.add_argument("--sha256", help="the APK's SHA-256, when the APK isn't on this machine")
    parser.add_argument("--data-dir", type=Path, default=DATA_DIR, help=argparse.SUPPRESS)
    parser.add_argument("--no-sign", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    try:
        version_code, version_name = gradle_version(GRADLE_FILE.read_text(encoding="utf-8"))
        if args.sha256:
            apk_sha256 = args.sha256.strip().lower()
        else:
            apk = args.apk or DEFAULT_APK
            if not apk.is_file():
                raise ValueError(f"{apk} doesn't exist. Build the release APK first, or pass --sha256.")
            apk_sha256 = hashlib.sha256(apk.read_bytes()).hexdigest()
        path = write_notice(args.data_dir, release_notice(version_code, version_name, apk_sha256))
    except (OSError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1

    print(f"Wrote {path} for {version_name} (versionCode {version_code})")
    if not args.no_sign:
        written = feed_signing.sign_feeds(args.data_dir, feed_signing.load_private_key(), feed_signing.trusted_public_keys())
        print(f"Signed: {', '.join(written) if written else 'nothing changed'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
