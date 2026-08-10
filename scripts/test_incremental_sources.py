#!/usr/bin/env python3
"""Regression tests for bounded FTC/FCC imports and complaint promotion rules."""

import importlib.util
import json
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
SCRIPTS_DIR = ROOT / "scripts"


def load_importer():
    spec = importlib.util.spec_from_file_location(
        "import_all_sources_incremental_test", SCRIPTS_DIR / "import_all_sources.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class FakeResponse:
    def __init__(self, payload, status_code=200):
        self.payload = payload
        self.status_code = status_code
        self.text = json.dumps(payload)

    def raise_for_status(self):
        if self.status_code >= 400:
            raise RuntimeError(f"HTTP {self.status_code}")

    def json(self):
        return self.payload


def test_fcc_retains_roles_and_spoof_signals():
    module = load_importer()
    original_get = module.requests.get
    original_sleep = module.time.sleep
    requests_seen = []
    try:
        def fake_get(url, **kwargs):
            requests_seen.append((url, kwargs))
            return FakeResponse(
                [
                    {
                        "id": "fcc-1",
                        "issue_date": "2026-08-01T00:00:00.000",
                        "issue": "Unwanted Calls",
                        "type_of_call_or_messge": "Telemarketing",
                        "caller_id_number": "+12125561201",
                        "advertiser_business_phone_number": "+13105561201",
                    },
                    {
                        "id": "fcc-2",
                        "issue_date": "2026-08-02T00:00:00.000",
                        "issue": "My own number is being spoofed",
                        "type_of_call_or_messge": "Robocall",
                        "caller_id_number": "+12125561202",
                    },
                ]
            )

        module.requests.get = fake_get
        module.time.sleep = lambda _seconds: None
        entries = module.fetch_fcc(max_records=10)

        by_role = {(entry["number"], entry["complaint_role"]): entry for entry in entries}
        assert by_role[("+12125561201", "caller_id")]["reports"] == 1
        assert by_role[("+13105561201", "callback_business")]["reports"] == 0
        assert by_role[("+13105561201", "callback_business")]["spoof_signal"] == "callback_number_only"
        spoofed = by_role[("+12125561202", "caller_id")]
        assert spoofed["reports"] == 0
        assert spoofed["spoof_signal"] == "explicit_spoof_claim"
        assert requests_seen[0][1]["params"]["$order"].startswith("issue_date DESC")
        assert entries.cursor["id"] == "fcc-2"
        assert entries.complete
    finally:
        module.requests.get = original_get
        module.time.sleep = original_sleep


def test_incremental_window_retries_and_advances_cursor():
    module = load_importer()
    original_get = module.requests.get
    original_sleep = module.time.sleep
    responses = [
        FakeResponse({}, status_code=403),
        FakeResponse(
            {
                "data": [
                    {
                        "id": "ftc-2",
                        "attributes": {
                            "created-date": "2026-08-02T00:00:00Z",
                            "company-phone-number": "+12125561203",
                            "subject": "Robocall",
                            "recorded-message-or-robocall": "Y",
                        },
                    }
                ]
            }
        ),
    ]
    requests_seen = []
    delays = []
    try:
        def fake_get(url, **kwargs):
            requests_seen.append((url, kwargs))
            return responses.pop(0)

        module.requests.get = fake_get
        module.time.sleep = delays.append
        result = module.fetch_ftc(
            max_records=1,
            cursor={"timestamp": "2026-08-01T00:00:00Z", "id": "ftc-1"},
        )

        assert result.complete
        assert [entry["number"] for entry in result] == ["+12125561203"]
        assert result.cursor == {"timestamp": "2026-08-02T00:00:00Z", "id": "ftc-2"}
        assert len(requests_seen) == 2
        params = requests_seen[0][1]["params"]
        assert params["items_per_page"] == 1
        assert params["sort_order"] == "asc"
        assert params["created_date_from"] == '"2026-08-01T00:00:00Z"'
        assert params["created_date_to"].startswith('"')
        assert delays == [5]
    finally:
        module.requests.get = original_get
        module.time.sleep = original_sleep


def test_cursors_and_snapshot_are_durable_and_attributed():
    module = load_importer()
    with tempfile.TemporaryDirectory() as directory:
        cursor_path = Path(directory) / "source-cursors.json"
        cursors = {"fcc_complaints": {"timestamp": "2026-08-02", "id": "fcc-2"}}
        module.save_source_cursors(cursors, cursor_path)
        assert module.load_source_cursors(cursor_path) == cursors
        cursor_path.write_text(json.dumps({"schema_version": 99, "sources": cursors}), encoding="utf-8")
        assert module.load_source_cursors(cursor_path) == {}

    manifest = module.load_source_manifest(ROOT / "data" / "source-manifest.json")
    entry = {
        "first_seen": "2026-08-02",
        "last_seen": "2026-08-02",
        "complaint_role": "caller_id",
        "spoof_signal": "unverified_caller_id",
    }
    evidence = module.source_evidence(
        manifest,
        "fcc_complaints",
        entry,
        retrieved_at="2026-08-10T12:00:00+00:00",
    )
    assert evidence["complaint_role"] == "caller_id"
    snapshot = module.source_snapshot(
        manifest,
        {
            "fcc_complaints": {
                "status": "ok",
                "accepted": 1,
                "cursor": {"timestamp": "2026-08-02", "id": "fcc-2"},
            }
        },
        fetched_at="2026-08-10T12:00:00+00:00",
    )
    fcc_row = next(row for row in snapshot["sources"] if row["id"] == "fcc_complaints")
    assert fcc_row["attribution"].startswith("US Federal Communications Commission")
    assert fcc_row["cursor"]["id"] == "fcc-2"


def test_new_complaints_require_independent_caller_corroboration():
    module = load_importer()
    with tempfile.TemporaryDirectory() as directory:
        directory = Path(directory)
        db_path = directory / "spam_numbers.json"
        snapshot_path = directory / "source-snapshot.json"
        db_path.write_text(
            json.dumps(
                {
                    "version": 1,
                    "updated": "2026-08-10",
                    "numbers": [],
                    "prefixes": [],
                }
            ),
            encoding="utf-8",
        )
        module.DB_FILE = db_path
        module.SOURCE_SNAPSHOT_FILE = snapshot_path
        entries = [
            {
                "number": "+12125561211",
                "type": "robocall",
                "reports": 9,
                "first_seen": "2026-08-01",
                "last_seen": "2026-08-10",
                "description": "FTC complaint",
                "evidence": [
                    {
                        "source_id": "ftc_complaints",
                        "complaint_role": "caller_id",
                        "spoof_signal": "unverified_originating_number",
                    }
                ],
            },
            {
                "number": "+12125561212",
                "type": "robocall",
                "reports": 2,
                "first_seen": "2026-08-01",
                "last_seen": "2026-08-10",
                "description": "Independent complaints",
                "evidence": [
                    {
                        "source_id": "ftc_complaints",
                        "complaint_role": "caller_id",
                        "spoof_signal": "unverified_originating_number",
                    },
                    {
                        "source_id": "fcc_complaints",
                        "complaint_role": "caller_id",
                        "spoof_signal": "unverified_caller_id",
                    },
                ],
            },
            {
                "number": "+12125561213",
                "type": "robocall",
                "reports": 2,
                "first_seen": "2026-08-01",
                "last_seen": "2026-08-10",
                "description": "Spoof claim only",
                "evidence": [
                    {
                        "source_id": "ftc_complaints",
                        "complaint_role": "caller_id",
                        "spoof_signal": "explicit_spoof_claim",
                    },
                    {
                        "source_id": "fcc_complaints",
                        "complaint_role": "caller_id",
                        "spoof_signal": "explicit_spoof_claim",
                    },
                ],
            },
        ]
        module.merge_into_database(entries, min_reports=1, source_names={"ftc_complaints", "fcc_complaints"})
        result = json.loads(db_path.read_text(encoding="utf-8"))
        numbers = {entry["number"] for entry in result["numbers"]}
        assert numbers == {"+12125561212"}


def main():
    test_fcc_retains_roles_and_spoof_signals()
    test_incremental_window_retries_and_advances_cursor()
    test_cursors_and_snapshot_are_durable_and_attributed()
    test_new_complaints_require_independent_caller_corroboration()
    print("incremental source tests: OK")


if __name__ == "__main__":
    main()
