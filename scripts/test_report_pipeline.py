#!/usr/bin/env python3
"""Regression tests for the community-report derived-feed pipeline."""

import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPTS_DIR = ROOT / "scripts"
NOW = "2026-06-12T12:00:00+00:00"
TIMES = [
    "2026-06-12T04:00:00+00:00",
    "2026-06-12T06:00:00+00:00",
    "2026-06-12T08:00:00+00:00",
    "2026-06-12T10:00:00+00:00",
    NOW,
]
BUCKETS = [f"{index:016x}" for index in range(1, 7)]


def run_script(name: str, data_dir: Path) -> None:
    env = os.environ.copy()
    env["CALLSHIELD_DATA_DIR"] = str(data_dir)
    env["CALLSHIELD_NOW"] = NOW
    result = subprocess.run(
        [sys.executable, str(SCRIPTS_DIR / name)],
        cwd=ROOT,
        env=env,
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        raise AssertionError(
            f"{name} failed with {result.returncode}\nSTDOUT:\n{result.stdout}\nSTDERR:\n{result.stderr}"
        )


def write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def write_report(
    data_dir: Path,
    filename: str,
    number: str,
    bucket: str | None,
    reported_at: str,
    report_type: str = "phishing",
    domains: list[str] | None = None,
) -> None:
    report = {"number": number, "type": report_type, "reported_at": reported_at}
    if bucket is not None:
        report["reporter_bucket"] = bucket
    if domains:
        report["sms_domains"] = domains
    write_json(data_dir / "reports" / filename, report)


def seed_reports(data_dir: Path) -> None:
    write_json(
        data_dir / "spam_numbers.json",
        {
            "version": 1,
            "updated": "2026-06-11",
            "description": "test database",
            "sources": ["community_reports"],
            "numbers": [],
            "prefixes": [],
        },
    )
    write_json(data_dir / "spam_domains_approved.json", {"domains": ["bad.example"]})

    campaign_buckets = [
        BUCKETS[:5],
        [BUCKETS[0], BUCKETS[1], BUCKETS[2], BUCKETS[3], BUCKETS[5]],
        [BUCKETS[0], BUCKETS[1], BUCKETS[2], BUCKETS[4], BUCKETS[5]],
        [BUCKETS[0], BUCKETS[1], BUCKETS[3], BUCKETS[4], BUCKETS[5]],
    ]
    for number_index, buckets in enumerate(campaign_buckets, start=1):
        number = f"+1212234010{number_index}"
        for report_index, (bucket, reported_at) in enumerate(zip(buckets, TIMES), start=1):
            domains = None
            if report_index <= 2:
                domains = ["bad.example", "co.uk", "login.chase.com", "unreviewed.example"]
            write_report(
                data_dir,
                f"campaign_{number_index}_{report_index}.json",
                number,
                bucket,
                reported_at,
                domains=domains,
            )

    # Many files from only two reporters cannot promote an arbitrary victim.
    for index in range(8):
        write_report(
            data_dir,
            f"attack_{index}.json",
            "+12122340999",
            BUCKETS[index % 2],
            TIMES[index % len(TIMES)],
        )

    # Legacy files remain mergeable but are not independent promotion evidence.
    write_report(data_dir, "legacy.json", "+12122340888", None, TIMES[0])


def assert_derived_outputs(data_dir: Path) -> None:
    hot_numbers = json.loads((data_dir / "hot_numbers.json").read_text(encoding="utf-8"))
    hot_ranges = json.loads((data_dir / "hot_ranges.json").read_text(encoding="utf-8"))
    spam_domains = json.loads((data_dir / "spam_domains.json").read_text(encoding="utf-8"))
    domain_review = json.loads((data_dir / "spam_domains_review.json").read_text(encoding="utf-8"))

    numbers = {entry["number"]: entry for entry in hot_numbers["numbers"]}
    expected = {f"+1212234010{index}" for index in range(1, 5)}
    if set(numbers) != expected:
        raise AssertionError(f"unexpected hot numbers: {numbers}")
    if any(entry["reports"] != 5 or entry["distinct_reporters"] != 5 for entry in numbers.values()):
        raise AssertionError(f"hot evidence metadata is wrong: {numbers}")

    ranges = {entry["npanxx"]: entry for entry in hot_ranges["ranges"]}
    if ranges.get("212234", {}).get("count") != 4:
        raise AssertionError(f"expected robust 212234 campaign range, got {ranges}")

    if spam_domains["domains"] != ["bad.example"]:
        raise AssertionError(f"unexpected approved spam domains: {spam_domains['domains']}")
    review_domains = {candidate["domain"] for candidate in domain_review["candidates"]}
    if review_domains != {"unreviewed.example"}:
        raise AssertionError(f"unexpected domain review candidates: {review_domains}")


def assert_merge_cleanup(data_dir: Path) -> None:
    reports_dir = data_dir / "reports"
    if reports_dir.exists() and list(reports_dir.glob("*.json")):
        raise AssertionError("merge script did not remove processed report files")

    merged = json.loads((data_dir / "spam_numbers.json").read_text(encoding="utf-8"))
    merged_numbers = {entry["number"]: entry for entry in merged["numbers"]}
    if merged_numbers["+12122340101"]["reports"] != 5:
        raise AssertionError(f"expected identity-deduped report counts, got {merged_numbers}")
    if merged_numbers["+12122340999"]["reports"] != 2:
        raise AssertionError(f"same-reporter daily reports were not collapsed: {merged_numbers}")
    if merged_numbers["+12122340888"]["reports"] != 1:
        raise AssertionError(f"legacy report was not preserved: {merged_numbers}")
    if any(entry.get("sources") != ["community"] for entry in merged_numbers.values()):
        raise AssertionError(f"community provenance missing: {merged_numbers}")


def assert_not_spam_requires_review(data_dir: Path) -> None:
    community = "+14152340101"
    authoritative = "+14152340102"
    legacy_authoritative = "+14152340103"
    write_json(
        data_dir / "spam_numbers.json",
        {
            "version": 7,
            "updated": "2026-06-11",
            "numbers": [
                {
                    "number": community,
                    "reports": 2,
                    "type": "spam",
                    "description": "Community reported",
                    "sources": ["community"],
                    "first_seen": "2026-06-01",
                    "last_seen": "2026-06-11",
                },
                {
                    "number": authoritative,
                    "reports": 1,
                    "type": "spam",
                    "description": "Community reported",
                    "sources": ["ftc"],
                    "first_seen": "2026-06-01",
                    "last_seen": "2026-06-11",
                },
                {
                    "number": legacy_authoritative,
                    "reports": 1,
                    "type": "spam",
                    "description": "Imported complaint",
                    "first_seen": "2026-06-01",
                    "last_seen": "2026-06-11",
                },
            ],
            "prefixes": [],
        },
    )
    for index, bucket in enumerate(BUCKETS[:4]):
        write_report(
            data_dir,
            f"community_vote_{index}.json",
            community,
            bucket,
            TIMES[index],
            report_type="not_spam",
        )
        write_report(
            data_dir,
            f"authoritative_vote_{index}.json",
            authoritative,
            bucket,
            TIMES[index],
            report_type="not_spam",
        )
        write_report(
            data_dir,
            f"legacy_vote_{index}.json",
            legacy_authoritative,
            bucket,
            TIMES[index],
            report_type="not_spam",
        )
    write_report(data_dir, "anonymous_vote.json", community, None, NOW, report_type="not_spam")

    run_script("merge_community_reports.py", data_dir)
    merged = json.loads((data_dir / "spam_numbers.json").read_text(encoding="utf-8"))
    by_number = {entry["number"]: entry for entry in merged["numbers"]}
    if by_number[community]["reports"] != 2 or by_number[authoritative]["reports"] != 1:
        raise AssertionError(f"not_spam votes mutated the database: {by_number}")
    if by_number[legacy_authoritative].get("sources") != ["legacy_import"]:
        raise AssertionError(f"legacy provenance was not migrated safely: {by_number}")

    review = json.loads((data_dir / "not_spam_review.json").read_text(encoding="utf-8"))
    candidates = {entry["number"] for entry in review["candidates"]}
    if candidates != {community}:
        raise AssertionError(f"unexpected not-spam review candidates: {candidates}")


def assert_min_reports_spares_existing_rows(data_dir: Path) -> None:
    import importlib.util

    spec = importlib.util.spec_from_file_location(
        "import_all_sources", SCRIPTS_DIR / "import_all_sources.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    db_path = data_dir / "min_reports_db.json"
    db_path.parent.mkdir(parents=True, exist_ok=True)
    write_json(
        db_path,
        {
            "version": 3,
            "updated": "2026-06-01",
            "description": "test",
            "sources": [],
            "numbers": [
                {
                    "number": "+12122340101",
                    "reports": 1,
                    "type": "robocall",
                    "description": "Community reported",
                    "first_seen": "2026-06-01",
                    "last_seen": "2026-06-01",
                },
            ],
            "prefixes": [],
        },
    )

    module.DB_FILE = db_path
    module.merge_into_database(
        [
            {
                "number": "+15302340123",
                "reports": 1,
                "type": "robocall",
                "description": "New low-confidence import",
                "first_seen": "2026-06-12",
                "last_seen": "2026-06-12",
            },
        ],
        min_reports=2,
    )

    result = json.loads(db_path.read_text(encoding="utf-8"))
    numbers = {entry["number"] for entry in result["numbers"]}
    if "+12122340101" not in numbers or "+15302340123" in numbers:
        raise AssertionError(f"min_reports filtering regressed: {numbers}")


def main() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        data_dir = Path(tmp) / "data"
        seed_reports(data_dir)
        run_script("extract_spam_domains.py", data_dir)
        run_script("generate_hot_list.py", data_dir)
        assert_derived_outputs(data_dir)
        run_script("merge_community_reports.py", data_dir)
        assert_merge_cleanup(data_dir)
        assert_derived_outputs(data_dir)
        assert_min_reports_spares_existing_rows(data_dir)

    with tempfile.TemporaryDirectory() as tmp:
        assert_not_spam_requires_review(Path(tmp) / "data")


if __name__ == "__main__":
    main()
