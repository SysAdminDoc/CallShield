#!/usr/bin/env python3
"""Regression tests for regional prefix validation and source-owned expiry."""

import importlib.util
import json
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
SCRIPTS_DIR = ROOT / "scripts"


def load_importer():
    spec = importlib.util.spec_from_file_location(
        "import_all_sources_regional_test", SCRIPTS_DIR / "import_all_sources.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class FakeResponse:
    def __init__(self, payload, status_code=200):
        self.payload = payload
        self.status_code = status_code

    def raise_for_status(self):
        if self.status_code >= 400:
            raise RuntimeError(f"HTTP {self.status_code}")

    def json(self):
        return self.payload


def evidence():
    return [{"source_id": "saracroche_prefixes", "retrieved_at": "2026-08-10T00:00:00+00:00"}]


def test_fetch_validates_country_allocation_and_is_list_compatible():
    module = load_importer()
    original_get = module.requests.get
    try:
        module.requests.get = lambda _url, **_kwargs: FakeResponse(
            {
                "patterns": [
                    {"action": "block", "pattern": "33162######", "name": "ARCEP"},
                    {"action": "block", "pattern": "332688#####", "name": "Operator"},
                    {"action": "block", "pattern": "441234######", "name": "Wrong country"},
                    {"action": "block", "pattern": "33012######", "name": "French trunk form"},
                ]
            }
        )
        result = module.fetch_saracroche_prefixes()

        assert result.complete
        assert {row["prefix"] for row in result} == {"+33162", "+332688"}
        assert module.is_valid_saracroche_prefix("+33162")
        assert not module.is_valid_saracroche_prefix("+441234")
        assert not module.is_valid_saracroche_prefix("+33012")
    finally:
        module.requests.get = original_get


def test_successful_refresh_expires_only_saracroche_ranges():
    module = load_importer()
    with tempfile.TemporaryDirectory() as directory:
        directory = Path(directory)
        db_path = directory / "spam_numbers.json"
        snapshot_path = directory / "source-snapshot.json"
        db_path.write_text(
            json.dumps(
                {
                    "version": 7,
                    "updated": "2026-08-09",
                    "numbers": [],
                    "prefixes": [
                        {
                            "prefix": "+33162",
                            "type": "telemarketer",
                            "description": "Saracroche: old range",
                        },
                        {
                            "prefix": "+332688",
                            "type": "telemarketer",
                            "description": "Saracroche: retained range",
                            "evidence": evidence(),
                        },
                        {
                            "prefix": "+1212",
                            "type": "user",
                            "description": "Local user range",
                        },
                    ],
                }
            ),
            encoding="utf-8",
        )
        module.DB_FILE = db_path
        module.SOURCE_SNAPSHOT_FILE = snapshot_path
        module.merge_into_database(
            [],
            min_reports=1,
            prefixes=[
                {
                    "prefix": "+332688",
                    "type": "telemarketer",
                    "description": "Saracroche: refreshed range",
                    "evidence": evidence(),
                }
            ],
            source_names={"saracroche_prefixes"},
            source_stats={"saracroche_prefixes": {"status": "ok", "accepted": 1}},
            refresh_prefix_sources={"saracroche_prefixes"},
        )
        refreshed = json.loads(db_path.read_text(encoding="utf-8"))
        prefixes = {row["prefix"]: row for row in refreshed["prefixes"]}
        assert "+33162" not in prefixes
        assert prefixes["+332688"]["description"] == "Saracroche: refreshed range"
        assert "+1212" in prefixes

        module.merge_into_database(
            [],
            min_reports=1,
            prefixes=[],
            source_names=set(),
            source_stats={"saracroche_prefixes": {"status": "error"}},
            refresh_prefix_sources=set(),
        )
        failed_refresh = json.loads(db_path.read_text(encoding="utf-8"))
        assert {row["prefix"] for row in failed_refresh["prefixes"]} == {"+332688", "+1212"}


def main():
    test_fetch_validates_country_allocation_and_is_list_compatible()
    test_successful_refresh_expires_only_saracroche_ranges()
    print("regional prefix tests: OK")


if __name__ == "__main__":
    main()
