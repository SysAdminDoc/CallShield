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
    midnight_number = "+12122340676"
    rotating_number = "+12122340677"
    ledger_number = "+12122340678"
    ledger_spread_number = "+12122340679"
    late_night_number = "+12122340681"

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
    assert reports_for(data_dir, legacy_number) == 0, "bucketless reports 20 hours apart were shipped"
    write_report(data_dir, "a_day_later.json", legacy_number, None, f"{TODAY}T04:00:00+00:00")
    run_drain(data_dir)
    assert reports_for(data_dir, legacy_number) == 4, "bucketless reports 24 hours apart did not promote"

    # Two UTC days, six minutes apart.
    write_report(data_dir, "before_midnight.json", midnight_number, None, f"{BASE_DAY}T23:57:00+00:00")
    write_report(data_dir, "after_midnight.json", midnight_number, None, f"{TODAY}T00:03:00+00:00")
    run_drain(data_dir)
    assert reports_for(data_dir, midnight_number) == 0, "reports either side of midnight were shipped"

    # Ledger events recorded before events carried a time: only whole days count.
    pending_path = data_dir / "community_pending.json"
    pending = json.loads(pending_path.read_text(encoding="utf-8"))
    two_days_ago = (datetime.now(timezone.utc) - timedelta(days=2)).date().isoformat()
    for number, first_day in ((ledger_number, BASE_DAY), (ledger_spread_number, two_days_ago)):
        entry = {"number": number, "type": "phishing", "reports": 0, "first_seen": first_day, "last_seen": TODAY,
                 "description": "Community reported", "sources": ["community"]}
        pending["numbers"][number] = {"published": False, "entry": entry, "events": [
            {"key": f"file:{number}_a.json", "day": first_day, "bucket": "", "count": 1},
        ]}
    write_json(pending_path, pending)
    write_report(data_dir, "ledger_next_day.json", ledger_number, None, f"{TODAY}T00:04:00+00:00")
    write_report(data_dir, "ledger_two_days.json", ledger_spread_number, None, f"{TODAY}T00:04:00+00:00")
    run_drain(data_dir)
    assert reports_for(data_dir, ledger_number) == 0, "a timeless event on the day before shipped a row"
    assert reports_for(data_dir, ledger_spread_number) == 2, "a timeless event two days before did not promote"

    write_report(data_dir, "legacy_bucket_first.json", legacy_number, BUCKETS[0], f"{TODAY}T00:01:00+00:00")
    run_drain(data_dir)
    assert reports_for(data_dir, legacy_number) == 0, "one bucket left a bucketless promotion shipped"
    write_report(data_dir, "legacy_bucket_second.json", legacy_number, BUCKETS[1], f"{TODAY}T00:02:00+00:00")
    run_drain(data_dir)
    assert reports_for(data_dir, legacy_number) == 0, "two buckets restored a demoted row"
    write_report(data_dir, "legacy_bucket_third.json", legacy_number, BUCKETS[2], f"{TODAY}T00:03:00+00:00")
    run_drain(data_dir)
    assert reports_for(data_dir, legacy_number) == 7, "three buckets did not restore a demoted row"

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
    # Buckets rotate at UTC midnight, so BUCKETS[0] may be either of today's reporters.
    assert reports_for(data_dir, bucket_number) == 0, "two reporters with three daily buckets were enough to ship"
    write_report(data_dir, "bucket_fourth.json", bucket_number, BUCKETS[3], f"{TODAY}T00:03:00+00:00")
    run_drain(data_dir)
    assert reports_for(data_dir, bucket_number) == 0, "three reporters shipped a row within 24 hours"
    write_report(data_dir, "bucket_fifth.json", bucket_number, BUCKETS[5], f"{TODAY}T04:00:00+00:00")
    run_drain(data_dir)
    assert reports_for(data_dir, bucket_number) == 5, "three same-day reporters 24 hours apart did not promote"

    # Three reporters just before midnight and one just after: two UTC days, eight minutes.
    for index, minute in enumerate(("23:55", "23:56", "23:57")):
        write_report(data_dir, f"late_{index}.json", late_night_number, BUCKETS[index], f"{BASE_DAY}T{minute}:00+00:00")
    write_report(data_dir, "early.json", late_night_number, BUCKETS[3], f"{TODAY}T00:03:00+00:00")
    run_drain(data_dir)
    assert reports_for(data_dir, late_night_number) == 0, "reporters eight minutes apart across midnight were shipped"

    # One reporter on three days shows three buckets.
    three_days_ago = (datetime.now(timezone.utc) - timedelta(days=2)).date().isoformat()
    for index, reported_at in enumerate((f"{three_days_ago}T10:00:00+00:00", TIMES[0], f"{TODAY}T00:04:00+00:00")):
        write_report(data_dir, f"rotating_{index}.json", rotating_number, BUCKETS[index], reported_at)
        run_drain(data_dir)
    assert reports_for(data_dir, rotating_number) == 0, "one reporter's daily buckets on three days were shipped"

    # The pending ledger keeps only 30 days of events, so the oldest report of a
    # three-bucket promotion ages out first and the rest fall short of quorum.
    # That used to demote the row on the next merge, queue or no queue.
    pending_path = data_dir / "community_pending.json"
    pending = json.loads(pending_path.read_text(encoding="utf-8"))
    aged = (datetime.now(timezone.utc) - timedelta(days=40)).date().isoformat()
    pending["numbers"][bucket_number]["events"][0]["day"] = aged
    write_json(pending_path, pending)
    run_script("merge_community_reports.py", data_dir)
    assert reports_for(data_dir, bucket_number) == 5, "expiry demoted a row three reporters had promoted"
    # Once every event has aged out, one new bucketed report must not demote it either.
    pending = json.loads(pending_path.read_text(encoding="utf-8"))
    for event in pending["numbers"][bucket_number]["events"]:
        event["day"] = aged
    write_json(pending_path, pending)
    write_report(data_dir, "bucket_late.json", bucket_number, BUCKETS[4], f"{TODAY}T00:04:00+00:00")
    run_drain(data_dir)
    assert reports_for(data_dir, bucket_number) == 6, "one new bucket demoted a row three reporters had promoted"

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
    # A pre-ledger row whose first report is older than the 30-day window.
    long_span_number = "+12122340680"
    days_ago = lambda days: (datetime.now(timezone.utc) - timedelta(days=days)).date().isoformat()  # noqa: E731
    database["numbers"].append(
        {"number": long_span_number, "type": "spam", "reports": 4, "first_seen": days_ago(173),
         "last_seen": days_ago(21), "description": "Community reported", "sources": ["community"],
         "evidence": [{"source_id": "github_database", "evidence_type": "aggregate_database"}]}
    )
    write_json(data_dir / "spam_numbers.json", database)
    run_script("merge_community_reports.py", data_dir)
    assert reports_for(data_dir, old_number) == 0, "a legacy single-report row stayed in the shipped database"
    assert reports_for(data_dir, long_span_number) == 4, "a legacy row lost its first report to the 30-day window"
    assert reports_for(data_dir, legacy_number) == 7, "a promoted row was demoted on the next merge"

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
    run_script("extract_spam_domains.py", data_dir, ["--allow-collapse", "--cleared", "domains"])
    run_script("generate_hot_list.py", data_dir, ["--allow-collapse", "--cleared", "numbers,ranges"])
    stopped = run_script_result("merge_community_reports.py", data_dir)
    assert stopped.returncode != 0, "a corrupt pending ledger was silently treated as empty"
    assert (data_dir / "reports" / "after_corruption.json").exists(), "a failed merge consumed its input"


def assert_not_spam_requires_review(data_dir: Path) -> None:
    community = "+14152340101"
    authoritative = "+14152340102"
    legacy_authoritative = "+14152340103"
    # Two days apart: a pre-ledger row's dates carry no time, so consecutive
    # days can't show its reports were 24 hours apart.
    first_seen = (datetime.now(timezone.utc) - timedelta(days=2)).date().isoformat()
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
                    "first_seen": first_seen,
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

    run_script("extract_spam_domains.py", data_dir, ["--allow-collapse", "--cleared", "domains"])
    run_script("generate_hot_list.py", data_dir, ["--allow-collapse", "--cleared", "numbers,ranges"])
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

    # --allow-collapse forces the guard for every feed the run writes, but it
    # is not an assertion about any of them, and an empty feed without
    # cleared=true is what phones read as an outage: HotListSyncWorker retries
    # every run and Protection test warns. 54589966 and 19bde793 shipped that
    # state to every device, so the flag alone must write nothing.
    for script in ("generate_hot_list.py", "extract_spam_domains.py"):
        if run_script_result(script, data_dir, ["--allow-collapse"]).returncode == 0:
            raise AssertionError(f"{script} published an empty feed without cleared=true")
    # Naming one feed never clears another: the unnamed empty numbers feed
    # stops the run before either hot file is replaced.
    if run_script_result("generate_hot_list.py", data_dir, ["--allow-collapse", "--cleared", "ranges"]).returncode == 0:
        raise AssertionError("--cleared ranges also published an empty numbers feed")
    for name, original in before.items():
        if (data_dir / name).read_bytes() != original:
            raise AssertionError(f"a refused empty publish still replaced {name}")

    # Naming the feed is what asserts the clear, and it is per feed.
    run_script("generate_hot_list.py", data_dir, ["--allow-collapse", "--cleared", "numbers,ranges"])
    run_script("extract_spam_domains.py", data_dir, ["--allow-collapse", "--cleared", "domains"])
    for name, item_key in (
        ("hot_numbers.json", "numbers"),
        ("hot_ranges.json", "ranges"),
        ("spam_domains.json", "domains"),
    ):
        payload = json.loads((data_dir / name).read_text(encoding="utf-8"))
        if payload.get(item_key):
            raise AssertionError(f"{name} was expected to be empty after the approved clear")
        if payload.get("cleared") is not True:
            raise AssertionError(f"{name} named in --cleared did not publish cleared=true")

    # A quiet day after a clear stays cleared without naming the feed again.
    run_script("generate_hot_list.py", data_dir, ["--allow-collapse"])
    run_script("extract_spam_domains.py", data_dir, ["--allow-collapse"])
    for name in ("hot_numbers.json", "hot_ranges.json", "spam_domains.json"):
        if json.loads((data_dir / name).read_text(encoding="utf-8")).get("cleared") is not True:
            raise AssertionError(f"{name} lost its clear on the next quiet run")

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
    # instead of tripping the collapse guard. Naming a feed that has rows in
    # --cleared changes nothing; its flag stays false.
    run_script("generate_hot_list.py", data_dir, ["--allow-collapse", "--cleared", "numbers,ranges"])
    hot = {entry["number"]: entry for entry in json.loads((data_dir / "hot_numbers.json").read_text(encoding="utf-8"))["numbers"]}
    carrier = hot.get(carrier_number)
    if carrier is None or carrier["distinct_reporters"] != 4 or carrier["reports"] != 4:
        raise AssertionError(f"four phones on two carrier /48s should be four reporters: {carrier}")
    if delegated_number in hot:
        raise AssertionError(f"one /48 rotating /64s must count as two reporters at most: {hot[delegated_number]}")


def assert_reporters_count_on_one_utc_day(data_dir: Path) -> None:
    """The Worker's buckets change at UTC midnight, so one reporter either side of it counts once."""
    previous_day = (datetime.fromisoformat(BASE_DAY) - timedelta(days=1)).date().isoformat()
    straddle = [f"{previous_day}T22:00:00+00:00", f"{previous_day}T23:30:00+00:00",
                f"{BASE_DAY}T01:00:00+00:00", f"{BASE_DAY}T03:00:00+00:00"]
    two_either_side, third_same_day = "+12122340301", "+12122340302"
    for number, times in ((two_either_side, straddle), (third_same_day, straddle + [f"{BASE_DAY}T05:00:00+00:00"])):
        for index, reported_at in enumerate(times, start=1):
            write_report(data_dir, f"{number[1:]}-{index}.json", number, f"00000000000b{index:04x}", reported_at,
                         device=f"00000000000c{index:04x}")
    # A domain seen by one bucket before midnight and another after, and one seen
    # by two buckets on the same day.
    for index, (domain, reported_at, bucket) in enumerate([
        ("straddle.example", f"{previous_day}T23:50:00+00:00", BUCKETS[0]),
        ("straddle.example", f"{previous_day}T23:55:00+00:00", BUCKETS[0]),
        ("straddle.example", f"{BASE_DAY}T00:10:00+00:00", BUCKETS[1]),
        ("sameday.example", f"{BASE_DAY}T01:00:00+00:00", BUCKETS[0]),
        ("sameday.example", f"{BASE_DAY}T01:30:00+00:00", BUCKETS[0]),
        ("sameday.example", f"{BASE_DAY}T02:00:00+00:00", BUCKETS[1]),
    ]):
        write_report(data_dir, f"domain-{index}.json", f"+1212234032{index}", bucket, reported_at,
                     report_type="sms_spam", domains=[domain])

    run_script("generate_hot_list.py", data_dir, ["--allow-collapse", "--cleared", "numbers,ranges"])
    run_script("extract_spam_domains.py", data_dir, ["--allow-collapse", "--cleared", "domains"])
    hot = {entry["number"]: entry for entry in json.loads((data_dir / "hot_numbers.json").read_text(encoding="utf-8"))["numbers"]}
    if two_either_side in hot:
        raise AssertionError(f"two reporters either side of midnight were counted as four: {hot[two_either_side]}")
    if hot.get(third_same_day, {}).get("distinct_reporters") != 3:
        raise AssertionError(f"three reporters on one day should trend: {hot.get(third_same_day)}")
    review = json.loads((data_dir / "spam_domains_review.json").read_text(encoding="utf-8"))
    reporters = {candidate["domain"]: candidate["distinct_reporters"] for candidate in review["candidates"]}
    if "straddle.example" in reporters or reporters.get("sameday.example") != 2:
        raise AssertionError(f"domain reporters should be counted on one UTC day: {reporters}")


def assert_community_watch_uses_90_day_device_evidence(data_dir: Path) -> None:
    """The advisory feed counts same-day devices for 90 days and clears on not-spam."""
    write_json(
        data_dir / "spam_numbers.json",
        {"version": 1, "updated": TODAY, "sources": ["community_reports"], "numbers": [], "prefixes": []},
    )
    watch_number, old_number, spread_number, cleared_number, one_group_number = (
        "+12122340401", "+12122340402", "+12122340403", "+12122340404", "+12122340407"
    )
    day = lambda age, hour=10, minute=0: (
        datetime.now(timezone.utc) - timedelta(days=age)
    ).replace(hour=hour, minute=minute, second=0, microsecond=0).isoformat()

    group_a, group_b = "0000000000000010", "0000000000000011"
    for index, (group, device) in enumerate(
        (
            (group_a, "0000000000000101"),
            (group_a, "0000000000000102"),
            (group_a, "0000000000000103"),
            (group_b, "0000000000000104"),
        ),
        start=1,
    ):
        write_report(data_dir, f"watch-{index}.json", watch_number, group, day(2, 8 + index), device=device)

    # This evidence is older than the unchanged 30-day promotion window.
    for index, bucket in enumerate(BUCKETS[:2], start=1):
        write_report(data_dir, f"old-watch-{index}.json", old_number, bucket, day(45), device=BUCKETS[index + 1])

    # Daily HMACs rotate, so reports on adjacent UTC days never combine into
    # two known reporters. These events are also too close to promote a row.
    write_report(data_dir, "spread-watch-1.json", spread_number, BUCKETS[2], day(20, 23, 40), device=BUCKETS[3])
    write_report(data_dir, "spread-watch-2.json", spread_number, BUCKETS[4], day(19, 0, 5), device=BUCKETS[5])

    for index, bucket in enumerate(BUCKETS[:2], start=1):
        write_report(data_dir, f"clear-watch-{index}.json", cleared_number, bucket, day(4), device=BUCKETS[index + 1])

    # Two devices in one /48 on the same day may be one household, so they are
    # one reporter's word, not two users'.
    write_report(data_dir, "one-group-1.json", one_group_number, group_a, day(3, 9), device="0000000000000105")
    write_report(data_dir, "one-group-2.json", one_group_number, group_a, day(3, 10), device="0000000000000106")

    # A legacy promotion ledger can prove reporter buckets, but never device
    # identities. Migration keeps that evidence as one device per bucket.
    legacy_number = "+12122340405"
    legacy_day = (datetime.now(timezone.utc) - timedelta(days=5)).date().isoformat()
    write_json(
        data_dir / "community_pending.json",
        {
            "schema_version": 1,
            "retention_days": 30,
            "numbers": {
                legacy_number: {
                    "published": False,
                    "entry": {
                        "number": legacy_number,
                        "type": "phishing",
                        "reports": 0,
                        "first_seen": legacy_day,
                        "last_seen": legacy_day,
                        "description": "Community reported",
                        "sources": ["community"],
                    },
                    "events": [
                        {"key": "legacy-a", "day": legacy_day, "bucket": BUCKETS[0], "count": 1},
                        {"key": "legacy-b", "day": legacy_day, "bucket": BUCKETS[1], "count": 1},
                    ],
                }
            },
        },
    )

    run_drain(data_dir)
    feed = json.loads((data_dir / "community_watch.json").read_text(encoding="utf-8"))
    rows = {row["number"]: row["reporter_count"] for row in feed["numbers"]}
    assert rows.get(watch_number) == 3, f"three devices in one /48 plus one in another must cap at three: {rows}"
    assert rows.get(old_number) == 2, f"90-day evidence older than promotion retention was lost: {rows}"
    assert rows.get(cleared_number) == 2, f"two same-day reporter groups should be watched: {rows}"
    assert rows.get(legacy_number) == 2, f"legacy promotion evidence was not migrated: {rows}"
    assert one_group_number not in rows, f"two devices in one group are not two users: {rows}"
    assert spread_number not in rows, f"reporters across UTC days were incorrectly combined: {rows}"
    assert all(set(row) == {"number", "reporter_count"} for row in feed["numbers"]), feed

    pending = json.loads((data_dir / "community_pending.json").read_text(encoding="utf-8"))["numbers"]
    assert len(pending[watch_number]["watch_events"]) == 4, pending[watch_number]
    assert len(pending[watch_number]["events"]) == 2, "device-level evidence changed the 30-day promotion gate"
    assert pending[old_number]["events"] == [], "90-day evidence extended promotion retention"

    write_report(data_dir, "not-spam.json", cleared_number, None, day(0), report_type="not_spam")
    run_drain(data_dir)
    cleared_feed = json.loads((data_dir / "community_watch.json").read_text(encoding="utf-8"))
    assert cleared_number not in {row["number"] for row in cleared_feed["numbers"]}, cleared_feed
    assert cleared_feed["cleared"] is False, "other active numbers should keep the feed populated"

    empty_dir = data_dir.parent / "expired"
    expired_number = "+12122340406"
    expired_day = (datetime.now(timezone.utc) - timedelta(days=91)).date().isoformat()
    write_json(
        empty_dir / "spam_numbers.json",
        {"version": 1, "updated": TODAY, "sources": ["community_reports"], "numbers": [], "prefixes": []},
    )
    write_json(
        empty_dir / "community_pending.json",
        {
            "schema_version": 1,
            "retention_days": 30,
            "watch_retention_days": 90,
            "numbers": {
                expired_number: {
                    "published": False,
                    "entry": {
                        "number": expired_number,
                        "type": "phishing",
                        "reports": 0,
                        "first_seen": expired_day,
                        "last_seen": expired_day,
                        "description": "Community reported",
                        "sources": ["community"],
                    },
                    "events": [],
                    "watch_events": [
                        {"day": expired_day, "bucket": BUCKETS[0], "device": BUCKETS[0]},
                        {"day": expired_day, "bucket": BUCKETS[1], "device": BUCKETS[1]},
                    ],
                    "not_spam_days": [],
                }
            },
        },
    )
    run_drain(empty_dir)
    expired_feed = json.loads((empty_dir / "community_watch.json").read_text(encoding="utf-8"))
    assert expired_feed["numbers"] == [] and expired_feed["cleared"] is True, expired_feed


def run_drain(data_dir: Path) -> None:
    # Each drain here leaves the derived feeds empty, which their collapse
    # guards refuse to publish without being told, and an empty feed has to be
    # published cleared.
    run_script("extract_spam_domains.py", data_dir, ["--allow-collapse", "--cleared", "domains"])
    run_script("generate_hot_list.py", data_dir, ["--allow-collapse", "--cleared", "numbers,ranges"])
    run_script("merge_community_reports.py", data_dir)


def reports_for(data_dir: Path, number: str) -> int:
    database = json.loads((data_dir / "spam_numbers.json").read_text(encoding="utf-8"))
    return next((row["reports"] for row in database["numbers"] if row["number"] == number), 0)


def pending_reports_for(data_dir: Path, number: str) -> int:
    ledger = json.loads((data_dir / "community_pending.json").read_text(encoding="utf-8"))["numbers"]
    return sum(event["count"] for event in ledger[number]["events"])


def row_for(data_dir: Path, number: str) -> dict | None:
    database = json.loads((data_dir / "spam_numbers.json").read_text(encoding="utf-8"))
    return next((row for row in database["numbers"] if row["number"] == number), None)


def assert_a_published_number_leaves_the_watch_list(data_dir: Path) -> None:
    """A maintainer approval publishes a watched number but keeps its ledger
    row unpublished. The watch list must still drop it: the database blocks it
    now, and the not-spam reports that would clear it are ignored once listed."""
    write_json(
        data_dir / "spam_numbers.json",
        {"version": 1, "updated": TODAY, "sources": ["community_reports"], "numbers": [], "prefixes": []},
    )
    number = "+12122340477"
    reported = (datetime.now(timezone.utc) - timedelta(days=1)).replace(hour=10, minute=0, second=0, microsecond=0)
    for index, bucket in enumerate(BUCKETS[:2], start=1):
        write_report(
            data_dir, f"watched-{index}.json", number, bucket, (reported + timedelta(hours=index)).isoformat(), device=BUCKETS[index + 1]
        )
    run_drain(data_dir)
    feed = json.loads((data_dir / "community_watch.json").read_text(encoding="utf-8"))
    assert [row["number"] for row in feed["numbers"]] == [number], feed

    write_json(
        data_dir / "spam_numbers_approved.json",
        {"approved": [{"number": number, "type": "scam", "reviewed_at": TODAY, "reference": "https://example.org/report"}]},
    )
    run_script("merge_community_reports.py", data_dir)
    assert row_for(data_dir, number) is not None, "the approval must publish the number"
    feed = json.loads((data_dir / "community_watch.json").read_text(encoding="utf-8"))
    assert feed["numbers"] == [] and feed["cleared"] is True, feed


def assert_maintainer_approval_publishes_a_reviewed_number(data_dir: Path) -> None:
    """Issue #27: one report of a Munich number waits for corroboration no
    imported source can give. The maintainer's review publishes it, and later
    community reports can't demote it."""
    munich, reported, fresh = "+498943780834", "+18056377456", "+13109462201"
    fcc_evidence = {
        "source_id": "fcc_complaints",
        "retrieved_at": f"{BASE_DAY}T00:00:00+00:00",
        "expires_at_epoch_ms": 4_000_000_000_000,
    }
    write_json(
        data_dir / "spam_numbers.json",
        {
            "version": 7,
            "updated": BASE_DAY,
            "sources": ["community_reports"],
            "numbers": [
                {
                    "number": reported,
                    "type": "robocall",
                    "reports": 59,
                    "first_seen": "2015-07-28",
                    "last_seen": "2026-09-21",
                    "description": "FCC: Unwanted Calls",
                    "sources": ["legacy_import"],
                    "evidence": [fcc_evidence],
                }
            ],
            "prefixes": [],
        },
    )
    write_report(data_dir, "498943780834_1.json", munich, None, f"{BASE_DAY}T11:54:55+00:00", report_type="unknown")
    run_drain(data_dir)
    assert row_for(data_dir, munich) is None, "one report must not publish a number"
    assert pending_reports_for(data_dir, munich) == 1

    approvals = {
        "approved": [
            {
                "number": munich,
                "type": "telemarketer",
                "reviewed_at": BASE_DAY,
                "reference": "https://github.com/SysAdminDoc/CallShield/issues/27",
                "note": "tellows.de 21 ratings, Clever Dialer 41 reviews",
            },
            {"number": reported, "type": "robocall", "reviewed_at": BASE_DAY, "reference": "https://github.com/SysAdminDoc/CallShield/issues/24"},
            {"number": fresh, "type": "scam", "reviewed_at": BASE_DAY, "reference": "https://example.org/report"},
        ]
    }
    write_json(data_dir / "spam_numbers_approved.json", approvals)
    run_script("merge_community_reports.py", data_dir)

    database = json.loads((data_dir / "spam_numbers.json").read_text(encoding="utf-8"))
    assert database["version"] == 8, database["version"]
    row = row_for(data_dir, munich)
    assert row is not None, "the reviewed number must be published"
    assert row["type"] == "telemarketer" and row["reports"] == 1, row
    assert row["description"] == "Community reported; Reviewed by the maintainer", row
    assert row["sources"] == ["community"], row
    [evidence] = row["evidence"]
    assert evidence["source_id"] == "maintainer_review" and evidence["confidence_tier"] == "curated", evidence
    expected_expiry = datetime.fromisoformat(f"{BASE_DAY}T00:00:00+00:00") + timedelta(days=730)
    assert evidence["expires_at_epoch_ms"] == int(expected_expiry.timestamp() * 1000), evidence
    # The report stays in the ledger, unpublished, in case the review is revoked.
    pending = json.loads((data_dir / "community_pending.json").read_text(encoding="utf-8"))["numbers"]
    assert pending[munich]["published"] is False and len(pending[munich]["events"]) == 1, pending[munich]

    # An imported row keeps its own evidence and counts, and gains the review.
    row = row_for(data_dir, reported)
    assert row["reports"] == 59 and row["description"] == "FCC: Unwanted Calls; Reviewed by the maintainer", row
    assert [item["source_id"] for item in row["evidence"]] == ["fcc_complaints", "maintainer_review"], row

    # A number nobody reported through the app is published on the review alone.
    row = row_for(data_dir, fresh)
    assert row["sources"] == ["maintainer_review"] and row["reports"] == 1, row
    assert row["description"] == "Reviewed by the maintainer", row

    # Running again changes nothing, so phones don't download the database again.
    run_script("merge_community_reports.py", data_dir)
    assert json.loads((data_dir / "spam_numbers.json").read_text(encoding="utf-8"))["version"] == 8

    # A later bucketed report can't demote it the way it demotes a community-only row.
    write_report(data_dir, "498943780834_2.json", munich, BUCKETS[0], NOW, report_type="unknown")
    run_drain(data_dir)
    row = row_for(data_dir, munich)
    assert row is not None and row["reports"] == 2, row

    # An approval that can't be checked stops the merge instead of being skipped.
    for broken, fault in (
        ({"number": "+15555550100", "type": "scam", "reviewed_at": BASE_DAY, "reference": "https://example.org/r"}, "plausible"),
        ({"number": fresh, "type": "scam", "reviewed_at": "2999-01-01", "reference": "https://example.org/r"}, "reviewed_at"),
        ({"number": fresh, "type": "scam", "reviewed_at": BASE_DAY, "reference": "http://example.org/r"}, "https reference"),
        ({"number": fresh, "type": "", "reviewed_at": BASE_DAY, "reference": "https://example.org/r"}, "needs a type"),
    ):
        write_json(data_dir / "spam_numbers_approved.json", {"approved": [*approvals["approved"][:2], broken]})
        result = run_script_result("merge_community_reports.py", data_dir)
        assert result.returncode != 0 and "spam_numbers_approved.json" in result.stderr, (broken, result.stderr)
        assert fault in result.stderr, (fault, result.stderr)


def database_version(data_dir: Path) -> int:
    return json.loads((data_dir / "spam_numbers.json").read_text(encoding="utf-8"))["version"]


def assert_maintainer_approval_can_be_revoked(data_dir: Path) -> None:
    """A revoked approval takes the review back off its row at the next merge,
    and a row nothing else backs is unpublished. A number listed twice, or an
    entry deleted instead of revoked, stops the merge."""
    munich, reported, fresh, settled = "+498943780834", "+18056377456", "+13109462201", "+13129870555"

    def days_ago(days: int) -> str:
        return (datetime.fromisoformat(BASE_DAY) - timedelta(days=days)).date().isoformat()

    fcc_evidence = {
        "source_id": "fcc_complaints",
        "retrieved_at": f"{BASE_DAY}T00:00:00+00:00",
        "expires_at_epoch_ms": 4_000_000_000_000,
    }
    write_json(
        data_dir / "spam_numbers.json",
        {
            "version": 7,
            "updated": BASE_DAY,
            "sources": ["community_reports"],
            "numbers": [
                {
                    "number": reported,
                    "type": "robocall",
                    "reports": 59,
                    "first_seen": "2015-07-28",
                    "last_seen": BASE_DAY,
                    "description": "FCC: Unwanted Calls",
                    "sources": ["legacy_import"],
                    "evidence": [fcc_evidence],
                },
                # A community row last reported two months ago.
                {
                    "number": settled,
                    "type": "scam",
                    "reports": 3,
                    "first_seen": days_ago(90),
                    "last_seen": days_ago(60),
                    "description": "Community reported",
                    "sources": ["community"],
                },
            ],
            "prefixes": [],
        },
    )
    write_report(data_dir, "munich_1.json", munich, None, f"{BASE_DAY}T11:54:55+00:00", report_type="unknown")
    run_drain(data_dir)
    approvals = [
        {"number": munich, "type": "telemarketer", "reviewed_at": BASE_DAY, "reference": "https://github.com/SysAdminDoc/CallShield/issues/27"},
        {"number": reported, "type": "robocall", "reviewed_at": BASE_DAY, "reference": "https://github.com/SysAdminDoc/CallShield/issues/24"},
        {"number": fresh, "type": "scam", "reviewed_at": BASE_DAY, "reference": "https://example.org/report"},
        {"number": settled, "type": "scam", "reviewed_at": days_ago(60), "reference": "https://example.org/settled"},
    ]
    write_json(data_dir / "spam_numbers_approved.json", {"approved": approvals})
    run_script("merge_community_reports.py", data_dir)
    assert database_version(data_dir) == 8
    assert row_for(data_dir, munich)["sources"] == ["community"]

    # The reviewed row's raw sources say "community", but the review is its own
    # evidence, so not-spam votes don't make it a correction candidate. Before,
    # it became one and approving the correction was then skipped.
    for index, bucket in enumerate(BUCKETS[:4]):
        write_report(data_dir, f"munich_not_spam_{index}.json", munich, bucket, TIMES[index], report_type="not_spam")
    run_drain(data_dir)
    assert not (data_dir / "not_spam_review.json").exists(), (data_dir / "not_spam_review.json").read_text(encoding="utf-8")
    assert database_version(data_dir) == 8

    # Taking the review off an imported row is a change of its own.
    write_json(
        data_dir / "spam_numbers_approved.json",
        {"approved": [approvals[0], {**approvals[1], "revoked": True, "revoked_at": BASE_DAY}, *approvals[2:]]},
    )
    run_script("merge_community_reports.py", data_dir)
    assert database_version(data_dir) == 9
    assert row_for(data_dir, reported)["description"] == "FCC: Unwanted Calls", row_for(data_dir, reported)

    legacy = "+13129870666"
    database = json.loads((data_dir / "spam_numbers.json").read_text(encoding="utf-8"))
    database["numbers"].append(
        {
            "number": legacy,
            "type": "scam",
            "reports": 1,
            "first_seen": BASE_DAY,
            "last_seen": BASE_DAY,
            "description": "Community reported; Reviewed by the maintainer",
            "sources": ["community"],
            "evidence": [row_for(data_dir, fresh)["evidence"][0]],
        }
    )
    write_json(data_dir / "spam_numbers.json", database)
    revoked = [{**approval, "revoked": True, "revoked_at": BASE_DAY} for approval in approvals]
    legacy_entry = {"number": legacy, "type": "scam", "reviewed_at": BASE_DAY, "reference": "https://example.org/legacy"}
    write_json(data_dir / "spam_numbers_approved.json", {"approved": [*revoked, {**legacy_entry, "revoked": True, "revoked_at": BASE_DAY}]})
    run_script("merge_community_reports.py", data_dir)
    assert database_version(data_dir) == 10

    # The review alone published these two.
    assert row_for(data_dir, munich) is None
    assert row_for(data_dir, fresh) is None
    # The imported row goes back to exactly what its own source says.
    row = row_for(data_dir, reported)
    assert row["reports"] == 59 and row["description"] == "FCC: Unwanted Calls", row
    assert row["sources"] == ["legacy_import"], row
    assert [item["source_id"] for item in row["evidence"]] == ["fcc_complaints"], row
    # Munich's community report waits in the ledger again, pinned to the row's last day.
    ledger = json.loads((data_dir / "community_pending.json").read_text(encoding="utf-8"))["numbers"]
    assert ledger[munich]["published"] is False, ledger[munich]
    assert [(event["day"], event["bucket"], event["count"]) for event in ledger[munich]["events"]] == [(BASE_DAY, "", 1)], ledger
    assert fresh not in ledger, ledger
    # A row the community published on its own reports stays published.
    row = row_for(data_dir, settled)
    assert row is not None and row["reports"] == 3 and row["description"] == "Community reported", row
    assert row["sources"] == ["community"] and "evidence" not in row, row
    assert ledger[settled]["published"] is True, ledger[settled]
    # A reviewed row with no reports left in the ledger, the way #27's number
    # was published before the ledger kept them, is simply unpublished.
    assert row_for(data_dir, legacy) is None
    assert legacy not in ledger, ledger[legacy]

    # Nothing changed, so the next merge leaves the version alone.
    run_script("merge_community_reports.py", data_dir)
    assert database_version(data_dir) == 10

    # Lifting a revocation approves the number again.
    write_json(data_dir / "spam_numbers_approved.json", {"approved": [revoked[0], revoked[1], approvals[2], revoked[3]]})
    run_script("merge_community_reports.py", data_dir)
    assert database_version(data_dir) == 11
    assert row_for(data_dir, fresh)["sources"] == ["maintainer_review"]

    earlier = (datetime.fromisoformat(BASE_DAY) - timedelta(days=1)).date().isoformat()
    for approved, fault in (
        # Listed twice with two dates, the evidence used to flip on every merge.
        ([*revoked[:2], approvals[2], {**approvals[2], "reviewed_at": earlier}], "listed more than once"),
        # Spelled two ways is still one number.
        ([*revoked[:2], approvals[2], {**approvals[2], "number": "+1 (310) 946-2201"}], "listed more than once"),
        # Deleting an entry took nothing back, so it has to be revoked instead.
        ([revoked[0], revoked[1]], "no longer lists"),
        ([revoked[0], revoked[1], {**approvals[2], "revoked": "yes", "revoked_at": BASE_DAY}], "revoked set to true or false"),
        ([revoked[0], revoked[1], {**approvals[2], "revoked": True}], "revoked_at"),
        ([revoked[0], revoked[1], {**approvals[2], "revoked": True, "revoked_at": "2999-01-01"}], "revoked_at"),
    ):
        write_json(data_dir / "spam_numbers_approved.json", {"approved": approved})
        result = run_script_result("merge_community_reports.py", data_dir)
        assert result.returncode != 0 and fault in result.stderr, (fault, result.stderr)
        assert fresh in result.stderr, result.stderr
        assert database_version(data_dir) == 11


def assert_revoked_review_hands_reports_back_to_the_gate(data_dir: Path) -> None:
    """A revoked review leaves the number to its community reports, with their
    real days and reporters. The review's own date and count can't help it."""
    hurried, bucketed, unreported, carried = "+13129870101", "+13129870102", "+13129870103", "+13129870104"
    timed = "+13129870105"

    def day(days: int, hour: int = 10) -> str:
        return (datetime.now(timezone.utc) - timedelta(days=days)).replace(hour=hour, minute=0, second=0, microsecond=0).isoformat()

    reviewed = (datetime.now(timezone.utc) - timedelta(days=1)).date().isoformat()
    # Published by the community on report times alone, long enough ago that
    # its reports have left the ledger.
    timed_row = {
        "number": timed,
        "type": "scam",
        "reports": 2,
        "first_seen": day(60)[:10],
        "last_seen": day(40)[:10],
        "description": "Community reported",
        "sources": ["community"],
    }
    write_json(data_dir / "spam_numbers.json", {"version": 7, "updated": BASE_DAY, "numbers": [timed_row], "prefixes": []})
    write_report(data_dir, "hurried_1.json", hurried, None, day(5))
    write_report(data_dir, "bucketed_1.json", bucketed, BUCKETS[0], day(5))
    write_report(data_dir, "bucketed_2.json", bucketed, BUCKETS[1], day(5, 11))
    write_report(data_dir, "carried_1.json", carried, BUCKETS[0], day(4))
    run_drain(data_dir)
    approvals = [
        {"number": number, "type": "scam", "reviewed_at": reviewed, "reference": "https://example.org/review"}
        for number in (hurried, bucketed, unreported, carried, timed)
    ]
    write_json(data_dir / "spam_numbers_approved.json", {"approved": approvals})
    run_script("merge_community_reports.py", data_dir)
    assert all(row_for(data_dir, number) for number in (hurried, bucketed, unreported, carried, timed))

    # Reports that land while the review holds the row up.
    write_report(data_dir, "unreported_1.json", unreported, BUCKETS[3], day(1))
    for index, bucket in enumerate(BUCKETS[:3]):
        write_report(data_dir, f"carried_{index + 2}.json", carried, bucket, day(1, 10 + index))
    # One bucketed reporter would send a row promoted on times alone back to
    # the gate, but not while the review holds it up.
    write_report(data_dir, "timed_1.json", timed, BUCKETS[4], day(1))
    run_drain(data_dir)
    row = row_for(data_dir, timed)
    assert row is not None and row["reports"] == 3 and row["first_seen"] == day(60)[:10], row
    assert row_for(data_dir, unreported)["reports"] == 2, row_for(data_dir, unreported)
    assert row_for(data_dir, carried)["reports"] == 4, row_for(data_dir, carried)

    write_json(
        data_dir / "spam_numbers_approved.json",
        {"approved": [{**approval, "revoked": True, "revoked_at": BASE_DAY} for approval in approvals]},
    )
    run_script("merge_community_reports.py", data_dir)
    ledger = json.loads((data_dir / "community_pending.json").read_text(encoding="utf-8"))["numbers"]

    # One report isn't a quorum, and its day is the report's, not the review's.
    assert row_for(data_dir, hurried) is None
    assert [event["day"] for event in ledger[hurried]["events"]] == [day(5)[:10]], ledger[hurried]
    assert ledger[hurried]["entry"]["last_seen"] == day(5)[:10], ledger[hurried]
    # Two reporters on one day aren't three.
    assert row_for(data_dir, bucketed) is None
    # The review's placeholder count goes with it: one real report is left.
    assert row_for(data_dir, unreported) is None
    assert ledger[unreported]["entry"]["reports"] == 1, ledger[unreported]
    assert ledger[unreported]["entry"]["description"] == "Community reported", ledger[unreported]
    # Three reporters on one day, a day after the first report: the community
    # carries this one on its own, so it stays.
    row = row_for(data_dir, carried)
    assert row is not None and row["reports"] == 4 and row["sources"] == ["community"], row
    assert row["first_seen"] == day(4)[:10] and row["last_seen"] == day(1)[:10], row
    assert row["description"] == "Community reported" and "evidence" not in row, row
    assert ledger[carried]["published"] is True, ledger[carried]
    # Without the review, the gate rechecks the timed row on its bucket and holds it.
    assert row_for(data_dir, timed) is None
    assert ledger[timed]["published"] is False, ledger[timed]

    # Later reports go through the ordinary gate. Before, a report on the same
    # day as the first one counted as a day after the review's date, and an
    # unbucketed one skipped the three-reporter rule the buckets had set.
    write_report(data_dir, "hurried_2.json", hurried, None, day(5, 14))
    write_report(data_dir, "bucketed_3.json", bucketed, None, day(2))
    run_drain(data_dir)
    assert row_for(data_dir, hurried) is None
    assert row_for(data_dir, bucketed) is None


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
    run_script("extract_spam_domains.py", data_dir, ["--allow-collapse", "--cleared", "domains"])
    run_script("generate_hot_list.py", data_dir, ["--allow-collapse", "--cleared", "numbers,ranges"])
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
    run_script("extract_spam_domains.py", data_dir, ["--allow-collapse", "--cleared", "domains"])
    run_script("generate_hot_list.py", data_dir, ["--allow-collapse", "--cleared", "numbers,ranges"])
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
        assert_maintainer_approval_publishes_a_reviewed_number(Path(tmp) / "data")

    with tempfile.TemporaryDirectory() as tmp:
        assert_maintainer_approval_can_be_revoked(Path(tmp) / "data")

    with tempfile.TemporaryDirectory() as tmp:
        assert_revoked_review_hands_reports_back_to_the_gate(Path(tmp) / "data")

    with tempfile.TemporaryDirectory() as tmp:
        assert_resend_across_drains_counts_once(Path(tmp) / "data")

    with tempfile.TemporaryDirectory() as tmp:
        assert_ledger_retention(Path(tmp) / "data")

    with tempfile.TemporaryDirectory() as tmp:
        assert_not_spam_id_not_recorded(Path(tmp) / "data")

    with tempfile.TemporaryDirectory() as tmp:
        assert_reporters_count_per_device_with_group_cap(Path(tmp) / "data")

    with tempfile.TemporaryDirectory() as tmp:
        assert_reporters_count_on_one_utc_day(Path(tmp) / "data")

    with tempfile.TemporaryDirectory() as tmp:
        assert_community_watch_uses_90_day_device_evidence(Path(tmp) / "data")

    with tempfile.TemporaryDirectory() as tmp:
        assert_a_published_number_leaves_the_watch_list(Path(tmp) / "data")


if __name__ == "__main__":
    main()
