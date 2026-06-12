#!/usr/bin/env python3
"""Regression test for the community-report derived-feed pipeline."""

import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPTS_DIR = ROOT / "scripts"
WORKFLOW_FILE = ROOT / ".github" / "workflows" / "merge-reports.yml"
NOW = "2026-06-12T12:00:00+00:00"


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


def assert_workflow_order() -> None:
    workflow = WORKFLOW_FILE.read_text(encoding="utf-8")
    extract_step = workflow.index("Extract spam domains from SMS reports")
    hot_step = workflow.index("Generate hot list + campaign ranges")
    merge_step = workflow.index("Merge reports into database")
    if not (extract_step < merge_step and hot_step < merge_step):
        raise AssertionError("merge-reports.yml must derive domains and hot feeds before merge cleanup")


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

    reports = [
        ("r1.json", "+12125550101", "https://bad.example/a"),
        ("r2.json", "+12125550101", ""),
        ("r3.json", "+12125550102", "http://bad.example/b"),
        ("r4.json", "+12125550102", ""),
        ("r5.json", "+12125550103", "www.bad.example/c"),
        ("r6.json", "+12125550103", ""),
    ]
    for filename, number, body in reports:
        report = {
            "number": number,
            "type": "phishing",
            "reported_at": NOW,
        }
        if body:
            report["sms_body"] = body
        write_json(data_dir / "reports" / filename, report)


def assert_derived_outputs(data_dir: Path) -> None:
    hot_numbers = json.loads((data_dir / "hot_numbers.json").read_text(encoding="utf-8"))
    hot_ranges = json.loads((data_dir / "hot_ranges.json").read_text(encoding="utf-8"))
    spam_domains = json.loads((data_dir / "spam_domains.json").read_text(encoding="utf-8"))

    numbers = {entry["number"]: entry["reports"] for entry in hot_numbers["numbers"]}
    expected_numbers = {
        "+12125550101": 2,
        "+12125550102": 2,
        "+12125550103": 2,
    }
    if numbers != expected_numbers:
        raise AssertionError(f"unexpected hot numbers: {numbers}")

    ranges = {entry["npanxx"]: entry["count"] for entry in hot_ranges["ranges"]}
    if ranges.get("212555") != 3:
        raise AssertionError(f"expected 212555 campaign range, got {ranges}")

    if "bad.example" not in spam_domains["domains"]:
        raise AssertionError(f"expected bad.example spam domain, got {spam_domains['domains']}")


def assert_merge_cleanup(data_dir: Path) -> None:
    reports_dir = data_dir / "reports"
    if reports_dir.exists() and list(reports_dir.glob("*.json")):
        raise AssertionError("merge script did not remove processed report files")

    merged = json.loads((data_dir / "spam_numbers.json").read_text(encoding="utf-8"))
    merged_numbers = {entry["number"]: entry["reports"] for entry in merged["numbers"]}
    if merged_numbers.get("+12125550101") != 2:
        raise AssertionError(f"expected merged report counts, got {merged_numbers}")


def main() -> None:
    assert_workflow_order()
    with tempfile.TemporaryDirectory() as tmp:
        data_dir = Path(tmp) / "data"
        seed_reports(data_dir)
        run_script("extract_spam_domains.py", data_dir)
        run_script("generate_hot_list.py", data_dir)
        assert_derived_outputs(data_dir)
        run_script("merge_community_reports.py", data_dir)
        assert_merge_cleanup(data_dir)
        assert_derived_outputs(data_dir)


if __name__ == "__main__":
    main()
