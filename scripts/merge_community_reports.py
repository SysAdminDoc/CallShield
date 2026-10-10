#!/usr/bin/env python3
"""
Merges community-reported spam numbers from data/reports/ into
the main spam_numbers.json database, then deletes processed files.
"""

import argparse
import json
import os
import subprocess
from collections import Counter
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

from phone_normalization import is_plausible_number, validated_report_number
from pipeline_io import (
    atomic_write_json,
    report_queue_digest,
    require_matching_derived_feed,
)
from report_dedup import (
    capped_reporter_count,
    find_burst_duplicates,
    find_resent_reports,
    parse_reported_at,
    reporter_identity,
    validated_report_id,
    validated_reporter_bucket,
)
from source_registry import (
    load_source_manifest,
    merge_evidence,
    source_evidence,
    source_health_report,
)
from spam_shards import write_sharded_database

DATA_DIR = Path(os.environ.get("CALLSHIELD_DATA_DIR", Path(__file__).parent.parent / "data"))
DB_FILE = DATA_DIR / "spam_numbers.json"
REPORTS_DIR = Path(os.environ.get("CALLSHIELD_REPORTS_DIR", DATA_DIR / "reports"))
NOT_SPAM_REVIEW_FILE = DATA_DIR / "not_spam_review.json"
SOURCE_SNAPSHOT_FILE = DATA_DIR / "source-snapshot.json"
MERGED_IDS_FILE = DATA_DIR / "merged_report_ids.json"
COMMUNITY_PENDING_FILE = DATA_DIR / "community_pending.json"
COMMUNITY_WATCH_FILE = DATA_DIR / "community_watch.json"
# Numbers the maintainer checked by hand, usually a spam report filed as a
# GitHub issue, against public complaint sites. Tracked, so every approval is
# on the record with the reason for it.
APPROVED_NUMBERS_FILE = DATA_DIR / "spam_numbers_approved.json"
SOURCE_MANIFEST_FILE = Path(__file__).parent.parent / "data" / "source-manifest.json"
MAINTAINER_SOURCE = "maintainer_review"
MAINTAINER_DESCRIPTION = "Reviewed by the maintainer"
COMMUNITY_PENDING_DAYS = 30
COMMUNITY_WATCH_DAYS = 90
COMMUNITY_WATCH_MIN_REPORTERS = 2
COMMUNITY_WATCH_MAX_NUMBERS = 5_000
COMMUNITY_REPORTER_QUORUM = 3
# Every promotion needs two reports this far apart, with or without buckets.
COMMUNITY_REPORT_GAP = timedelta(hours=24)
# How long a merged report's id is remembered. The app resends a report whose
# answer it never saw, and a resend that lands after its original was merged
# and deleted is invisible to the queue.
MERGED_ID_RETENTION_DAYS = 14

COMMUNITY_DESCRIPTION = "Community reported"
COMMUNITY_SOURCE = "community"
LEGACY_SOURCE = "legacy_import"
# In-app GitHub reports are titled "[SPAM] +E164" (ReportIssueUrl.kt).
GITHUB_REPO = "SysAdminDoc/CallShield"
SPAM_ISSUE_PREFIX = "[SPAM]"


def quarantine(report_file: Path, rejected_dir: Path) -> None:
    """Move an unreadable/malformed report out of the active queue so it is
    not silently reprocessed (and re-erroring) on every future run."""
    try:
        rejected_dir.mkdir(parents=True, exist_ok=True)
        report_file.rename(rejected_dir / report_file.name)
    except OSError:
        pass


def load_merged_report_ids() -> dict[str, str]:
    """report_id -> the day it was merged, for reports merged recently.

    An unreadable ledger stops the merge instead of reading as empty, which
    would count every resend in the queue a second time.
    """
    if not MERGED_IDS_FILE.exists():
        return {}
    try:
        ids = json.loads(MERGED_IDS_FILE.read_text(encoding="utf-8"))["ids"]
        if not isinstance(ids, dict):
            raise ValueError("ids is not an object")
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise SystemExit(f"{MERGED_IDS_FILE.name} can't be read ({error}). Fix or restore it before merging.") from error
    return {str(report_id): str(day) for report_id, day in ids.items() if validated_report_id(report_id)}


def _is_valid_day(day: str, today: str) -> bool:
    """True when day is a YYYY-MM-DD string not later than today."""
    try:
        datetime.strptime(day, "%Y-%m-%d")
    except (ValueError, TypeError):
        return False
    return day <= today


def load_community_pending(today: str) -> dict[str, dict]:
    """Read promotion and advisory evidence with their separate retention windows."""
    if not COMMUNITY_PENDING_FILE.exists():
        return {}
    try:
        payload = json.loads(COMMUNITY_PENDING_FILE.read_text(encoding="utf-8"))
        if payload["schema_version"] != 1 or not isinstance(payload["numbers"], dict):
            raise ValueError("unsupported pending ledger")
        cutoff = (date.fromisoformat(today) - timedelta(days=COMMUNITY_PENDING_DAYS)).isoformat()
        watch_cutoff = (date.fromisoformat(today) - timedelta(days=COMMUNITY_WATCH_DAYS)).isoformat()
        pending = {}
        for number, state in payload["numbers"].items():
            if (
                not is_plausible_number(number)
                or not isinstance(state, dict)
                or not isinstance(state.get("published"), bool)
                or not isinstance(state.get("entry"), dict)
                or not isinstance(state.get("events"), list)
            ):
                raise ValueError(f"invalid pending entry for {number}")
            events = []
            for event in state["events"]:
                if (
                    not isinstance(event, dict)
                    or not isinstance(event.get("key"), str)
                    or not event["key"]
                    or not _is_valid_day(event.get("day"), today)
                    or not isinstance(event.get("bucket"), str)
                    or (event["bucket"] and validated_reporter_bucket(event["bucket"]) != event["bucket"])
                    or type(event.get("count")) is not int
                    or event["count"] < 1
                    or ("at" in event and parse_reported_at(event["at"]) is None)
                ):
                    raise ValueError(f"invalid pending event for {number}")
                if event["day"] >= cutoff:
                    events.append(event)
            raw_watch_events = state.get("watch_events")
            if raw_watch_events is None:
                # Older promotion ledgers kept only the /48 bucket. Treat it as
                # one device while migrating the evidence the old schema can prove.
                raw_watch_events = [
                    {"day": event["day"], "bucket": event["bucket"], "device": event["bucket"]}
                    for event in events
                    if event["bucket"]
                ]
            if not isinstance(raw_watch_events, list):
                raise ValueError(f"invalid watch events for {number}")
            watch_events = []
            for event in raw_watch_events:
                if (
                    not isinstance(event, dict)
                    or not _is_valid_day(event.get("day"), today)
                    or not isinstance(event.get("bucket"), str)
                    or not event["bucket"]
                    or validated_reporter_bucket(event["bucket"]) != event["bucket"]
                    or not isinstance(event.get("device"), str)
                    or not event["device"]
                    or validated_reporter_bucket(event["device"]) != event["device"]
                ):
                    raise ValueError(f"invalid watch event for {number}")
                if event["day"] >= watch_cutoff:
                    watch_events.append(event)
            raw_not_spam_days = state.get("not_spam_days", [])
            if not isinstance(raw_not_spam_days, list):
                raise ValueError(f"invalid not-spam dates for {number}")
            not_spam_days = []
            for day in raw_not_spam_days:
                if not _is_valid_day(day, today):
                    raise ValueError(f"invalid not-spam date for {number}")
                if day >= watch_cutoff:
                    not_spam_days.append(day)
            if events or state["published"] or watch_events or not_spam_days:
                pending[number] = {
                    **state,
                    "events": events,
                    "watch_events": watch_events,
                    "not_spam_days": sorted(set(not_spam_days)),
                }
        return pending
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise SystemExit(f"{COMMUNITY_PENDING_FILE.name} can't be read ({error}). Fix or restore it before merging.") from error


def empty_community_state(number: str, report_type: str, day: str) -> dict:
    """Create a pending ledger row for advisory-only evidence."""
    return {
        "published": False,
        "entry": {
            "number": number,
            "type": report_type,
            "reports": 0,
            "first_seen": day,
            "last_seen": day,
            "description": COMMUNITY_DESCRIPTION,
            "sources": [COMMUNITY_SOURCE],
        },
        "events": [],
        "watch_events": [],
        "not_spam_days": [],
    }


def add_watch_event(state: dict, day: str, identity: tuple[str, str]) -> None:
    event = {"day": day, "bucket": identity[0], "device": identity[1]}
    if event not in state.setdefault("watch_events", []):
        state["watch_events"].append(event)


def community_watch_reporter_count(state: dict) -> int:
    """Same-day devices on the busiest day, capped per group, and only on a day
    that heard from two groups: the label says "2 CallShield users", and two
    phones behind one home network (a guest subnet gives one /48 two /64s) are
    as likely one person. A second group is the second opinion."""
    by_day: dict[str, set[tuple[str, str]]] = {}
    for event in state.get("watch_events", []):
        by_day.setdefault(event["day"], set()).add((event["bucket"], event["device"]))
    return max(
        (
            capped_reporter_count(identities)
            for identities in by_day.values()
            if len({group for group, _ in identities}) >= 2
        ),
        default=0,
    )


def watch_reporter_count(number: str, state: dict, existing: dict[str, dict], today: str) -> int:
    """The reporter count the watch feed would carry for [number]; 0 when it's left out."""
    cutoff = (date.fromisoformat(today) - timedelta(days=COMMUNITY_WATCH_DAYS)).isoformat()
    # A maintainer approval publishes a number but keeps its ledger row
    # unpublished, so the database itself is what says it's listed.
    if number in existing or state["published"] or any(day >= cutoff for day in state.get("not_spam_days", [])):
        return 0
    return community_watch_reporter_count(state)


def load_spam_issues(source: str) -> list[dict]:
    """Open issues for --github-issues: "gh" asks GitHub, anything else is a saved
    `gh issue list --json number,title,createdAt` file."""
    if source != "gh":
        issues = json.loads(Path(source).read_text(encoding="utf-8"))
    else:
        try:
            listed = subprocess.run(
                [
                    "gh", "issue", "list", "--repo", GITHUB_REPO, "--state", "open",
                    "--search", "SPAM in:title", "--limit", "500", "--json", "number,title,createdAt",
                ],
                check=True,
                capture_output=True,
                text=True,
                encoding="utf-8",
                timeout=60,
            )
        except subprocess.CalledProcessError as error:
            # gh says why on stderr ("run gh auth login"), which the exception leaves out.
            raise ValueError(f"gh failed: {(error.stderr or '').strip() or error}") from error
        issues = json.loads(listed.stdout)
    if not isinstance(issues, list) or not all(isinstance(issue, dict) for issue in issues):
        raise ValueError("expected a list of issues, as gh issue list --json writes")
    return issues


def spam_issue_rows(issues: list[dict], existing: dict[str, dict], pending: dict[str, dict], today: str) -> list[str]:
    """One line per open [SPAM] issue saying where its number stands.

    The merge never reads these issues as reports: counting them as reporter
    groups would let one person who files both ways pass the gate. Listing
    them here puts review and reply in one step.
    """
    rows = []
    for issue in sorted(issues, key=lambda item: item.get("number") or 0):
        title = str(issue.get("title") or "")
        if not title.startswith(SPAM_ISSUE_PREFIX):
            continue
        try:
            age = f"{(date.fromisoformat(today) - date.fromisoformat(str(issue.get('createdAt'))[:10])).days} days old"
        except ValueError:
            age = "age unknown"
        label = f"#{issue.get('number')} ({age})"
        number = validated_report_number(title[len(SPAM_ISSUE_PREFIX):].strip())
        if number is None:
            rows.append(f"{label}: unreadable title {title!r}")
            continue
        entry, state = existing.get(number), pending.get(number)
        if entry is not None:
            where = f"in the database ({entry.get('type', 'spam')}, {entry.get('reports', 0)} reports)"
        elif state is not None:
            events = state.get("events", [])
            # Counted the way the gate counts them: a bucket changes at midnight,
            # so only one day's buckets are known to be different reporters.
            groups = same_day_reporters(events)
            reports = sum(int(event.get("count", 1)) for event in events)
            where = f"pending, {reports} reports, {groups} reporter groups on its busiest day"
            if watch_reporter_count(number, state, existing, today) >= COMMUNITY_WATCH_MIN_REPORTERS:
                where += ", on the watch list"
        else:
            where = "not in the database or the pending pool"
        rows.append(f"{label} {number}: {where}")
    return rows


def write_community_watch_feed(
    pending: dict[str, dict], existing: dict[str, dict], today: str, input_digest: str
) -> None:
    rows = []
    for number, state in pending.items():
        reporters = watch_reporter_count(number, state, existing, today)
        if reporters >= COMMUNITY_WATCH_MIN_REPORTERS:
            rows.append({"number": number, "reporter_count": reporters})
    rows = sorted(rows, key=lambda row: (-row["reporter_count"], row["number"]))[:COMMUNITY_WATCH_MAX_NUMBERS]
    atomic_write_json(
        COMMUNITY_WATCH_FILE,
        {
            "schema_version": 1,
            "generated": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "input_report_digest": input_digest,
            "count": len(rows),
            "cleared": not rows,
            "numbers": rows,
        },
    )


def legacy_community_state(entry: dict, today: str) -> dict:
    """Recover only the report days a pre-ledger row can actually prove.

    The 30-day window is for new evidence. A row that shipped before the ledger
    existed is judged on its whole recorded span, or the migration expires rows
    whose reports were months apart (feed v47 dropped four that way).
    """
    reports = max(0, int(entry.get("reports", 0)))
    first, last = entry.get("first_seen"), entry.get("last_seen")
    events = []
    if reports and _is_valid_day(first, today) and _is_valid_day(last, today):
        if first != last and reports > 1:
            events.extend([
                {"key": "legacy:first", "day": first, "bucket": "", "count": 1},
                {"key": "legacy:last", "day": last, "bucket": "", "count": reports - 1},
            ])
        else:
            events.append({"key": "legacy:last", "day": last, "bucket": "", "count": reports})
    return {"published": False, "entry": entry, "events": events}


def _event_time(event: dict) -> datetime | None:
    """The Worker's timestamp an event was recorded with, when it has one."""
    at = parse_reported_at(event.get("at"))
    return at if at is not None and at.astimezone(timezone.utc).date().isoformat() == event["day"] else None


def same_day_reporters(events: list[dict]) -> int:
    """How many reporters the events prove are different people.

    The Worker's bucket is an HMAC of the UTC day and the reporter's network, so
    it changes at midnight by design and one reporter on three days shows three
    buckets. Only buckets from the same day are known to be different reporters.
    """
    by_day: dict[str, set[str]] = {}
    for event in events:
        if event["bucket"]:
            by_day.setdefault(event["day"], set()).add(event["bucket"])
    return max((len(buckets) for buckets in by_day.values()), default=0)


def reports_a_day_apart(events: list[dict]) -> bool:
    """True when two reports are provably at least 24 hours apart.

    UTC dates alone can't show it: reports at 23:57 and 00:03 fall on two days.
    An event recorded without a time could be anywhere in its day, so it counts
    from whichever end of the day makes the gap smallest.
    """
    if not events:
        return False
    starts, ends = [], []
    for event in events:
        at = _event_time(event)
        day_start = datetime.fromisoformat(event["day"]).replace(tzinfo=timezone.utc)
        starts.append(at or day_start)
        ends.append(at or day_start + timedelta(days=1))
    return max(starts) - min(ends) >= COMMUNITY_REPORT_GAP


def community_has_quorum(state: dict) -> bool:
    """Two reports a day apart, and with reporter buckets, three reporters on one day."""
    events = state["events"]
    if any(event["bucket"] for event in events) and same_day_reporters(events) < COMMUNITY_REPORTER_QUORUM:
        return False
    return reports_a_day_apart(events)


def promote_community_row(state: dict) -> None:
    """Publish a row and record whether three reporters carried it.

    Only the pending window's events are kept, so a row promoted by three
    reporters lost its quorum once the oldest reports passed 30 days and was
    demoted. Such a row now keeps its place; a row promoted on report times alone
    is still rechecked when bucket evidence appears.
    """
    state["published"] = True
    state["bucket_quorum"] = same_day_reporters(state["events"]) >= COMMUNITY_REPORTER_QUORUM


def remember_merged_report_ids(merged: dict[str, str], counted: set[str], today: str) -> None:
    """Add this run's ids and drop those past MERGED_ID_RETENTION_DAYS."""
    cutoff = (datetime.strptime(today, "%Y-%m-%d") - timedelta(days=MERGED_ID_RETENTION_DAYS)).strftime("%Y-%m-%d")
    kept = {report_id: day for report_id, day in merged.items() if _is_valid_day(day, today) and day >= cutoff}
    kept.update(dict.fromkeys(counted, today))
    if kept != merged or MERGED_IDS_FILE.exists():
        atomic_write_json(MERGED_IDS_FILE, {"retention_days": MERGED_ID_RETENTION_DAYS, "ids": dict(sorted(kept.items()))})


def load_review_candidates() -> list[dict]:
    if not NOT_SPAM_REVIEW_FILE.exists():
        return []
    try:
        payload = json.loads(NOT_SPAM_REVIEW_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    candidates = payload.get("candidates") if isinstance(payload, dict) else None
    if not isinstance(candidates, list):
        return []
    return [candidate for candidate in candidates if isinstance(candidate, dict)]


def canonical_source_ids(entry: dict) -> set[str]:
    source_ids = set()
    for source in entry.get("sources", []):
        if isinstance(source, str) and source.strip():
            source_ids.add("community_reports" if source == COMMUNITY_SOURCE else source)
    for evidence in entry.get("evidence", []):
        if isinstance(evidence, dict) and evidence.get("source_id"):
            if evidence["source_id"] == "github_database" and evidence.get("evidence_type") == "aggregate_database":
                continue  # This is a snapshot of our own database, not corroboration.
            source_ids.add(str(evidence["source_id"]))
    return source_ids


def apply_approved_corrections(
    existing: dict[str, dict],
    candidates: list[dict],
    today: str,
) -> tuple[int, int]:
    """Apply only explicit maintainer approvals to community-only rows.

    Anonymous votes create review candidates but do not change the shipped
    database. An operator may set ``approved: true`` in the local review file;
    the next merge then decays the community report count or removes the row.
    Authoritative or mixed-source rows are never changed by this path.
    """

    decayed = 0
    removed = 0
    for candidate in candidates:
        if candidate.get("approved") is not True or candidate.get("applied_at"):
            continue
        number = candidate.get("number")
        entry = existing.get(number) if isinstance(number, str) else None
        if entry is None:
            candidate["skipped_reason"] = "row_missing"
            candidate["reviewed_at"] = today
            continue
        if canonical_source_ids(entry) != {"community_reports"}:
            candidate["skipped_reason"] = "authoritative_source_present"
            candidate["reviewed_at"] = today
            continue
        try:
            votes = max(0, int(candidate.get("not_spam_votes", 0)))
            reports = max(0, int(entry.get("reports", 0)))
        except (TypeError, ValueError):
            candidate["skipped_reason"] = "invalid_counts"
            candidate["reviewed_at"] = today
            continue
        if votes <= 0 or reports <= 0:
            candidate["skipped_reason"] = "no_active_contribution"
            candidate["reviewed_at"] = today
            continue
        remaining = max(0, reports - votes)
        if remaining == 0:
            del existing[number]
            removed += 1
        else:
            entry["reports"] = remaining
            decayed += 1
        candidate["applied_at"] = today
        candidate["applied_not_spam_votes"] = votes
    return decayed, removed


def load_approved_numbers() -> list[dict]:
    """Read the maintainer's approvals; a missing file means none.

    A file that exists but can't be read stops the merge. Skipping it would
    quietly drop approvals the maintainer made on purpose.
    """
    if not APPROVED_NUMBERS_FILE.exists():
        return []
    payload = json.loads(APPROVED_NUMBERS_FILE.read_text(encoding="utf-8"))
    approved = payload.get("approved") if isinstance(payload, dict) else None
    if not isinstance(approved, list):
        raise TypeError(f"{APPROVED_NUMBERS_FILE.name} must hold an 'approved' list")
    return approved


def validated_approvals(approvals: list[dict], today: str) -> tuple[list[dict], set[str]]:
    """Check every entry, then split the approvals from the revoked numbers.

    Each number gets one entry. Listed twice with different dates, a number's
    evidence flipped on every merge and moved the database version each time,
    so every phone downloaded it again. To take an approval back, the entry
    stays and gains ``"revoked": true`` and a ``revoked_at`` date, which keeps
    the decision on the record.
    """
    active: list[dict] = []
    revoked: set[str] = set()
    seen: set[str] = set()
    for approval in approvals:
        if not isinstance(approval, dict):
            raise TypeError(f"{APPROVED_NUMBERS_FILE.name}: every approval must be an object")
        number = validated_report_number(str(approval.get("number", "")))
        if not number:
            raise ValueError(f"{APPROVED_NUMBERS_FILE.name}: {approval.get('number')!r} is not a plausible number")
        if number in seen:
            raise ValueError(f"{APPROVED_NUMBERS_FILE.name}: {number} is listed more than once. Keep one entry per number")
        seen.add(number)
        is_revoked = approval.get("revoked", False)
        if not isinstance(is_revoked, bool):
            raise TypeError(f"{APPROVED_NUMBERS_FILE.name}: {number} needs revoked set to true or false")
        if is_revoked:
            revoked_at = approval.get("revoked_at")
            if not isinstance(revoked_at, str) or not _is_valid_day(revoked_at, today):
                raise ValueError(f"{APPROVED_NUMBERS_FILE.name}: {number} needs a revoked_at date no later than today")
            revoked.add(number)
            continue
        reviewed = approval.get("reviewed_at")
        reference = approval.get("reference")
        spam_type = approval.get("type")
        if not isinstance(reviewed, str) or not _is_valid_day(reviewed, today):
            raise ValueError(f"{APPROVED_NUMBERS_FILE.name}: {number} needs a reviewed_at date no later than today")
        if not isinstance(reference, str) or not reference.startswith("https://"):
            raise ValueError(f"{APPROVED_NUMBERS_FILE.name}: {number} needs an https reference to the report")
        if not isinstance(spam_type, str) or not spam_type.replace("_", "").isalpha():
            raise ValueError(f"{APPROVED_NUMBERS_FILE.name}: {number} needs a type such as telemarketer or scam")
        active.append({"number": number, "type": spam_type, "reviewed_at": reviewed})
    return active, revoked


def has_maintainer_review(entry: dict) -> bool:
    return MAINTAINER_SOURCE in entry.get("sources", []) or any(
        isinstance(item, dict) and item.get("source_id") == MAINTAINER_SOURCE for item in entry.get("evidence") or []
    )


def require_listed_reviews(existing: dict[str, dict], listed: set[str]) -> None:
    """Stop when a reviewed row's entry was deleted instead of revoked.

    Deleting the entry took nothing back. The review stayed in the row as its
    evidence for two years, and the row stayed published on it.
    """
    for number, entry in existing.items():
        if number not in listed and has_maintainer_review(entry):
            raise ValueError(
                f"{APPROVED_NUMBERS_FILE.name} no longer lists {number}, which carries a maintainer review. "
                "Put the entry back with revoked set to true and a revoked_at date"
            )


def keeps_community_ledger(entry: dict) -> bool:
    """A reviewed row whose only other support is community reports.

    Its reports stay in the pending ledger with their real days and reporters,
    so revoking the review hands them back to the community gate intact.
    """
    support = canonical_source_ids(entry)
    return MAINTAINER_SOURCE in support and support - {MAINTAINER_SOURCE} <= {"community_reports"}


def revoke_maintainer_approvals(
    existing: dict[str, dict],
    pending: dict[str, dict],
    revoked: set[str],
) -> tuple[int, int]:
    """Take the review back off every row whose approval is revoked.

    Its evidence, source and description come off. A row another source still
    backs stays published without them. A row with community reports in the
    ledger goes back to the community gate, which runs right after this and
    keeps it or unpublishes it on those reports alone. A row the review alone
    carried, or whose reports have aged out of the ledger, is unpublished.
    Running this twice changes nothing.
    """
    released = 0
    removed = 0
    for number in sorted(revoked):
        entry = existing.get(number)
        if entry is None or not has_maintainer_review(entry):
            continue
        evidence = [
            item for item in entry.get("evidence") or []
            if not (isinstance(item, dict) and item.get("source_id") == MAINTAINER_SOURCE)
        ]
        if evidence:
            entry["evidence"] = evidence
        else:
            entry.pop("evidence", None)
        entry["sources"] = [source for source in entry.get("sources", []) if source != MAINTAINER_SOURCE]
        entry["description"] = "; ".join(
            part for part in entry.get("description", "").split("; ") if part != MAINTAINER_DESCRIPTION
        )
        support = canonical_source_ids(entry)
        state = pending.get(number)
        if support - {"community_reports"}:
            released += 1
            continue
        if support and state is not None and (state["published"] or state["events"]):
            if not state["published"]:
                # The review published it, so its count and dates were partly
                # the review's. The reports alone decide them now.
                days = [event["day"] for event in state["events"]]
                entry["reports"] = sum(event["count"] for event in state["events"])
                entry["first_seen"], entry["last_seen"] = min(days), max(days)
            entry["description"] = entry["description"] or COMMUNITY_DESCRIPTION
            state["entry"] = entry
            released += 1
            continue
        del existing[number]
        pending.pop(number, None)
        removed += 1
    return released, removed


def apply_maintainer_approvals(
    existing: dict[str, dict],
    pending: dict[str, dict],
    approvals: list[dict],
    manifest: dict,
    today: str,
) -> tuple[int, int]:
    """Publish numbers the maintainer checked, with that review as their evidence.

    The community gate holds a number until independent reports back it, which
    is right for anonymous reports but leaves a number someone reported on
    GitHub waiting forever when it's a region no imported source covers
    (issue #27, a Munich number). The review is its own evidence record, so the
    row is no longer community-only and the quorum rechecks leave it alone. The
    pending reports still count toward it and stay in the ledger, along with
    later ones, in case the review is revoked. Running this twice changes nothing.
    """

    added = 0
    updated = 0
    for approval in approvals:
        number, reviewed, spam_type = approval["number"], approval["reviewed_at"], approval["type"]
        evidence = source_evidence(
            manifest,
            MAINTAINER_SOURCE,
            {"first_seen": reviewed, "last_seen": reviewed},
            retrieved_at=f"{reviewed}T00:00:00+00:00",
        )
        entry = existing.get(number)
        if entry is not None:
            merged = merge_evidence(entry.get("evidence"), [evidence])
            if merged == entry.get("evidence"):
                continue
            entry["evidence"] = merged
            if MAINTAINER_DESCRIPTION not in entry.get("description", ""):
                entry["description"] = f"{entry.get('description', '')}; {MAINTAINER_DESCRIPTION}".strip("; ")
            updated += 1
            continue

        state = pending.get(number)
        events = state["events"] if state else []
        days = [event["day"] for event in events] + [reviewed]
        base = dict(state["entry"]) if state else {}
        entry = {
            "number": number,
            "type": spam_type if base.get("type") in (None, "", "unknown") else base["type"],
            "reports": max(1, sum(event.get("count", 1) for event in events)),
            "first_seen": min(days),
            "last_seen": max(days),
            "description": f"{COMMUNITY_DESCRIPTION}; {MAINTAINER_DESCRIPTION}" if events else MAINTAINER_DESCRIPTION,
            "sources": sorted(set(base.get("sources", [])) | {COMMUNITY_SOURCE}) if events else [MAINTAINER_SOURCE],
            "evidence": [evidence],
        }
        existing[number] = entry
        added += 1
    return added, updated


def update_source_health_snapshot(
    database: dict,
    review_candidates: list[dict],
    *,
    quarantined_count: int,
    quarantined_this_run: int,
) -> None:
    if not SOURCE_SNAPSHOT_FILE.exists():
        return
    try:
        snapshot = json.loads(SOURCE_SNAPSHOT_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return
    if not isinstance(snapshot, dict) or not isinstance(snapshot.get("sources"), list):
        return
    snapshot["health"] = source_health_report(
        snapshot,
        database,
        {"candidates": review_candidates},
        quarantined_count=quarantined_count,
        quarantined_this_run=quarantined_this_run,
        generated_at=datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
    )
    atomic_write_json(SOURCE_SNAPSHOT_FILE, snapshot)


def sanitize_dates(db: dict, today: str) -> None:
    """Clamp structurally-impossible first_seen/last_seen values that older
    imports introduced (years far in the past or future). The entry is kept —
    only the dates are repaired — so the hot-list recency filter and any date
    display stay sane."""
    lo, hi = "2000-01-01", today
    for entry in db.get("numbers", []):
        fs = entry.get("first_seen", "")
        ls = entry.get("last_seen", "")
        fs_ok = lo <= fs <= hi
        ls_ok = lo <= ls <= hi
        if not fs_ok:
            entry["first_seen"] = ls if ls_ok else lo
        if not ls_ok:
            fs2 = entry.get("first_seen", "")
            entry["last_seen"] = fs2 if lo <= fs2 <= hi else hi


def main(argv: list[str] | None = None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--apply-reviewed-corrections",
        action="store_true",
        help="apply only review candidates explicitly marked approved: true",
    )
    parser.add_argument(
        "--github-issues",
        nargs="?",
        const="gh",
        metavar="ISSUES_JSON",
        help="list open [SPAM] issues with their pool state, read through gh or from a saved "
        "`gh issue list --json number,title,createdAt` file; they never change the counts",
    )
    args = parser.parse_args(argv)
    print("=== Merge Community Reports ===\n")

    if not REPORTS_DIR.exists() and not DB_FILE.exists():
        print("No reports directory or database found.")
        return

    report_files = list(REPORTS_DIR.glob("*.json")) if REPORTS_DIR.exists() else []
    if not report_files:
        print("No queued reports; checking community evidence.")

    report_digest = report_queue_digest(REPORTS_DIR)
    if report_files:
        for derived_file in (
            DATA_DIR / "hot_numbers.json",
            DATA_DIR / "hot_ranges.json",
            DATA_DIR / "spam_domains.json",
        ):
            require_matching_derived_feed(derived_file, report_digest=report_digest)

    print(f"Found {len(report_files)} report files")

    # Load existing database
    if DB_FILE.exists():
        with open(DB_FILE) as f:
            db = json.load(f)
    else:
        db = {
            "version": 1,
            "updated": datetime.now().strftime("%Y-%m-%d"),
            "description": "CallShield community spam number database",
            "sources": ["ftc_complaints", "fcc_complaints", "community_reports"],
            "numbers": [],
            "prefixes": [],
        }

    today = datetime.now(timezone.utc).date().isoformat()
    pending_cutoff = (date.fromisoformat(today) - timedelta(days=COMMUNITY_PENDING_DAYS)).isoformat()
    watch_cutoff = (date.fromisoformat(today) - timedelta(days=COMMUNITY_WATCH_DAYS)).isoformat()
    pending = load_community_pending(today)
    sanitize_dates(db, today)

    # Self-heal: drop fictional/implausible rows that older bulk imports let in
    # (e.g. FCC-complaint entries with area/exchange 555). The report ingest
    # path already validates, but these predate that guard.
    before = len(db["numbers"])
    db["numbers"] = [n for n in db["numbers"] if is_plausible_number(n.get("number", ""))]
    purged = before - len(db["numbers"])
    if purged:
        print(f"Purged {purged} implausible existing rows")

    # Migrate legacy rows to an explicit provenance field once. Human-readable
    # descriptions are presentation text and must never decide whether an
    # anonymous vote can weaken an authoritative entry.
    provenance_migrated = 0
    for entry in db["numbers"]:
        sources = entry.get("sources")
        if isinstance(sources, list) and all(isinstance(source, str) for source in sources):
            continue
        entry["sources"] = [
            COMMUNITY_SOURCE if entry.get("description") == COMMUNITY_DESCRIPTION else LEGACY_SOURCE
        ]
        provenance_migrated += 1

    # Repeat submissions of the same number+verdict seconds apart are one
    # reporter, not corroboration. Counting them inflates the shipped `reports`
    # value and, for not_spam, lets a single voter de-list a genuine community
    # row one vote at a time. See report_dedup for why the Worker's own dedup
    # cannot be relied on. Unreadable files are ignored here and quarantined by
    # the main loop below.
    peeked = []
    for report_file in report_files:
        try:
            with open(report_file) as f:
                peek = json.load(f)
        except (OSError, ValueError):
            continue
        peeked_number = validated_report_number(peek.get("number", ""))
        if not peeked_number:
            continue
        peeked.append((report_file, peeked_number, peek))
    # One report the app sent twice under the same id counts once, even when the
    # resend came from another network and so from another reporter bucket.
    resent_reports = find_resent_reports(
        (validated_report_id(peek.get("report_id")), parse_reported_at(peek.get("reported_at")), report_file.name)
        for report_file, _, peek in peeked
    )
    # A resend whose original went in with an earlier drain.
    merged_ids = load_merged_report_ids()
    previously_merged = {
        report_file.name for report_file, _, peek in peeked if validated_report_id(peek.get("report_id")) in merged_ids
    }
    burst_candidates = []
    identity_duplicates = set()
    seen_reporter_days = set()
    for report_file, peeked_number, peek in peeked:
        if report_file.name in resent_reports:
            continue
        # Keyed on the vote, not the type: a reporter who picks a different
        # type for each report is still one reporter that day. A row from
        # another source keeps no ledger, so nothing later would catch it.
        vote = "not_spam" if peek.get("type") == "not_spam" else "spam"
        reported_at = parse_reported_at(peek.get("reported_at"))
        identity = reporter_identity(peek)
        if identity is None or reported_at is None:
            burst_candidates.append(((peeked_number, vote), reported_at, report_file.name))
        else:
            report_day = reported_at.astimezone(timezone.utc).date().isoformat()
            identity_key = (peeked_number, vote, report_day, *identity)
            if identity_key in seen_reporter_days:
                identity_duplicates.add(report_file.name)
            else:
                seen_reporter_days.add(identity_key)
    burst_duplicates = find_burst_duplicates(burst_candidates)

    existing = {n["number"]: n for n in db["numbers"]}

    # Approvals are checked before anything changes. A revoked one comes off
    # before the quorum recheck, which would otherwise read the review's date
    # as a community report day.
    approvals, revoked_numbers = validated_approvals(load_approved_numbers(), today)
    require_listed_reviews(existing, {approval["number"] for approval in approvals} | revoked_numbers)
    revoked_released, revoked_removed = revoke_maintainer_approvals(existing, pending, revoked_numbers)
    if revoked_released or revoked_removed:
        print(f"Revoked approvals: {revoked_removed} rows unpublished, {revoked_released} left to their other sources")

    demoted = 0
    for number, entry in list(existing.items()):
        if canonical_source_ids(entry) != {"community_reports"}:
            if not keeps_community_ledger(entry):
                pending.pop(number, None)
            continue
        state = pending.setdefault(number, legacy_community_state(entry, today))
        if state["published"] and (state.get("bucket_quorum") or not any(event["bucket"] for event in state["events"])):
            continue
        if community_has_quorum(state):
            promote_community_row(state)
        else:
            state["published"] = False
            del existing[number]
            demoted += 1

    added = 0
    updated = 0
    skipped = 0
    unattributed_votes = 0
    collapsed = 0
    rejected = 0
    # Implausible submissions are consumed silently otherwise, so a drain that
    # dropped a third of the queue reads the same as one that dropped nothing.
    # Counting them by raw value keeps the run auditable without reprinting one
    # line per file for a burst of the same fictional number.
    implausible: Counter[str] = Counter()
    processed_files = []
    counted_ids: set[str] = set()
    already_merged = 0
    expired_reports = 0
    not_spam_votes: dict[str, set[str]] = {}
    rejected_dir = REPORTS_DIR / "rejected"

    for report_file in report_files:
        try:
            with open(report_file) as f:
                report = json.load(f)
        except (OSError, ValueError) as e:
            print(f"  Quarantining unreadable {report_file.name}: {e}")
            quarantine(report_file, rejected_dir)
            rejected += 1
            continue

        try:
            number = validated_report_number(report.get("number", ""))
            if not number:
                # Junk / fictional / malformed number — drop the noise report.
                raw = report.get("number")
                implausible[raw if isinstance(raw, str) and raw else "<missing>"] += 1
                processed_files.append(report_file)
                skipped += 1
                continue

            if report_file.name in previously_merged:
                processed_files.append(report_file)
                already_merged += 1
                continue

            if (
                report_file.name in burst_duplicates
                or report_file.name in identity_duplicates
                or report_file.name in resent_reports
            ):
                # Same number and verdict as a report already counted seconds
                # earlier. Consume the file so it does not linger in the queue.
                processed_files.append(report_file)
                collapsed += 1
                continue

            report_id = validated_report_id(report.get("report_id"))

            spam_type = report.get("type", "unknown")
            parsed_at = parse_reported_at(report.get("reported_at"))
            reported_at = parsed_at.astimezone(timezone.utc).date().isoformat() if parsed_at else today
            reported_at = min(reported_at, today)

            # Handle false-positive reports: collect one vote per reporter for review.
            # SECURITY: anonymous not_spam votes may only weaken COMMUNITY rows.
            # Authoritative FCC/FTC entries are immune to anonymous removal,
            # otherwise a stream of not_spam reports could de-list real spammers.
            if spam_type == "not_spam":
                if reported_at >= watch_cutoff and (
                    number not in existing or canonical_source_ids(existing[number]) == {"community_reports"}
                ):
                    state = pending.get(number)
                    if state is None:
                        state = empty_community_state(number, "unknown", reported_at)
                        pending[number] = state
                    not_spam_days = state.setdefault("not_spam_days", [])
                    if reported_at not in not_spam_days:
                        not_spam_days.append(reported_at)
                reporter_bucket = validated_reporter_bucket(report.get("reporter_bucket"))
                if reporter_bucket:
                    not_spam_votes.setdefault(number, set()).add(reporter_bucket)
                else:
                    # A vote with no reporter identity cannot be counted as an
                    # independent source, so it is dropped. Tracked separately
                    # from implausible numbers: the number was fine, the
                    # provenance was not, and reporting both under one
                    # "implausible" total hides a stale Worker.
                    unattributed_votes += 1
            else:
                identity = reporter_identity(report)
                if reported_at >= watch_cutoff and identity and (
                    number not in existing or canonical_source_ids(existing[number]) == {"community_reports"}
                ):
                    state = pending.get(number)
                    if state is None:
                        state = empty_community_state(number, spam_type, reported_at)
                        pending[number] = state
                    add_watch_event(state, reported_at, identity)
                if reported_at < pending_cutoff and number not in existing:
                    # Too old to count toward promoting a new row. A row that is already
                    # in the database (FCC-backed or published) still takes the report.
                    expired_reports += 1
                else:
                    bucket = validated_reporter_bucket(report.get("reporter_bucket"))
                    key = f"id:{report_id}" if report_id else f"file:{report_file.name}"
                    event = {"key": key, "day": reported_at, "bucket": bucket, "count": 1}
                    if parsed_at and parsed_at.astimezone(timezone.utc).date().isoformat() == reported_at:
                        event["at"] = parsed_at.astimezone(timezone.utc).isoformat(timespec="seconds")
                    state = pending.get(number)
                    if state and (
                        any(event["key"] == key for event in state["events"])
                        or (bucket and any(event["bucket"] == bucket and event["day"] == reported_at for event in state["events"]))
                    ):
                        collapsed += 1
                    elif number in existing:
                        entry = existing[number]
                        entry["reports"] += 1
                        entry["sources"] = sorted(set(entry.get("sources", [])) | {COMMUNITY_SOURCE})
                        if reported_at > entry.get("last_seen", ""):
                            entry["last_seen"] = reported_at
                        if state is None and keeps_community_ledger(entry):
                            state = {
                                "published": False,
                                "entry": {
                                    "number": number,
                                    "type": entry.get("type", spam_type),
                                    "reports": 0,
                                    "first_seen": reported_at,
                                    "last_seen": reported_at,
                                    "description": COMMUNITY_DESCRIPTION,
                                    "sources": [COMMUNITY_SOURCE],
                                },
                                "events": [],
                                "watch_events": [],
                                "not_spam_days": [],
                            }
                            pending[number] = state
                        if state:
                            state["events"].append(event)
                            if state["published"]:
                                state["entry"] = entry
                            if (
                                bucket
                                and state["published"]
                                and not state.get("bucket_quorum")
                                and not has_maintainer_review(entry)
                                and not community_has_quorum(state)
                            ):
                                state["published"] = False
                                del existing[number]
                                demoted += 1
                        updated += 1
                    else:
                        if state is None:
                            entry = {
                                "number": number,
                                "type": spam_type,
                                "reports": 0,
                                "first_seen": reported_at,
                                "last_seen": reported_at,
                                "description": COMMUNITY_DESCRIPTION,
                                "sources": [COMMUNITY_SOURCE],
                            }
                            state = {"published": False, "entry": entry, "events": []}
                            pending[number] = state
                        state["events"].append(event)
                        if community_has_quorum(state):
                            days = [event["day"] for event in state["events"]]
                            entry = state["entry"]
                            entry["reports"] = sum(event["count"] for event in state["events"])
                            entry["first_seen"] = min(days)
                            entry["last_seen"] = max(days)
                            existing[number] = entry
                            promote_community_row(state)
                            added += 1
                if report_id:
                    counted_ids.add(report_id)

            processed_files.append(report_file)

        except Exception as e:  # noqa: BLE001 - malformed report content
            print(f"  Quarantining malformed {report_file.name}: {e}")
            quarantine(report_file, rejected_dir)
            rejected += 1

    approved_added, approved_updated = (0, 0)
    if approvals:
        approved_added, approved_updated = apply_maintainer_approvals(
            existing, pending, approvals, load_source_manifest(SOURCE_MANIFEST_FILE), today
        )
        if approved_added or approved_updated:
            print(f"Maintainer approvals: {approved_added} published, {approved_updated} existing rows marked reviewed")

    # Anonymous false-positive votes never mutate the shipped database by
    # default. A distinct-source quorum strictly larger than the spam count can
    # only park a community-only row for maintainer review; rows with any
    # authoritative provenance are not candidates at all.
    review_candidates = []
    for number, reporters in sorted(not_spam_votes.items()):
        entry = existing.get(number)
        if entry is None or canonical_source_ids(entry) != {"community_reports"}:
            continue
        report_count = max(0, int(entry.get("reports", 0)))
        if len(reporters) > report_count:
            review_candidates.append(
                {
                    "number": number,
                    "not_spam_votes": len(reporters),
                    "spam_reports": report_count,
                    "reported_at": today,
                    "source_ids": ["community_reports"],
                }
            )

    prior_candidates = load_review_candidates()
    by_number = {}
    for candidate in prior_candidates + review_candidates:
        number = candidate.get("number")
        if not isinstance(number, str):
            continue
        merged = dict(by_number.get(number, {}))
        merged.update(candidate)
        by_number[number] = merged
    review_candidates = list(by_number.values())

    decayed, removed = (0, 0)
    if args.apply_reviewed_corrections:
        decayed, removed = apply_approved_corrections(existing, review_candidates, today)

    pending = {
        number: state for number, state in pending.items()
        if (state["published"] and number in existing)
        or (not state["published"] and (state["events"] or state.get("watch_events") or state.get("not_spam_days")))
    }

    if review_candidates and (
        review_candidates != prior_candidates or args.apply_reviewed_corrections
    ):
        atomic_write_json(
            NOT_SPAM_REVIEW_FILE,
            {
                "updated": today,
                "count": len(by_number),
                "candidates": review_candidates,
            },
        )

    db["numbers"] = list(existing.values())
    # Only publish a new version when the contents actually changed. The app
    # re-syncs the whole 6.5 MB database whenever `version` moves, so bumping
    # it on a no-op run costs every device a pointless download.
    changed = (
        added > 0
        or updated > 0
        or purged > 0
        or provenance_migrated > 0
        or decayed > 0
        or removed > 0
        or demoted > 0
        or approved_added > 0
        or approved_updated > 0
        or revoked_released > 0
        or revoked_removed > 0
    )
    if changed:
        db["version"] += 1
        db["updated"] = today
        db["numbers"].sort(key=lambda x: x.get("reports", 0), reverse=True)
        atomic_write_json(DB_FILE, db)
        # Devices fetch the shards, and spam_numbers.txt is written with them,
        # so a rewrite of the database rewrites both.
        write_sharded_database(db, DB_FILE.parent)
    else:
        print("No changes — database version left at", db["version"])

    atomic_write_json(
        COMMUNITY_PENDING_FILE,
        {
            "schema_version": 1,
            "retention_days": COMMUNITY_PENDING_DAYS,
            "watch_retention_days": COMMUNITY_WATCH_DAYS,
            "numbers": dict(sorted(pending.items())),
        },
    )
    write_community_watch_feed(pending, existing, today, report_digest)

    # Before the files go, so a resend arriving later is still recognised.
    remember_merged_report_ids(merged_ids, counted_ids, today)

    # Delete processed report files only after DB is safely persisted
    for report_file in processed_files:
        try:
            os.remove(report_file)
        except OSError:
            pass

    # Remove reports dir if empty (rejected/ subdir keeps it around if any)
    if REPORTS_DIR.exists() and not list(REPORTS_DIR.iterdir()):
        REPORTS_DIR.rmdir()

    quarantined_count = len(list(rejected_dir.glob("*.json"))) if rejected_dir.exists() else 0
    update_source_health_snapshot(
        db,
        review_candidates,
        quarantined_count=quarantined_count,
        quarantined_this_run=rejected,
    )

    if implausible:
        print(f"\nRejected as implausible ({skipped} report(s), {len(implausible)} distinct):")
        for raw, count in sorted(implausible.items(), key=lambda item: (-item[1], item[0])):
            print(f"  {raw} x{count}")

    print(
        f"\nMerged: {added} new, {updated} updated, {skipped} skipped (implausible), "
        f"{collapsed} collapsed (duplicate), {already_merged} already merged in an earlier drain, "
        f"{unattributed_votes} not_spam votes dropped "
        f"(no reporter identity), {rejected} quarantined, "
        f"{expired_reports} expired reports, {demoted} uncorroborated rows held, "
        f"{decayed} corrections decayed, {removed} rows removed"
    )
    print(f"Total database: {len(db['numbers'])} numbers")

    if args.github_issues:
        try:
            issues = load_spam_issues(args.github_issues)
        except (OSError, ValueError, subprocess.SubprocessError) as error:
            print(f"\nCouldn't read the open {SPAM_ISSUE_PREFIX} issues: {error}")
        else:
            rows = spam_issue_rows(issues, existing, pending, today)
            print(f"\nOpen {SPAM_ISSUE_PREFIX} issues ({len(rows)}):")
            for row in rows:
                print(f"  {row}")


if __name__ == "__main__":
    main()
