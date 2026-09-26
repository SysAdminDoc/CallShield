#!/usr/bin/env python3
"""Regression tests for bounded FTC/FCC imports and complaint promotion rules."""

import contextlib
import importlib.util
import io
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


def test_future_fcc_row_cannot_poison_the_cursor():
    module = load_importer()
    original_get = module.requests.get
    requests_seen = []
    try:
        def fake_get(url, **kwargs):
            requests_seen.append(kwargs["params"])
            return FakeResponse([
                {"id": "missing", "issue_date": None, "caller_id_number": "+12125561203"},
                {"id": "46486", "issue_date": "9999-12-15T00:00:00.000", "caller_id_number": "+12125561201"},
                {"id": "valid", "issue_date": "2026-09-24T00:00:00.000", "caller_id_number": "+12125561202"},
            ])

        module.requests.get = fake_get
        result = module.fetch_fcc(
            max_records=3,
            cursor={"timestamp": "9999-12-15T00:00:00.000", "id": "46486"},
        )
        assert "issue_date IS NOT NULL" in requests_seen[0]["$where"], requests_seen
        assert "issue_date >=" not in requests_seen[0]["$where"], requests_seen
        assert [row["number"] for row in result] == ["+12125561202"], result
        assert result.cursor == {"timestamp": "2026-09-24T00:00:00.000", "id": "valid"}, result.cursor

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "source-cursors.json"
            module.save_source_cursors({"fcc_complaints": {"timestamp": "9999-12-15", "id": "46486"}}, path)
            assert module.load_source_cursors(path) == {}, path.read_text(encoding="utf-8")
            path.write_text(json.dumps({"schema_version": 1, "sources": {
                "fcc_complaints": {"timestamp": "9999-12-15", "id": "46486"}
            }}), encoding="utf-8")
            assert module.load_source_cursors(path) == {}
    finally:
        module.requests.get = original_get


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


def test_source_freshness_keeps_what_a_run_did_not_fetch():
    module = load_importer()
    previous = {"ftc_complaints": "2026-08-01T00:00:00+00:00", "toastedspam": "2026-08-10T00:00:00+00:00"}
    stats = {
        "ftc_complaints": {"status": "ok", "last_success_at": "2026-09-21T12:00:00+00:00"},
        # Skipped or failed this run: its earlier success must survive.
        "toastedspam": {"status": "not_requested", "last_success_at": None},
        "fcc_complaints": {"status": "error", "last_success_at": None},
    }
    merged = module.merge_source_freshness(previous, stats)
    assert merged == {
        "ftc_complaints": "2026-09-21T12:00:00+00:00",
        "toastedspam": "2026-08-10T00:00:00+00:00",
    }, merged

    dated = module.merge_source_record_dates(
        {"fcc_complaints": "2026-08-01"},
        {"fcc_complaints": {"newest_record_date": "2026-09-24"},
         "ftc_complaints": {"newest_record_date": None}},
    )
    assert dated == {"fcc_complaints": "2026-09-24"}, dated

    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "source-freshness.json"
        assert module.load_source_freshness(path) == {}
        module.save_source_freshness(merged, path, dated)
        assert module.load_source_freshness(path) == merged
        assert module.load_source_freshness(path, "newest_record_date") == dated
        # The liveness gate reads the same file under the same key.
        assert json.loads(path.read_text(encoding="utf-8"))["last_success"] == merged
        path.write_text(json.dumps({"schema_version": 99, "last_success": merged}), encoding="utf-8")
        assert module.load_source_freshness(path) == {}


def test_merge_writes_its_records_beside_the_database():
    # The snapshot and the freshness record went to the real data/ directory
    # whatever DB_FILE said, so every test merge overwrote them. A merge that
    # carries stats would stamp fake successful imports into the record the
    # weekly liveness check reads, hiding a source that had really gone stale.
    module = load_importer()
    with tempfile.TemporaryDirectory() as directory:
        directory = Path(directory)
        db_path = directory / "spam_numbers.json"
        db_path.write_text(
            json.dumps({"version": 1, "updated": "2026-08-10", "numbers": [], "prefixes": []}),
            encoding="utf-8",
        )
        module.DB_FILE = db_path
        stats = {"ftc_complaints": {"status": "ok", "accepted": 0,
                                    "last_success_at": "2026-09-21T12:00:00+00:00",
                                    "newest_record_date": "2026-08-02"}}
        module.merge_into_database([], min_reports=1, source_names={"ftc_complaints"}, source_stats=stats)

        snapshot = json.loads((directory / "source-snapshot.json").read_text(encoding="utf-8"))
        statuses = {row["id"]: row["status"] for row in snapshot["sources"]}
        assert statuses["ftc_complaints"] == "ok", statuses
        freshness = module.load_source_freshness(directory / "source-freshness.json")
        assert freshness == {"ftc_complaints": "2026-09-21T12:00:00+00:00"}, freshness
        assert module.load_source_freshness(directory / "source-freshness.json", "newest_record_date") == {
            "ftc_complaints": "2026-08-02"
        }


def test_new_complaints_require_independent_caller_corroboration():
    module = load_importer()
    with tempfile.TemporaryDirectory() as directory:
        directory = Path(directory)
        db_path = directory / "spam_numbers.json"
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


def test_ftc_demo_key_run_stays_inside_the_hourly_budget():
    # api.ftc.gov gives the shared DEMO_KEY 10 requests a day. A 5,000-record
    # run needs 100, hit the limit part way through and recorded nothing, so
    # the freshness gate read FTC as never imported.
    module = load_importer()
    original_get = module.requests.get
    original_sleep = module.time.sleep
    requests_seen = []
    try:
        def fake_get(url, **kwargs):
            params = kwargs["params"]
            requests_seen.append(params)
            first = params["offset"]
            return FakeResponse(
                {
                    "data": [
                        {
                            "id": f"ftc-{first + n}",
                            "attributes": {
                                "created-date": f"2026-08-01T00:{(first + n) // 60 % 60:02d}:{(first + n) % 60:02d}Z",
                                "company-phone-number": f"+1212556{(first + n) % 10000:04d}",
                                "subject": "Robocall",
                                "recorded-message-or-robocall": "Y",
                            },
                        }
                        for n in range(params["items_per_page"])
                    ]
                }
            )

        module.requests.get = fake_get
        module.time.sleep = lambda _seconds: None

        result = module.fetch_ftc(max_records=5000, api_key=module.FTC_DEMO_KEY)

        assert result.complete
        assert result.cursor is not None
        assert len(requests_seen) == module.FTC_DEMO_KEY_REQUEST_BUDGET, len(requests_seen)
        assert module.FTC_DEMO_KEY_REQUEST_BUDGET < 10, "the FTC API allows DEMO_KEY 10 requests a day"
        assert sum(params["items_per_page"] for params in requests_seen) == module.FTC_DEMO_KEY_REQUEST_BUDGET * module.FTC_PAGE_SIZE
        assert {params["api_key"] for params in requests_seen} == {"DEMO_KEY"}

        # A key of its own is not budgeted.
        requests_seen.clear()
        result = module.fetch_ftc(max_records=1300, api_key="a-key-of-its-own")

        assert result.complete
        assert len(requests_seen) == 26, len(requests_seen)
        assert {params["api_key"] for params in requests_seen} == {"a-key-of-its-own"}
    finally:
        module.requests.get = original_get
        module.time.sleep = original_sleep


def test_merge_summary_counts_only_numbers_that_stayed():
    # The 2026-09-25 refresh printed "Added: 294,619" while the total stood
    # still: every one of them was a single uncorroborated complaint that the
    # filter dropped a few lines later.
    module = load_importer()
    with tempfile.TemporaryDirectory() as directory:
        directory = Path(directory)
        db_path = directory / "spam_numbers.json"
        db_path.write_text(
            json.dumps({"version": 1, "updated": "2026-08-10", "numbers": [], "prefixes": []}),
            encoding="utf-8",
        )
        module.DB_FILE = db_path
        ftc = {"source_id": "ftc_complaints", "complaint_role": "caller_id", "spoof_signal": "unverified_originating_number"}
        fcc = {"source_id": "fcc_complaints", "complaint_role": "caller_id", "spoof_signal": "unverified_caller_id"}
        entries = [
            {
                "number": "+12125561221",
                "type": "robocall",
                "reports": 1,
                "first_seen": "2026-08-01",
                "last_seen": "2026-08-10",
                "description": "One complaint",
                "evidence": [ftc],
            },
            {
                "number": "+12125561222",
                "type": "robocall",
                "reports": 2,
                "first_seen": "2026-08-01",
                "last_seen": "2026-08-10",
                "description": "Independent complaints",
                "evidence": [ftc, fcc],
            },
        ]
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            module.merge_into_database(entries, min_reports=2, source_names={"ftc_complaints", "fcc_complaints"})
        summary = output.getvalue()
        assert "  Added:   1\n" in summary, summary
        assert "  Total:   1\n" in summary, summary


def main():
    test_fcc_retains_roles_and_spoof_signals()
    test_future_fcc_row_cannot_poison_the_cursor()
    test_incremental_window_retries_and_advances_cursor()
    test_cursors_and_snapshot_are_durable_and_attributed()
    test_source_freshness_keeps_what_a_run_did_not_fetch()
    test_merge_writes_its_records_beside_the_database()
    test_new_complaints_require_independent_caller_corroboration()
    test_ftc_demo_key_run_stays_inside_the_hourly_budget()
    test_merge_summary_counts_only_numbers_that_stayed()
    print("incremental source tests: OK")


if __name__ == "__main__":
    main()
