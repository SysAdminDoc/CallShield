#!/usr/bin/env python3
"""Regression tests for the community-report derived-feed pipeline."""

import json
import os
import subprocess
import sys
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPTS_DIR = ROOT / "scripts"
BASE_DAY = (datetime.now(timezone.utc) - timedelta(days=1)).date().isoformat()
TODAY = datetime.now(timezone.utc).date().isoformat()
NOW = f"{BASE_DAY}T12:00:00+00:00"
TIMES = [
    f"{BASE_DAY}T04:00:00+00:00",
    f"{BASE_DAY}T06:00:00+00:00",
    f"{BASE_DAY}T08:00:00+00:00",
    f"{BASE_DAY}T10:00:00+00:00",
    NOW,
]
BUCKETS = [f"{index:016x}" for index in range(1, 7)]


def run_script_result(
    name: str,
    data_dir: Path,
    args: list[str] | None = None,
) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    env["CALLSHIELD_DATA_DIR"] = str(data_dir)
    env["CALLSHIELD_NOW"] = NOW
    result = subprocess.run(
        [sys.executable, str(SCRIPTS_DIR / name), *(args or [])],
        cwd=ROOT,
        env=env,
        text=True,
        capture_output=True,
        check=False,
    )
    return result


def run_script(name: str, data_dir: Path, args: list[str] | None = None) -> None:
    result = run_script_result(name, data_dir, args)
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
    report_id: str | None = None,
    device: str | None = None,
) -> None:
    report = {"number": number, "type": report_type, "reported_at": reported_at}
    if bucket is not None:
        report["reporter_bucket"] = bucket
    if device is not None:
        report["reporter_device"] = device
    if report_id is not None:
        report["report_id"] = report_id
    if domains:
        report["sms_domains"] = domains
    write_json(data_dir / "reports" / filename, report)


def seed_reports(data_dir: Path) -> None:
    write_json(
        data_dir / "spam_numbers.json",
        {
            "version": 1,
            "updated": BASE_DAY,
            "description": "test database",
            "sources": ["community_reports"],
            "numbers": [],
            "prefixes": [],
        },
    )
    write_json(
        data_dir / "source-snapshot.json",
        {
            "schema_version": 1,
            "generated_at": NOW,
            "sources": [
                {
                    "id": "community_reports",
                    "status": "ok",
                    "accepted": 0,
                    "rejected": 0,
                    "last_success_at": NOW,
                    "stale_after_days": 90,
                }
            ],
        },
    )
    write_json(
        data_dir / "spam_domains_approved.json",
        {"description": "Reviewed domains", "approved": ["bad.example"]},
    )

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

    # One report resent under its id after the phone changed networks arrives
    # from a second reporter bucket. Counted twice, these four files would make
    # the number trend (4 reports, 4 reporters); counted once, they can't.
    resent = "3f1c9a52-7d4e-4b8a-9c1d-2e5f6a7b8c9d"
    for index, (bucket, reported_at, report_id) in enumerate(
        [
            (BUCKETS[0], TIMES[0], resent),
            (BUCKETS[1], TIMES[1], resent),
            (BUCKETS[2], TIMES[2], "a0b1c2d3-e4f5-4a6b-8c7d-9e0f1a2b3c4d"),
            (BUCKETS[3], TIMES[3], "b1c2d3e4-f5a6-4b7c-9d8e-0f1a2b3c4d5e"),
        ]
    ):
        write_report(data_dir, f"resent_{index}.json", "+13129870777", bucket, reported_at, report_id=report_id)


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

    # A feed with rows cleared nothing, so the flag must stay false whether or
    # not --allow-collapse was passed. Only an actually-empty approved run may
    # tell the client to drop its local rows.
    for name, payload in (
        ("hot_numbers.json", hot_numbers),
        ("hot_ranges.json", hot_ranges),
        ("spam_domains.json", spam_domains),
    ):
        if payload.get("cleared") is not False:
            raise AssertionError(f"{name} claimed cleared on a feed that has rows: {payload.get('cleared')}")

    if spam_domains["domains"] != ["bad.example"]:
        raise AssertionError(f"unexpected approved spam domains: {spam_domains['domains']}")
    review_domains = {candidate["domain"] for candidate in domain_review["candidates"]}
    if review_domains != {"unreviewed.example"}:
        raise AssertionError(f"unexpected domain review candidates: {review_domains}")


def assert_unknown_approval_shape_fails(data_dir: Path) -> None:
    seed_reports(data_dir)
    write_json(data_dir / "spam_domains_approved.json", {"domains": ["bad.example"]})
    result = run_script_result("extract_spam_domains.py", data_dir)
    if result.returncode == 0 or "'approved' array" not in result.stderr:
        raise AssertionError(f"unknown approval shape was accepted: {result}")
    if (data_dir / "spam_domains.json").exists():
        raise AssertionError("invalid approval shape published a spam domain feed")


def assert_merge_cleanup(data_dir: Path) -> None:
    reports_dir = data_dir / "reports"
    if reports_dir.exists() and list(reports_dir.glob("*.json")):
        raise AssertionError("merge script did not remove processed report files")

    merged = json.loads((data_dir / "spam_numbers.json").read_text(encoding="utf-8"))
    if merged["numbers"]:
        raise AssertionError(f"same-day community reports were shipped: {merged['numbers']}")
    pending = json.loads((data_dir / "community_pending.json").read_text(encoding="utf-8"))["numbers"]
    expected_counts = {
        "+12122340101": 5,
        "+12122340999": 2,
        "+12122340888": 1,
        "+13129870777": 3,
    }
    for number, count in expected_counts.items():
        if len(pending[number]["events"]) != count:
            raise AssertionError(f"pending evidence was not identity-deduped for {number}: {pending[number]}")

    snapshot = json.loads((data_dir / "source-snapshot.json").read_text(encoding="utf-8"))
    if "health" not in snapshot:
        raise AssertionError(f"source health was not attached to the pipeline snapshot: {snapshot}")
    if "+12122340101" in json.dumps(snapshot):
        raise AssertionError("source health snapshot leaked a raw phone number")


def assert_community_promotion(data_dir: Path) -> None:
    write_json(
        data_dir / "spam_numbers.json",
        {"version": 1, "updated": BASE_DAY, "sources": ["community_reports"], "numbers": [], "prefixes": []},
    )
    legacy_number = "+12122340671"
    bucket_number = "+12122340672"
    old_number = "+12122340673"
    mixed_batch_number = "+12122340674"

    write_report(data_dir, "first.json", legacy_number, None, TIMES[0])
    run_drain(data_dir)
    assert reports_for(data_dir, legacy_number) == 0, "one anonymous report was shipped"
    pending = json.loads((data_dir / "community_pending.json").read_text(encoding="utf-8"))["numbers"]
    assert len(pending[legacy_number]["events"]) == 1, "first report was not retained"

    write_report(data_dir, "same_day.json", legacy_number, None, TIMES[2])
    run_drain(data_dir)
    assert reports_for(data_dir, legacy_number) == 0, "two same-day reports were shipped"

    write_report(data_dir, "next_day.json", legacy_number, None, f"{TODAY}T00:00:00+00:00")
    run_drain(data_dir)
    assert reports_for(data_dir, legacy_number) == 3, "two UTC days did not promote bucketless reports"

    write_report(data_dir, "legacy_bucket_first.json", legacy_number, BUCKETS[0], f"{TODAY}T00:01:00+00:00")
    run_drain(data_dir)
    assert reports_for(data_dir, legacy_number) == 0, "one bucket left a bucketless promotion shipped"
    write_report(data_dir, "legacy_bucket_second.json", legacy_number, BUCKETS[1], f"{TODAY}T00:02:00+00:00")
    run_drain(data_dir)
    assert reports_for(data_dir, legacy_number) == 0, "two buckets restored a demoted row"
    write_report(data_dir, "legacy_bucket_third.json", legacy_number, BUCKETS[2], f"{TODAY}T00:03:00+00:00")
    run_drain(data_dir)
    assert reports_for(data_dir, legacy_number) == 6, "three buckets did not restore a demoted row"

    write_report(data_dir, "mixed_batch_first.json", mixed_batch_number, None, TIMES[0])
    write_report(data_dir, "mixed_batch_second.json", mixed_batch_number, None, f"{TODAY}T00:00:00+00:00")
    write_report(data_dir, "mixed_batch_bucket.json", mixed_batch_number, BUCKETS[0], f"{TODAY}T00:02:00+00:00")
    run_drain(data_dir)
    assert reports_for(data_dir, mixed_batch_number) == 0, "a mixed same-drain batch shipped with one bucket"

    write_report(data_dir, "bucket_first.json", bucket_number, BUCKETS[0], TIMES[0])
    run_drain(data_dir)
    write_report(data_dir, "bucket_repeat.json", bucket_number, BUCKETS[0], TIMES[2])
    run_drain(data_dir)
    assert pending_reports_for(data_dir, bucket_number) == 1, "a bucket counted twice across drains"
    write_report(data_dir, "bucket_second.json", bucket_number, BUCKETS[1], f"{TODAY}T00:00:00+00:00")
    run_drain(data_dir)
    assert reports_for(data_dir, bucket_number) == 0, "two buckets were enough to ship"
    write_report(data_dir, "bucket_third.json", bucket_number, BUCKETS[2], f"{TODAY}T00:02:00+00:00")
    run_drain(data_dir)
    assert reports_for(data_dir, bucket_number) == 3, "three buckets across two days did not promote"

    # The pending ledger keeps only 30 days of events, so the oldest report of a
    # three-bucket promotion ages out first and the rest fall short of quorum.
    # That used to demote the row on the next merge, queue or no queue.
    pending_path = data_dir / "community_pending.json"
    pending = json.loads(pending_path.read_text(encoding="utf-8"))
    aged = (datetime.now(timezone.utc) - timedelta(days=40)).date().isoformat()
    pending["numbers"][bucket_number]["events"][0]["day"] = aged
    write_json(pending_path, pending)
    run_script("merge_community_reports.py", data_dir)
    assert reports_for(data_dir, bucket_number) == 3, "expiry demoted a row three buckets had promoted"
    # Once every event has aged out, one new bucketed report must not demote it either.
    pending = json.loads(pending_path.read_text(encoding="utf-8"))
    for event in pending["numbers"][bucket_number]["events"]:
        event["day"] = aged
    write_json(pending_path, pending)
    write_report(data_dir, "bucket_late.json", bucket_number, BUCKETS[3], f"{TODAY}T00:04:00+00:00")
    run_drain(data_dir)
    assert reports_for(data_dir, bucket_number) == 4, "one new bucket demoted a row three buckets had promoted"

    # The 30-day cutoff is for promoting new rows. A row already in the database
    # (here FCC-backed) takes a late report instead of having it thrown away.
    fcc_number = "+12122340675"
    database = json.loads((data_dir / "spam_numbers.json").read_text(encoding="utf-8"))
    database["numbers"].append(
        {"number": fcc_number, "type": "robocall", "reports": 5, "first_seen": BASE_DAY,
         "last_seen": BASE_DAY, "description": "FCC complaints", "sources": ["fcc_complaints"],
         "evidence": [{"source_id": "fcc_complaints", "evidence_type": "complaint"}]}
    )
    write_json(data_dir / "spam_numbers.json", database)
    late = (datetime.now(timezone.utc) - timedelta(days=40)).replace(microsecond=0).isoformat()
    write_report(data_dir, "late_for_fcc_row.json", fcc_number, None, late)
    run_drain(data_dir)
    assert reports_for(data_dir, fcc_number) == 6, "a late report for a row already in the database was thrown away"


    database = json.loads((data_dir / "spam_numbers.json").read_text(encoding="utf-8"))
    database["numbers"].append(
        {"number": old_number, "type": "spam", "reports": 1, "first_seen": BASE_DAY,
         "last_seen": BASE_DAY, "description": "Community reported", "sources": ["community"],
         "evidence": [{"source_id": "github_database", "evidence_type": "aggregate_database"}]}
    )
    write_json(data_dir / "spam_numbers.json", database)
    run_script("merge_community_reports.py", data_dir)
    assert reports_for(data_dir, old_number) == 0, "a legacy single-report row stayed in the shipped database"
    assert reports_for(data_dir, legacy_number) == 6, "a promoted row was demoted on the next merge"

    pending_path = data_dir / "community_pending.json"
    pending = json.loads(pending_path.read_text(encoding="utf-8"))
    expired = (datetime.now(timezone.utc) - timedelta(days=31)).date().isoformat()
    pending["numbers"][old_number]["events"] = [
        {"key": "file:old.json", "day": expired, "bucket": "", "count": 1}
    ]
    write_json(pending_path, pending)
    run_script("merge_community_reports.py", data_dir)
    pending = json.loads(pending_path.read_text(encoding="utf-8"))["numbers"]
    assert old_number not in pending, "a pending report survived past 30 days"

    pending_path.write_text("not json", encoding="utf-8")
    write_report(data_dir, "after_corruption.json", old_number, None, f"{TODAY}T00:05:00+00:00")
    run_script("extract_spam_domains.py", data_dir, ["--allow-collapse"])
    run_script("generate_hot_list.py", data_dir, ["--allow-collapse"])
    stopped = run_script_result("merge_community_reports.py", data_dir)
    assert stopped.returncode != 0, "a corrupt pending ledger was silently treated as empty"
    assert (data_dir / "reports" / "after_corruption.json").exists(), "a failed merge consumed its input"


def assert_not_spam_requires_review(data_dir: Path) -> None:
    community = "+14152340101"
    authoritative = "+14152340102"
    legacy_authoritative = "+14152340103"
    write_json(
        data_dir / "spam_numbers.json",
        {
            "version": 7,
            "updated": BASE_DAY,
            "numbers": [
                {
                    "number": community,
                    "reports": 2,
                    "type": "spam",
                    "description": "Community reported",
                    "sources": ["community"],
                    "first_seen": BASE_DAY,
                    "last_seen": TODAY,
                },
                {
                    "number": authoritative,
                    "reports": 1,
                    "type": "spam",
                    "description": "Community reported",
                    "sources": ["ftc"],
                    "first_seen": BASE_DAY,
                    "last_seen": TODAY,
                },
                {
                    "number": legacy_authoritative,
                    "reports": 1,
                    "type": "spam",
                    "description": "Imported complaint",
                    "first_seen": BASE_DAY,
                    "last_seen": TODAY,
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

    run_script("extract_spam_domains.py", data_dir, ["--allow-collapse"])
    run_script("generate_hot_list.py", data_dir, ["--allow-collapse"])
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

    review["candidates"][0]["approved"] = True
    write_json(data_dir / "not_spam_review.json", review)
    run_script("merge_community_reports.py", data_dir, ["--apply-reviewed-corrections"])
    corrected = json.loads((data_dir / "spam_numbers.json").read_text(encoding="utf-8"))
    if any(entry.get("number") == community for entry in corrected["numbers"]):
        raise AssertionError("approved community false-positive correction did not remove the row")
    corrected_numbers = {entry["number"] for entry in corrected["numbers"]}
    if authoritative not in corrected_numbers or legacy_authoritative not in corrected_numbers:
        raise AssertionError("reviewed correction changed an authoritative row")


def assert_collapse_guard(data_dir: Path) -> None:
    """A source outage must not replace healthy derived feeds with empties."""
    write_json(
        data_dir / "spam_numbers.json",
        {"version": 1, "numbers": [], "prefixes": []},
    )
    previous_outputs = {
        "hot_numbers.json": {"count": 3, "numbers": [{"number": "+12125550101"}] * 3},
        "hot_ranges.json": {"count": 1, "ranges": [{"npanxx": "212555"}]},
        "spam_domains.json": {"count": 2, "domains": ["bad.example", "worse.example"]},
    }
    before = {}
    for name, payload in previous_outputs.items():
        path = data_dir / name
        write_json(path, payload)
        before[name] = path.read_bytes()

    hot_result = run_script_result("generate_hot_list.py", data_dir)
    if hot_result.returncode == 0:
        raise AssertionError("hot generator published a collapsed feed without --allow-collapse")
    domains_result = run_script_result("extract_spam_domains.py", data_dir)
    if domains_result.returncode == 0:
        raise AssertionError("domain generator published a collapsed feed without --allow-collapse")
    for name, original in before.items():
        if (data_dir / name).read_bytes() != original:
            raise AssertionError(f"collapse guard replaced {name} before explicit approval")

    run_script("generate_hot_list.py", data_dir, ["--allow-collapse"])
    run_script("extract_spam_domains.py", data_dir, ["--allow-collapse"])

    # --allow-collapse forces the guard for every feed the run writes, but it
    # is not an assertion about any of them. Approving a collapse of one feed
    # must never tell devices to delete rows for a feed nobody mentioned, so
    # the flag alone publishes cleared=false and devices keep what they have.
    for name, item_key in (
        ("hot_numbers.json", "numbers"),
        ("hot_ranges.json", "ranges"),
        ("spam_domains.json", "domains"),
    ):
        payload = json.loads((data_dir / name).read_text(encoding="utf-8"))
        if payload.get(item_key):
            raise AssertionError(f"{name} was expected to be empty after the approved collapse")
        if payload.get("cleared") is not False:
            raise AssertionError(f"{name} claimed a deliberate clear from --allow-collapse alone")

    # Naming the feed is what asserts the clear, and it is per feed.
    run_script("generate_hot_list.py", data_dir, ["--allow-collapse", "--cleared", "ranges"])
    run_script("extract_spam_domains.py", data_dir, ["--allow-collapse", "--cleared", "domains"])
    hot_numbers = json.loads((data_dir / "hot_numbers.json").read_text(encoding="utf-8"))
    hot_ranges = json.loads((data_dir / "hot_ranges.json").read_text(encoding="utf-8"))
    spam_domains = json.loads((data_dir / "spam_domains.json").read_text(encoding="utf-8"))
    if hot_ranges.get("cleared") is not True or spam_domains.get("cleared") is not True:
        raise AssertionError("a feed named in --cleared must publish cleared=true")
    if hot_numbers.get("cleared") is not False:
        raise AssertionError("a feed absent from --cleared must not claim a deliberate clear")

    # A typo must fail loudly rather than quietly approving nothing.
    typo = run_script_result(
        "generate_hot_list.py", data_dir, ["--allow-collapse", "--cleared", "rangez"]
    )
    if typo.returncode == 0:
        raise AssertionError("an unknown --cleared feed name was accepted")


def assert_merge_requires_current_derived_outputs(data_dir: Path) -> None:
    """Merge must not consume reports until all derived feeds share its queue."""
    seed_reports(data_dir)
    result = run_script_result("merge_community_reports.py", data_dir)
    if result.returncode == 0:
        raise AssertionError("merge consumed reports before derived feeds were generated")
    if not list((data_dir / "reports").glob("*.json")):
        raise AssertionError("merge removed reports after refusing stale derived feeds")

    run_script("extract_spam_domains.py", data_dir)
    run_script("generate_hot_list.py", data_dir)
    run_script("merge_community_reports.py", data_dir)


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

def assert_external_source_parsers() -> None:
    """Exercise the optional international bulk/range feeds without network."""
    import importlib.util

    spec = importlib.util.spec_from_file_location(
        "import_all_sources", SCRIPTS_DIR / "import_all_sources.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    class FakeResponse:
        def __init__(self, payload: dict, status_code: int = 200, text: str | None = None):
            self._payload = payload
            self.status_code = status_code
            self.text = text if text is not None else json.dumps(payload)

        def raise_for_status(self):
            if self.status_code >= 400:
                raise RuntimeError(f"HTTP {self.status_code}")

        def json(self):
            return self._payload

    original_get = module.requests.get
    try:
        def fake_get(url, **kwargs):
            if url == "https://api.ftc.gov/v0/dnc-complaints":
                return FakeResponse({}, status_code=429)
            if url == module.PHONEBLOCK_BLOCKLIST_URL:
                return FakeResponse(
                    {
                        "version": 42,
                        "numbers": [
                            {
                                "phone": "+49123456789",
                                "rating": "G_FRAUD",
                                "votes": 20,
                                "lastActivity": 1_735_689_600_000,
                            },
                            {"phone": "+12125561234", "rating": "E_ADVERTISING", "votes": 4},
                            {"phone": "+49111111111", "rating": "G_FRAUD", "votes": 0},
                        ],
                    }
                )
            if url.startswith("https://opendata.fcc.gov/resource/"):
                return FakeResponse(
                    [
                        {
                            "caller_id_number": "+12125561234",
                            "advertiser_business_phone_number": "+13105561234",
                            "issue": "Telemarketing",
                            "issue_date": "2026-07-31T00:00:00.000",
                            ":created_at": "2026-08-01T05:06:29.585Z",
                        }
                    ]
                )
            if url == "https://nomorobo.example/irs.csv":
                return FakeResponse(
                    {},
                    text="phone,reason\n+34912345678,IRS callback scam\n+34912345678,repeat report\n",
                )
            return FakeResponse(
                {
                    "version": "2026-08-01T00:00:00+00:00",
                    "blocked_numbers_count": 17_000_000,
                    "patterns": [
                        {"action": "block", "name": "ARCEP", "pattern": "33162######"},
                        {"action": "allow", "name": "ignore", "pattern": "33163######"},
                        {"action": "block", "name": "operator", "pattern": "332688#####"},
                        {"action": "block", "name": "bad", "pattern": "abc######"},
                    ],
                }
            )

        module.requests.get = fake_get
        original_sleep = module.time.sleep
        module.time.sleep = lambda _seconds: None
        if module.fetch_ftc(max_records=1):
            raise AssertionError("FTC rate-limit handling must fail closed")
        phoneblock = module.fetch_phoneblock(limit=10)
        if {row["number"] for row in phoneblock} != {"+49123456789", "+12125561234"}:
            raise AssertionError(f"PhoneBlock parsing regressed: {phoneblock}")
        if not any(row["type"] == "scam" for row in phoneblock):
            raise AssertionError("PhoneBlock fraud rating did not map to scam")

        fcc = module.fetch_fcc(max_records=1)
        fcc_numbers = {row["number"]: row for row in fcc}
        if set(fcc_numbers) != {"+12125561234", "+13105561234"}:
            raise AssertionError(f"FCC dual-number parsing regressed: {fcc}")
        if not all("FCC" in row["description"] for row in fcc_numbers.values()):
            raise AssertionError(f"FCC provenance missing: {fcc}")

        nomorobo = module.fetch_nomorobo_irs("https://nomorobo.example/irs.csv")
        if len(nomorobo) != 1 or nomorobo[0]["number"] != "+34912345678":
            raise AssertionError(f"Nomorobo parsing regressed: {nomorobo}")
        if nomorobo[0]["reports"] != 4 or nomorobo[0]["type"] != "scam":
            raise AssertionError(f"Nomorobo confidence mapping regressed: {nomorobo}")
        if module.fetch_nomorobo_irs("http://nomorobo.example/irs.csv"):
            raise AssertionError("Nomorobo HTTP feed must fail closed")

        prefixes = module.fetch_saracroche_prefixes()
        prefix_values = {row["prefix"] for row in prefixes}
        if prefix_values != {"+33162", "+332688"}:
            raise AssertionError(f"Saracroche parsing regressed: {prefixes}")

        module.requests.get = lambda *args, **kwargs: FakeResponse({}, status_code=401)
        if module.fetch_phoneblock(limit=5):
            raise AssertionError("PhoneBlock auth failure must fail closed")
    finally:
        module.requests.get = original_get
        module.time.sleep = original_sleep


def assert_reporters_count_per_device_with_group_cap(data_dir: Path) -> None:
    """Phones sharing a carrier /48 are separate reporters; a rotating /48 counts twice at most."""
    carrier_a, carrier_b, delegated = "00000000000a0001", "00000000000a0002", "00000000000a0003"
    carrier_number, delegated_number = "+12122340201", "+12122340202"
    # Four phones on two carrier /48s, two per /48.
    for index, (group, reported_at) in enumerate(zip([carrier_a, carrier_a, carrier_b, carrier_b], TIMES), start=1):
        write_report(
            data_dir,
            f"carrier-{index}.json",
            carrier_number,
            group,
            reported_at,
            device=f"00000000000d{index:04x}",
        )
    # One delegated /48 rotating through five /64s.
    for index, reported_at in enumerate(TIMES, start=1):
        write_report(
            data_dir,
            f"delegated-{index}.json",
            delegated_number,
            delegated,
            reported_at,
            device=f"00000000000e{index:04x}",
        )

    # An empty feed is allowed here so a counting regression reports itself below
    # instead of tripping the collapse guard.
    run_script("generate_hot_list.py", data_dir, ["--allow-collapse"])
    hot = {entry["number"]: entry for entry in json.loads((data_dir / "hot_numbers.json").read_text(encoding="utf-8"))["numbers"]}
    carrier = hot.get(carrier_number)
    if carrier is None or carrier["distinct_reporters"] != 4 or carrier["reports"] != 4:
        raise AssertionError(f"four phones on two carrier /48s should be four reporters: {carrier}")
    if delegated_number in hot:
        raise AssertionError(f"one /48 rotating /64s must count as two reporters at most: {hot[delegated_number]}")


def run_drain(data_dir: Path) -> None:
    # Each drain here leaves the derived feeds empty, which their collapse
    # guards refuse to publish twice without being told.
    run_script("extract_spam_domains.py", data_dir, ["--allow-collapse"])
    run_script("generate_hot_list.py", data_dir, ["--allow-collapse"])
    run_script("merge_community_reports.py", data_dir)


def reports_for(data_dir: Path, number: str) -> int:
    database = json.loads((data_dir / "spam_numbers.json").read_text(encoding="utf-8"))
    return next((row["reports"] for row in database["numbers"] if row["number"] == number), 0)


def pending_reports_for(data_dir: Path, number: str) -> int:
    ledger = json.loads((data_dir / "community_pending.json").read_text(encoding="utf-8"))["numbers"]
    return sum(event["count"] for event in ledger[number]["events"])


def assert_resend_across_drains_counts_once(data_dir: Path) -> None:
    """A resend that lands after its original was merged and deleted counts
    once. The queue alone can't see that, so merged ids are kept a while."""
    write_json(
        data_dir / "spam_numbers.json",
        {"version": 1, "updated": "2026-06-11", "sources": ["community_reports"], "numbers": [], "prefixes": []},
    )
    number = "+12129460188"
    report_id = "3f2c1a9e-8b7d-4c6e-9f10-2a3b4c5d6e7f"
    write_report(data_dir, "original.json", number, BUCKETS[0], TIMES[0], report_type="spam", report_id=report_id)
    run_drain(data_dir)
    assert pending_reports_for(data_dir, number) == 1

    write_report(data_dir, "resend.json", number, BUCKETS[1], TIMES[2], report_type="spam", report_id=report_id)
    run_drain(data_dir)
    assert pending_reports_for(data_dir, number) == 1, "a resend counted again in a later drain"
    assert not (data_dir / "reports" / "resend.json").exists(), "the resend was left in the queue"

    other_id = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
    write_report(data_dir, "other.json", number, BUCKETS[2], TIMES[3], report_type="spam", report_id=other_id)
    run_drain(data_dir)
    assert pending_reports_for(data_dir, number) == 2, "a different report of the same number must still count"
    ledger = json.loads((data_dir / "merged_report_ids.json").read_text(encoding="utf-8"))["ids"]
    assert {report_id, other_id} <= set(ledger), ledger

    (data_dir / "merged_report_ids.json").write_text("not json", encoding="utf-8")
    write_report(data_dir, "later.json", number, BUCKETS[3], NOW, report_type="spam")
    run_script("extract_spam_domains.py", data_dir, ["--allow-collapse"])
    run_script("generate_hot_list.py", data_dir, ["--allow-collapse"])
    stopped = run_script_result("merge_community_reports.py", data_dir)
    assert stopped.returncode != 0, "an unreadable ledger must stop the merge"
    assert (data_dir / "reports" / "later.json").exists(), "a stopped merge must leave the queue alone"
    assert pending_reports_for(data_dir, number) == 2


def assert_ledger_retention(data_dir: Path) -> None:
    """Past, future, and non-date entries in the ledger are handled.

    Past entries older than the retention window are dropped on the next
    drain. Non-date strings and future dates never expire under plain
    string comparison, so the fix validates every day value.
    """
    from datetime import datetime as dt
    number = "+12124567890"
    seed_reports(data_dir)
    ledger_path = data_dir / "merged_report_ids.json"
    # merge_community_reports uses datetime.now(), not CALLSHIELD_NOW,
    # so "today" is the real system date. The good entry must be within
    # the 14-day retention window of today.
    real_today = dt.now().strftime("%Y-%m-%d")
    old_uuid = "00000000-0000-4000-8000-000000000001"
    garbage_uuid = "00000000-0000-4000-8000-000000000002"
    future_uuid = "00000000-0000-4000-8000-000000000003"
    good_uuid = "00000000-0000-4000-8000-000000000004"
    write_json(ledger_path, {
        "retention_days": 14,
        "ids": {
            old_uuid: "2020-01-01",
            garbage_uuid: "zzzz",
            future_uuid: "2099-12-31",
            good_uuid: real_today,
        },
    })
    write_report(data_dir, "one.json", number, BUCKETS[0], NOW)
    run_script("extract_spam_domains.py", data_dir)
    run_script("generate_hot_list.py", data_dir)
    run_script("merge_community_reports.py", data_dir)

    ledger = json.loads(ledger_path.read_text(encoding="utf-8"))["ids"]
    assert old_uuid not in ledger, "expired entry was kept"
    assert garbage_uuid not in ledger, "non-date entry was kept"
    assert future_uuid not in ledger, "future-date entry was kept"
    assert good_uuid in ledger, "recent valid entry was dropped"


def assert_not_spam_id_not_recorded(data_dir: Path) -> None:
    """A not_spam vote's report id must not be remembered as merged.

    The id was previously recorded before the spam/not_spam branch, so a
    quarantined report that later re-entered the queue as spam would be
    silently skipped as "already merged".
    """
    number = "+12125553333"
    vote_id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    seed_reports(data_dir)
    # Ensure the number exists as a community row so the vote has a target.
    write_report(data_dir, "spam.json", number, BUCKETS[0], TIMES[0], report_type="spam")
    run_script("extract_spam_domains.py", data_dir)
    run_script("generate_hot_list.py", data_dir)
    run_script("merge_community_reports.py", data_dir)

    # Now submit a not_spam vote with a report_id.
    write_report(data_dir, "vote.json", number, BUCKETS[1], TIMES[1], report_type="not_spam", report_id=vote_id)
    run_script("extract_spam_domains.py", data_dir, ["--allow-collapse"])
    run_script("generate_hot_list.py", data_dir, ["--allow-collapse"])
    run_script("merge_community_reports.py", data_dir)

    ledger_path = data_dir / "merged_report_ids.json"
    if ledger_path.exists():
        ledger = json.loads(ledger_path.read_text(encoding="utf-8"))["ids"]
        assert vote_id not in ledger, f"not_spam vote id was recorded: {ledger}"


def assert_fixed_clock_is_for_tests_only() -> None:
    """A feed stamped by CALLSHIELD_NOW and published would be refused by every
    device as a replay, or block every later feed until its time passed. So the
    repository's own data directory refuses the fixed clock. Checked in-process,
    so nothing here can write to the real data directory."""
    sys.path.insert(0, str(SCRIPTS_DIR))
    import generate_hot_list

    original_now = os.environ.get("CALLSHIELD_NOW")
    original_dir = generate_hot_list.DATA_DIR
    os.environ["CALLSHIELD_NOW"] = NOW
    try:
        generate_hot_list.DATA_DIR = ROOT / "data"
        try:
            generate_hot_list.current_time_utc()
        except SystemExit:
            pass
        else:
            raise AssertionError("CALLSHIELD_NOW was honoured for the repository's own data directory")
        with tempfile.TemporaryDirectory() as tmp:
            generate_hot_list.DATA_DIR = Path(tmp)
            stamp = generate_hot_list.current_time_utc().isoformat()
            assert stamp == NOW, f"a scratch data directory keeps the fixed clock, got {stamp}"
    finally:
        generate_hot_list.DATA_DIR = original_dir
        if original_now is None:
            os.environ.pop("CALLSHIELD_NOW", None)
        else:
            os.environ["CALLSHIELD_NOW"] = original_now


def main() -> None:
    assert_fixed_clock_is_for_tests_only()

    with tempfile.TemporaryDirectory() as tmp:
        assert_unknown_approval_shape_fails(Path(tmp) / "data")

    with tempfile.TemporaryDirectory() as tmp:
        assert_collapse_guard(Path(tmp) / "data")

    with tempfile.TemporaryDirectory() as tmp:
        assert_merge_requires_current_derived_outputs(Path(tmp) / "data")

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
        assert_external_source_parsers()

    with tempfile.TemporaryDirectory() as tmp:
        assert_community_promotion(Path(tmp) / "data")

    with tempfile.TemporaryDirectory() as tmp:
        assert_not_spam_requires_review(Path(tmp) / "data")

    with tempfile.TemporaryDirectory() as tmp:
        assert_resend_across_drains_counts_once(Path(tmp) / "data")

    with tempfile.TemporaryDirectory() as tmp:
        assert_ledger_retention(Path(tmp) / "data")

    with tempfile.TemporaryDirectory() as tmp:
        assert_not_spam_id_not_recorded(Path(tmp) / "data")

    with tempfile.TemporaryDirectory() as tmp:
        assert_reporters_count_per_device_with_group_cap(Path(tmp) / "data")


if __name__ == "__main__":
    main()
