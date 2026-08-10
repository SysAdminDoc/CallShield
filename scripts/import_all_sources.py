#!/usr/bin/env python3
"""
CallShield Multi-Source Database Builder

Aggregates spam phone numbers from ALL available free public sources
and merges them into data/spam_numbers.json.

Sources:
  1. FTC Do Not Call API (api.ftc.gov) — no key, DEMO_KEY
  2. FCC Unwanted Calls Dataset (opendata.fcc.gov) — Socrata API, no key
  3. PhoneBlock.net community database — optional operator key for bulk
  4. Saracroche French telemarketing ranges — daily public JSON feed
  5. Nomorobo IRS callback-scam feed — optional carrier-authorized URL
  6. ToastedSpam US/Canada blocklist — HTTP-only, explicit opt-in
  7. Existing CallShield database — preserves community reports

Usage:
    python import_all_sources.py
    python import_all_sources.py --max 500000
    python import_all_sources.py --max 500000 --min-reports 2
"""

import csv
import hashlib
import io
import json
import re
import sys
import time
import argparse
import os
from datetime import datetime, timezone
from pathlib import Path
from collections import Counter
from phone_normalization import (
    is_plausible_number,
    normalize_nanp_number,
    normalize_report_number,
)

from pipeline_io import atomic_write_json
from spam_shards import write_sharded_database
from source_registry import attach_source_evidence, load_source_manifest, merge_evidence, source_evidence, source_snapshot

try:
    import requests
except ImportError as exc:  # pragma: no cover - environment guard
    raise SystemExit(
        "requests is required. Install the pipeline dependencies: "
        "pip install -r scripts/requirements.txt"
    ) from exc

DATA_DIR = Path(__file__).parent.parent / "data"
DB_FILE = DATA_DIR / "spam_numbers.json"
SOURCE_MANIFEST_FILE = DATA_DIR / "source-manifest.json"
SOURCE_SNAPSHOT_FILE = DATA_DIR / "source-snapshot.json"
SOURCE_CURSOR_FILE = DATA_DIR / "source-cursors.json"

PHONEBLOCK_BLOCKLIST_URL = "https://phoneblock.net/phoneblock/api/blocklist"
SARACROCHE_PREFIX_URL = "https://saracroche.org/api/v1/lists/french-list-arcep-operators"
PHONEBLOCK_MAX_LIMIT = 5000
NOMOROBO_MAX_RECORDS = 250000
FTC_PAGE_SIZE = 50
FCC_PAGE_SIZE = 5000
SOURCE_CURSOR_SCHEMA_VERSION = 1
RETRYABLE_STATUS_CODES = {403, 429}


class SourceFetchResult(list[dict]):
    """List-compatible adapter result carrying a resumable high-water mark."""

    def __init__(
        self,
        entries: list[dict],
        *,
        cursor: dict[str, str] | None,
        complete: bool,
        error: str | None = None,
    ) -> None:
        super().__init__(entries)
        self.cursor = cursor
        self.complete = complete
        self.error = error


def _cursor_key(timestamp: str, record_id: str) -> tuple[str, str]:
    return timestamp or "", record_id or ""


def _record_cursor(
    record: dict,
    *,
    timestamp_field: str,
) -> dict[str, str] | None:
    timestamp = str(record.get(timestamp_field, "") or "").strip()
    record_id = str(record.get("id", "") or "").strip()
    if not timestamp:
        return None
    return {"timestamp": timestamp, "id": record_id}


def _cursor_after(
    record: dict,
    cursor: dict[str, str] | None,
    *,
    timestamp_field: str,
) -> bool:
    candidate = _record_cursor(record, timestamp_field=timestamp_field)
    if candidate is None or cursor is None:
        return True
    return _cursor_key(candidate["timestamp"], candidate["id"]) > _cursor_key(
        cursor.get("timestamp", ""),
        cursor.get("id", ""),
    )


def _max_cursor(
    records: list[dict],
    previous: dict[str, str] | None,
    *,
    timestamp_field: str,
) -> dict[str, str] | None:
    candidates = [
        candidate
        for record in records
        if (candidate := _record_cursor(record, timestamp_field=timestamp_field)) is not None
    ]
    if previous:
        candidates.append(previous)
    return max(candidates, key=lambda item: _cursor_key(item["timestamp"], item["id"]), default=None)


def _get_with_backoff(
    url: str,
    *,
    params: dict | None = None,
    timeout: int,
    label: str,
    max_attempts: int = 4,
):
    """GET a public feed with bounded 403/429 backoff and no partial success."""

    for attempt in range(max_attempts):
        try:
            response = requests.get(url, params=params, timeout=timeout)
        except Exception as exc:
            return None, str(exc)
        if response.status_code not in RETRYABLE_STATUS_CODES:
            return response, None
        if attempt == max_attempts - 1:
            return None, f"{label} returned HTTP {response.status_code} after {max_attempts} attempts"
        delay = min(60, 5 * (2**attempt))
        print(f"  {label} HTTP {response.status_code}; waiting {delay}s (retry {attempt + 1}/{max_attempts - 1})...")
        time.sleep(delay)
    return None, f"{label} request did not complete"


def load_source_cursors(path: Path = SOURCE_CURSOR_FILE) -> dict[str, dict[str, str]]:
    if not path.exists():
        return {}
    try:
        with path.open(encoding="utf-8") as cursor_file:
            payload = json.load(cursor_file)
        if payload.get("schema_version") != SOURCE_CURSOR_SCHEMA_VERSION:
            return {}
        sources = payload.get("sources", {})
        return {key: value for key, value in sources.items() if isinstance(value, dict)}
    except (OSError, ValueError, AttributeError):
        return {}


def save_source_cursors(
    cursors: dict[str, dict[str, str]],
    path: Path = SOURCE_CURSOR_FILE,
) -> None:
    atomic_write_json(
        path,
        {
            "schema_version": SOURCE_CURSOR_SCHEMA_VERSION,
            "sources": dict(sorted(cursors.items())),
        },
    )


def _fetch_stats(result: SourceFetchResult, retrieved_at: str) -> dict:
    return {
        "status": "ok" if result.complete else "error",
        "accepted": len(result),
        "checksum": payload_checksum(result),
        "last_success_at": retrieved_at if result.complete else None,
        "last_failure_at": retrieved_at if not result.complete else None,
        "error": result.error,
        "cursor": result.cursor,
    }


def payload_checksum(entries: list[dict]) -> str | None:
    """Return a stable checksum for the accepted adapter payload."""

    if not entries:
        return None
    encoded = json.dumps(entries, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def normalize_phone(raw: str) -> str | None:
    return normalize_nanp_number(raw)


def normalize_external_phone(raw: str) -> str | None:
    """Normalize a source number without inventing a country code.

    US feeds commonly contain local 10-digit values, so ``normalize_phone``
    remains NANP-only for those sources.  Community feeds such as PhoneBlock
    publish explicit E.164 values; accepting those here lets the importer
    retain useful international evidence while still rejecting malformed
    numbers at the trust boundary.
    """
    value = str(raw or "").strip()
    if not value.startswith("+"):
        return normalize_phone(value)
    normalized = normalize_report_number(value)
    return normalized if normalized and is_plausible_number(normalized) else None


# ── Source 1: FTC API ──────────────────────────────────────────────────
FTC_API_URL = "https://api.ftc.gov/v0/dnc-complaints"


def _failed_fetch(
    label: str,
    cursor: dict[str, str] | None,
    error: str,
) -> SourceFetchResult:
    print(f"  {label} unavailable; preserving the previous cursor ({error})")
    return SourceFetchResult([], cursor=cursor, complete=False, error=error)


def fetch_ftc(
    max_records: int = 5000,
    cursor: dict[str, str] | None = None,
) -> SourceFetchResult:
    """Fetch a bounded FTC window and return a commit-safe high-water mark."""

    print("\n[FTC Do Not Call API]")
    numbers: dict[str, dict] = {}
    cursor_records: list[dict] = []
    offset = 0
    records_fetched = 0
    previous_cursor = dict(cursor) if cursor else None
    now = datetime.now(timezone.utc).replace(microsecond=0).isoformat()

    while records_fetched < max_records:
        page_size = min(FTC_PAGE_SIZE, max_records - records_fetched)
        params = {
            "api_key": "DEMO_KEY",
            "items_per_page": page_size,
            "offset": offset,
            "sort_order": "asc" if cursor else "desc",
        }
        if cursor:
            # The FTC API requires the two date filters together. Keep the
            # lower bound inclusive and filter the exact high-water tuple
            # locally so records sharing a timestamp are not lost.
            params["created_date_from"] = f'"{cursor["timestamp"]}"'
            params["created_date_to"] = f'"{now}"'

        response, error = _get_with_backoff(
            FTC_API_URL,
            params=params,
            timeout=30,
            label="FTC",
        )
        if response is None:
            return _failed_fetch("FTC", previous_cursor, error or "request failed")
        try:
            response.raise_for_status()
            payload = response.json()
            records = payload.get("data", [])
            if not isinstance(records, list):
                raise ValueError("FTC response data is not an array")
        except Exception as exc:
            return _failed_fetch("FTC", previous_cursor, str(exc))
        if not records:
            break

        records_fetched += len(records)
        for record in records:
            attrs = record.get("attributes", {}) or {}
            if not isinstance(attrs, dict):
                attrs = {}
            cursor_record = {
                "id": str(record.get("id", "") or ""),
                "created-date": str(attrs.get("created-date", "") or ""),
            }
            if not _cursor_after(
                cursor_record,
                cursor,
                timestamp_field="created-date",
            ):
                continue
            if cursor_record.get("created-date"):
                cursor_records.append(cursor_record)

            phone = attrs.get("company-phone-number", "")
            normalized = normalize_external_phone(phone)
            if not normalized:
                continue

            subject = str(attrs.get("subject", "") or "").strip()
            is_robo = (
                str(attrs.get("recorded-message-or-robocall", "") or "").upper() == "Y"
                or str(attrs.get("is-robocall", "") or "").lower() in {"y", "yes", "true", "1"}
            )
            created_raw = str(attrs.get("created-date", "") or "")
            created = created_raw[:10]
            if not created:
                created = datetime.now(timezone.utc).strftime("%Y-%m-%d")
            description = f"FTC caller ID: {subject}" if subject else "FTC caller-ID complaint"
            if normalized in numbers:
                numbers[normalized]["reports"] += 1
                numbers[normalized]["first_seen"] = min(numbers[normalized]["first_seen"], created)
                numbers[normalized]["last_seen"] = max(numbers[normalized]["last_seen"], created)
                if description not in numbers[normalized]["description"]:
                    numbers[normalized]["description"] += f"; {description}"
            else:
                numbers[normalized] = {
                    "number": normalized,
                    "type": "robocall" if is_robo else "telemarketer",
                    "reports": 1,
                    "first_seen": created,
                    "last_seen": created,
                    "description": description,
                    "complaint_role": "caller_id",
                    "spoof_signal": "unverified_originating_number",
                }

        offset += len(records)
        if len(records) < page_size:
            break
        if records_fetched >= max_records:
            break
        time.sleep(0.5)

    next_cursor = _max_cursor(
        cursor_records,
        previous_cursor,
        timestamp_field="created-date",
    )
    print(f"  Fetched {len(numbers):,} unique numbers ({records_fetched:,} records)")
    return SourceFetchResult(
        list(numbers.values()),
        cursor=next_cursor,
        complete=True,
    )


# ── Source 2: FCC Unwanted Calls (Socrata) ─────────────────────────────
FCC_API_URL = "https://opendata.fcc.gov/resource/vakf-fz8e.json"


def fetch_fcc(
    max_records: int = 50000,
    cursor: dict[str, str] | None = None,
) -> SourceFetchResult:
    """Fetch a bounded Socrata window while retaining both phone roles."""

    print("\n[FCC Unwanted Calls Dataset]")
    numbers: dict[tuple[str, str, str], dict] = {}
    cursor_records: list[dict] = []
    offset = 0
    records_fetched = 0
    previous_cursor = dict(cursor) if cursor else None

    while records_fetched < max_records:
        batch = min(FCC_PAGE_SIZE, max_records - records_fetched)
        params = {
            "$limit": batch,
            "$offset": offset,
            "$order": "issue_date ASC, id ASC" if cursor else "issue_date DESC, id DESC",
        }
        if cursor:
            params["$where"] = f"issue_date >= '{cursor['timestamp']}'"

        response, error = _get_with_backoff(
            FCC_API_URL,
            params=params,
            timeout=60,
            label="FCC",
        )
        if response is None:
            return _failed_fetch("FCC", previous_cursor, error or "request failed")
        try:
            response.raise_for_status()
            records = response.json()
            if not isinstance(records, list):
                raise ValueError("FCC response is not an array")
        except Exception as exc:
            return _failed_fetch("FCC", previous_cursor, str(exc))
        if not records:
            break

        records_fetched += len(records)
        for record in records:
            record = record if isinstance(record, dict) else {}
            cursor_record = {
                "id": str(record.get("id", "") or ""),
                "issue_date": str(record.get("issue_date", "") or ""),
            }
            if not _cursor_after(cursor_record, cursor, timestamp_field="issue_date"):
                continue
            if cursor_record.get("issue_date"):
                cursor_records.append(cursor_record)

            issue = str(record.get("issue", "Unwanted Calls") or "Unwanted Calls").strip()
            call_type = str(record.get("type_of_call_or_messge", "") or "").strip()
            issue_context = " ".join(part for part in (issue, call_type) if part)
            issue_lower = issue_context.lower()
            date = str(record.get("issue_date", "") or "")[:10]
            if not date:
                date = datetime.now(timezone.utc).strftime("%Y-%m-%d")
            explicit_spoof = "spoof" in issue_lower

            spam_type = "robocall"
            if "telemarket" in issue_lower:
                spam_type = "telemarketer"
            elif "text" in issue_lower or "sms" in issue_lower:
                spam_type = "sms_spam"

            for field, role, default_signal, report_weight in (
                ("caller_id_number", "caller_id", "unverified_caller_id", 1),
                ("advertiser_business_phone_number", "callback_business", "callback_number_only", 0),
            ):
                normalized = normalize_external_phone(record.get(field, ""))
                if not normalized:
                    continue
                spoof_signal = "explicit_spoof_claim" if role == "caller_id" and explicit_spoof else default_signal
                effective_weight = 0 if spoof_signal == "explicit_spoof_claim" else report_weight
                key = (normalized, role, spoof_signal)
                description = f"FCC {role}: {issue or 'Unwanted Calls'}"
                if call_type:
                    description += f" ({call_type})"
                current = numbers.get(key)
                if current is None:
                    numbers[key] = {
                        "number": normalized,
                        "type": spam_type,
                        "reports": effective_weight,
                        "first_seen": date,
                        "last_seen": date,
                        "description": description,
                        "complaint_role": role,
                        "spoof_signal": spoof_signal,
                    }
                else:
                    current["reports"] += effective_weight
                    current["first_seen"] = min(current["first_seen"], date)
                    current["last_seen"] = max(current["last_seen"], date)
                    if description not in current["description"]:
                        current["description"] += f"; {description}"

        offset += len(records)
        if len(records) < batch:
            break
        if records_fetched >= max_records:
            break
        print(f"  {offset:,} records processed, {len(numbers):,} role-separated entries...")
        time.sleep(0.3)

    next_cursor = _max_cursor(
        cursor_records,
        previous_cursor,
        timestamp_field="issue_date",
    )
    print(f"  Fetched {len(numbers):,} role-separated entries ({records_fetched:,} records)")
    return SourceFetchResult(
        list(numbers.values()),
        cursor=next_cursor,
        complete=True,
    )


# ── Source 3: Nomorobo IRS callback-scam feed ─────────────────────────
def fetch_nomorobo_irs(
    feed_url: str | None = None,
    api_token: str | None = None,
    max_records: int = NOMOROBO_MAX_RECORDS,
) -> list[dict]:
    """Import a carrier-authorized Nomorobo IRS CSV feed when configured.

    Nomorobo distributes this callback-scam feed to approved carriers. The
    importer deliberately does not guess or scrape a private URL: operators
    pass the URL they received from Nomorobo, and an optional bearer token is
    read from the environment/CLI. A non-HTTPS URL is rejected because this
    data becomes a shipped hard-block list.
    """
    print("\n[Nomorobo IRS callback-scam feed]")
    if not feed_url:
        print("  Disabled (set --nomorobo-irs-url or NOMOROBO_IRS_FEED_URL)")
        return []
    if not feed_url.lower().startswith("https://"):
        print("  Skipped: Nomorobo feed URL must use HTTPS")
        return []

    headers = {"Accept": "text/csv, text/plain"}
    if api_token:
        headers["Authorization"] = f"Bearer {api_token}"
    try:
        response = requests.get(feed_url, headers=headers, timeout=90)
        if response.status_code in (401, 403):
            print("  Feed authorization rejected; skipped")
            return []
        response.raise_for_status()
        body = response.text
    except Exception as exc:
        print(f"  Error: {exc}")
        return []

    today = datetime.now().strftime("%Y-%m-%d")
    numbers = {}
    for row_index, row in enumerate(csv.reader(io.StringIO(body))):
        if row_index >= max_records:
            break
        # The IRS export has changed column names over time. Identify the
        # first plausible phone cell rather than depending on one schema.
        candidate = None
        for cell in row:
            value = str(cell or "").strip()
            if not value or not (value.startswith("+") or any(ch.isdigit() for ch in value)):
                continue
            normalized = normalize_external_phone(value)
            if normalized:
                candidate = normalized
                break
        if not candidate:
            continue
        description = "Nomorobo IRS callback-scam feed"
        row_text = " ".join(str(cell).lower() for cell in row)
        spam_type = "scam" if any(word in row_text for word in ("scam", "fraud", "irs")) else "robocall"
        if candidate in numbers:
            numbers[candidate]["reports"] += 1
            numbers[candidate]["last_seen"] = today
        else:
            numbers[candidate] = {
                "number": candidate,
                "type": spam_type,
                "reports": 3,
                "first_seen": today,
                "last_seen": today,
                "description": description,
            }

    print(f"  Fetched {len(numbers):,} numbers")
    return list(numbers.values())


# ── Source 3: PhoneBlock.net ───────────────────────────────────────────
def fetch_phoneblock(limit: int = 0, api_key: str | None = None) -> list[dict]:
    """Fetch PhoneBlock's public bulk list when the operator has access.

    PhoneBlock documents a versioned bulk endpoint, but current deployments
    require an API key/account for the download even though per-number lookups
    remain public.  Keep this source optional and fail closed on 401/403 so a
    scheduled import never replaces a healthy database with an empty snapshot.
    """
    print("\n[PhoneBlock.net Community Database]")
    if limit <= 0:
        print("  Bulk download disabled (use --phoneblock-limit to opt in)")
        print("  Per-number lookup remains available in-app without a bulk key")
        return []

    bounded_limit = min(limit, PHONEBLOCK_MAX_LIMIT)
    params = {"limit": bounded_limit}
    headers = {"Accept": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    try:
        response = requests.get(
            PHONEBLOCK_BLOCKLIST_URL,
            params=params,
            headers=headers,
            timeout=60,
        )
        if response.status_code in (401, 403):
            print("  Bulk download requires PhoneBlock credentials; skipped")
            return []
        response.raise_for_status()
        payload = response.json()
    except Exception as exc:
        print(f"  Error: {exc}")
        return []

    numbers = {}
    for row in payload.get("numbers", []):
        normalized = normalize_external_phone(row.get("phone", ""))
        if not normalized or int(row.get("votes", 0) or 0) <= 0:
            continue
        votes = int(row.get("votes", 1) or 1)
        rating = str(row.get("rating", "unknown"))
        spam_type = "scam" if rating in {"G_FRAUD", "F_GAMBLE"} else "telemarketer"
        activity = row.get("lastActivity")
        date = datetime.now().strftime("%Y-%m-%d")
        if activity:
            try:
                date = datetime.fromtimestamp(int(activity) / 1000).strftime("%Y-%m-%d")
            except (TypeError, ValueError, OSError):
                pass
        numbers[normalized] = {
            "number": normalized,
            "type": spam_type,
            "reports": votes,
            "first_seen": date,
            "last_seen": date,
            "description": f"PhoneBlock: {rating} ({votes} votes)",
        }

    print(
        f"  Fetched {len(numbers):,} numbers from version "
        f"{payload.get('version', 'unknown')}"
    )
    return list(numbers.values())


def fetch_saracroche_prefixes() -> list[dict]:
    """Fetch Saracroche's daily French telemarketing range feed.

    The endpoint publishes wildcard patterns such as ``33162######``.  The
    Android database has a safe, length-independent prefix matcher, so we
    retain only the fixed portion (the part before ``#``) and add the leading
    ``+`` required by the feed schema.  The source is intentionally range-only
    here: expanding 17M covered numbers into exact rows would make the APK and
    sync payload needlessly huge.
    """
    print("\n[Saracroche France telemarketing prefixes]")
    try:
        response = requests.get(SARACROCHE_PREFIX_URL, timeout=60)
        response.raise_for_status()
        payload = response.json()
    except Exception as exc:
        print(f"  Error: {exc}")
        return []

    prefixes = {}
    for row in payload.get("patterns", []):
        if str(row.get("action", "block")).lower() != "block":
            continue
        pattern = str(row.get("pattern", "")).strip()
        fixed = pattern.split("#", 1)[0]
        if not fixed.isdigit() or len(fixed) < 5 or len(fixed) > 15:
            continue
        prefix = f"+{fixed}"
        name = str(row.get("name", "French telemarketing range")).strip()
        prefixes[prefix] = {
            "prefix": prefix,
            "type": "telemarketer",
            "description": f"Saracroche: {name}",
        }

    print(
        f"  Fetched {len(prefixes):,} prefixes covering "
        f"{payload.get('blocked_numbers_count', 'unknown')} numbers "
        f"(version {payload.get('version', 'unknown')})"
    )
    return list(prefixes.values())


# ── Source 4: ToastedSpam (US/Canada curated plain-text blocklist) ────
def fetch_toastedspam(allow_insecure: bool = False) -> list[dict]:
    print("\n[ToastedSpam US/Canada Blocklist]")
    # toastedspam.com serves no TLS (verified 2026-07-30: https -> connection
    # failure). A cleartext fetch feeding elevated-confidence rows into the
    # shipped database is an on-path poisoning vector — every line an
    # attacker injects becomes a hard-blocked number on every device. Only
    # fetch when the operator explicitly opts in on a trusted network.
    if not allow_insecure:
        print("  Skipped: HTTP-only source (no TLS). Re-run with --allow-insecure-sources on a trusted network to include it.")
        return []
    try:
        resp = requests.get("http://www.toastedspam.com/deny", timeout=30)
        if resp.status_code != 200:
            print(f"  HTTP {resp.status_code}, skipping")
            return []
    except Exception as e:
        print(f"  Error: {e}")
        return []

    numbers = {}
    for line in resp.text.splitlines():
        line = line.strip()
        if not line or line.startswith('#') or line.startswith(';') or line.startswith('//'):
            continue
        normalized = normalize_phone(line)
        if normalized and normalized not in numbers:
            numbers[normalized] = {
                "number": normalized,
                "type": "robocall",
                "reports": 2,  # Curated list = elevated confidence
                "first_seen": datetime.now().strftime("%Y-%m-%d"),
                "last_seen": datetime.now().strftime("%Y-%m-%d"),
                "description": "ToastedSpam community blocklist",
            }

    print(f"  Fetched {len(numbers):,} numbers")
    return list(numbers.values())


# ── Source 5: ScamCallers community text lists (GitHub mirror) ─────────
def fetch_community_text_lists() -> list[dict]:
    print("\n[Community Text Blocklists]")
    sources = [
        # Repo-hosted plain-text spam number lists (one number per line, +1XXXXXXXXXX)
        ("https://raw.githubusercontent.com/sunlei/denylist/master/denylist.txt", "sunlei/denylist"),
    ]
    numbers = {}
    for url, name in sources:
        try:
            resp = requests.get(url, timeout=20)
            if not resp.ok:
                print(f"  {name}: HTTP {resp.status_code}, skipping")
                continue
            count = 0
            for line in resp.text.splitlines():
                line = line.strip()
                if not line or line.startswith('#'):
                    continue
                normalized = normalize_phone(line)
                if normalized:
                    if normalized in numbers:
                        numbers[normalized]["reports"] += 1
                    else:
                        numbers[normalized] = {
                            "number": normalized,
                            "type": "robocall",
                            "reports": 1,
                            "first_seen": datetime.now().strftime("%Y-%m-%d"),
                            "last_seen": datetime.now().strftime("%Y-%m-%d"),
                            "description": f"Community list: {name}",
                        }
                        count += 1
            print(f"  {name}: {count:,} numbers")
        except Exception as e:
            print(f"  {name}: Error — {e}")

    print(f"  Total from community lists: {len(numbers):,} unique numbers")
    return list(numbers.values())


COMPLAINT_SOURCE_IDS = {"ftc_complaints", "fcc_complaints"}


def complaint_is_corroborated(entry: dict) -> bool:
    """Require independent, non-spoof complaint evidence for new rows."""

    complaint_evidence = [
        item
        for item in entry.get("evidence", [])
        if item.get("source_id") in COMPLAINT_SOURCE_IDS
    ]
    if not complaint_evidence:
        return True
    source_ids = {item.get("source_id") for item in complaint_evidence}
    roles = {item.get("complaint_role") for item in complaint_evidence}
    if len(source_ids) < 2 or "caller_id" not in roles:
        return False
    # An explicit spoof claim is useful context, but cannot promote a row on
    # its own. A second, non-spoof signal may corroborate the same number.
    return any(item.get("spoof_signal") != "explicit_spoof_claim" for item in complaint_evidence)


def merge_into_database(
    all_numbers: list[dict],
    min_reports: int = 1,
    prefixes: list[dict] | None = None,
    source_names: set[str] | None = None,
    source_stats: dict[str, dict] | None = None,
):
    """Merge numbers. min_reports filters low-confidence single-source entries."""
    if DB_FILE.exists():
        with open(DB_FILE) as f:
            db = json.load(f)
    else:
        db = {
            "version": 1,
            "updated": datetime.now().strftime("%Y-%m-%d"),
            "description": "CallShield community spam number database",
            "sources": ["ftc_complaints", "fcc_complaints", "phoneblock", "toastedspam", "community_reports"],
            "numbers": [],
            "prefixes": [],
        }

    manifest = load_source_manifest(SOURCE_MANIFEST_FILE)
    legacy_retrieved_at = f"{db.get('updated', datetime.now().strftime('%Y-%m-%d'))}T00:00:00+00:00"
    for row in db["numbers"]:
        if not row.get("evidence"):
            row["evidence"] = [source_evidence(manifest, "github_database", row, retrieved_at=legacy_retrieved_at)]

    existing = {n["number"]: n for n in db["numbers"]}
    # Snapshot the pre-merge keys so the min_reports filter below can be
    # scoped to newly-added entries only. Rows already in the shipped
    # database (community reports land at reports=1) must survive a rerun.
    pre_existing = set(existing)

    added = 0
    updated = 0
    for entry in all_numbers:
        num = entry["number"]
        if num in existing:
            # Snapshot semantics (max, not +=): reruns re-fetch overlapping
            # source windows, and accumulation inflates counts without bound —
            # see update_ftc.py merge_into_database for the full rationale.
            changed = False
            reports = max(existing[num].get("reports", 0), entry["reports"])
            if reports != existing[num].get("reports", 0):
                existing[num]["reports"] = reports
                changed = True
            if entry.get("last_seen", "") > existing[num].get("last_seen", ""):
                existing[num]["last_seen"] = entry["last_seen"]
                changed = True
            if entry.get("first_seen", "9999") < existing[num].get("first_seen", "9999"):
                existing[num]["first_seen"] = entry["first_seen"]
                changed = True
            merged_evidence = merge_evidence(existing[num].get("evidence"), entry.get("evidence"))
            if merged_evidence != existing[num].get("evidence", []):
                existing[num]["evidence"] = merged_evidence
                changed = True
            description = entry.get("description", "")
            if description and description not in existing[num].get("description", ""):
                existing[num]["description"] = (
                    f"{existing[num].get('description', '')}; {description}"
                ).strip("; ")
                changed = True
            if changed:
                updated += 1
        else:
            existing[num] = entry
            added += 1

    # Persist source evidence even when the data payload is unchanged. A
    # successful no-op import is still useful: it proves the feeds were
    # reachable and keeps freshness visible to release review tooling.
    atomic_write_json(
        SOURCE_SNAPSHOT_FILE,
        source_snapshot(manifest, source_stats or {}),
    )

    # Apply min_reports filter to NEWLY-ADDED entries only. Applying it to the
    # whole merged dict deleted every community-reported row (they are written
    # at reports=1 by merge_community_reports.py) on the documented no-flag
    # regen, because --min-reports defaults to 2.
    filtered = 0
    filtered_uncorroborated = 0
    before = len(existing)
    retained = {}
    for key, value in existing.items():
        if key in pre_existing:
            retained[key] = value
            continue
        if not complaint_is_corroborated(value):
            filtered_uncorroborated += 1
            continue
        if value.get("reports", 0) < min_reports:
            continue
        retained[key] = value
    existing = retained
    filtered = before - len(existing)
    if filtered:
        print(
            f"  Filtered {filtered:,} new entries below min_reports={min_reports} "
            f"or without independent complaint corroboration "
            f"({filtered_uncorroborated:,} uncorroborated)"
        )

    existing_prefixes = {p["prefix"]: p for p in db.get("prefixes", []) if p.get("prefix")}
    prefix_added = 0
    prefix_updated = 0
    for entry in prefixes or []:
        prefix = entry.get("prefix", "")
        if not prefix:
            continue
        if prefix in existing_prefixes:
            before = existing_prefixes[prefix].copy()
            existing_prefixes[prefix].update(
                {
                    key: value
                    for key, value in entry.items()
                    if value not in (None, "")
                }
            )
            if existing_prefixes[prefix] != before:
                prefix_updated += 1
        else:
            existing_prefixes[prefix] = entry
            prefix_added += 1

    db["numbers"] = list(existing.values())
    db["prefixes"] = list(existing_prefixes.values())
    # Bump the published version only on a real change: the app re-downloads
    # the whole database whenever it moves.
    if added == 0 and updated == 0 and filtered == 0 and prefix_added == 0 and prefix_updated == 0:
        write_sharded_database(db, DB_FILE.parent)
        print("No changes — database version left at", db.get("version", 0))
        return
    db["version"] = db.get("version", 0) + 1
    db["updated"] = datetime.now().strftime("%Y-%m-%d")
    db["sources"] = sorted(
        set(db.get("sources", []))
        | (source_names or {"ftc_complaints", "fcc_complaints", "toastedspam", "community_reports"})
    )
    db["numbers"].sort(key=lambda x: x.get("reports", 0), reverse=True)
    db["prefixes"].sort(key=lambda x: x.get("prefix", ""))

    atomic_write_json(DB_FILE, db)
    write_sharded_database(db, DB_FILE.parent)

    print(f"\n{'='*50}")
    print(f"Database updated:")
    print(f"  Added:   {added:,}")
    print(f"  Updated: {updated:,}")
    print(f"  Prefixes added:   {prefix_added:,}")
    print(f"  Prefixes updated: {prefix_updated:,}")
    print(f"  Total:   {len(db['numbers']):,}")
    print(f"  Size:    {DB_FILE.stat().st_size / 1024:.1f} KB")
    print(f"  Version: {db['version']}")

    types = Counter(n["type"] for n in db["numbers"])
    print(f"\nBy type:")
    for t, count in types.most_common():
        print(f"  {t}: {count:,}")

    print(f"\nTop 10:")
    for n in db["numbers"][:10]:
        print(f"  {n['number']} — {n['reports']} reports — {n['description'][:50]}")


def main():
    parser = argparse.ArgumentParser(description="Import spam numbers from all sources")
    parser.add_argument("--max", type=int, default=500000, help="Max records to fetch from FCC")
    parser.add_argument("--min-reports", type=int, default=2, help="Minimum reports to include a number (default: 2)")
    parser.add_argument(
        "--phoneblock-limit",
        type=int,
        default=0,
        help="Opt into PhoneBlock bulk download (1-5000; credentials may be required)",
    )
    parser.add_argument(
        "--phoneblock-api-key",
        default=None,
        help="Optional PhoneBlock API key (prefer PHONEBLOCK_API_KEY env var)",
    )
    parser.add_argument(
        "--include-saracroche",
        action="store_true",
        help="Import Saracroche's French telemarketing prefix feed (CC BY-NC-SA)",
    )
    parser.add_argument(
        "--nomorobo-irs-url",
        default=None,
        help="Carrier-authorized Nomorobo IRS CSV URL (prefer NOMOROBO_IRS_FEED_URL)",
    )
    parser.add_argument(
        "--nomorobo-irs-token",
        default=None,
        help="Optional bearer token for the Nomorobo IRS feed (prefer NOMOROBO_IRS_TOKEN)",
    )
    parser.add_argument(
        "--allow-insecure-sources",
        action="store_true",
        help="Include HTTP-only sources (ToastedSpam). Only use on a trusted network.",
    )
    args = parser.parse_args()

    print("=" * 50)
    print("CallShield Multi-Source Database Builder")
    print("=" * 50)

    all_numbers = []
    source_stats: dict[str, dict] = {}
    manifest = load_source_manifest(SOURCE_MANIFEST_FILE)
    retrieved_at = datetime.now(timezone.utc).replace(microsecond=0).isoformat()

    # Source 1: FTC
    cursors = load_source_cursors()
    next_cursors = dict(cursors)

    ftc = fetch_ftc(  # FTC API is slow; bulk is via CSV
        max_records=min(args.max, 5000),
        cursor=cursors.get("ftc_complaints"),
    )
    attach_source_evidence(ftc, manifest, "ftc_complaints", retrieved_at=retrieved_at)
    all_numbers.extend(ftc)
    source_stats["ftc_complaints"] = _fetch_stats(ftc, retrieved_at)
    if ftc.complete and ftc.cursor:
        next_cursors["ftc_complaints"] = ftc.cursor

    # Source 2: FCC (biggest source — pull up to --max records)
    fcc = fetch_fcc(max_records=args.max, cursor=cursors.get("fcc_complaints"))
    attach_source_evidence(fcc, manifest, "fcc_complaints", retrieved_at=retrieved_at)
    all_numbers.extend(fcc)
    source_stats["fcc_complaints"] = _fetch_stats(fcc, retrieved_at)
    if fcc.complete and fcc.cursor:
        next_cursors["fcc_complaints"] = fcc.cursor

    # Source 3: PhoneBlock (per-number lookup — seed only, real-time in app)
    pb = fetch_phoneblock(
        limit=args.phoneblock_limit,
        api_key=args.phoneblock_api_key or os.environ.get("PHONEBLOCK_API_KEY"),
    )
    attach_source_evidence(pb, manifest, "phoneblock_bulk", retrieved_at=retrieved_at)
    all_numbers.extend(pb)
    source_stats["phoneblock_bulk"] = {
        "status": "ok" if pb else "not_requested",
        "accepted": len(pb),
        "checksum": payload_checksum(pb),
        "last_success_at": retrieved_at if pb else None,
    }

    nomorobo = fetch_nomorobo_irs(
        feed_url=args.nomorobo_irs_url or os.environ.get("NOMOROBO_IRS_FEED_URL"),
        api_token=args.nomorobo_irs_token or os.environ.get("NOMOROBO_IRS_TOKEN"),
    )
    attach_source_evidence(nomorobo, manifest, "nomorobo_irs", retrieved_at=retrieved_at)
    all_numbers.extend(nomorobo)
    source_stats["nomorobo_irs"] = {
        "status": "ok" if nomorobo else "not_requested",
        "accepted": len(nomorobo),
        "checksum": payload_checksum(nomorobo),
        "last_success_at": retrieved_at if nomorobo else None,
    }

    saracroche_prefixes = fetch_saracroche_prefixes() if args.include_saracroche else []
    attach_source_evidence(saracroche_prefixes, manifest, "saracroche_prefixes", retrieved_at=retrieved_at)
    source_stats["saracroche_prefixes"] = {
        "status": "ok" if saracroche_prefixes else "not_requested",
        "accepted": len(saracroche_prefixes),
        "checksum": payload_checksum(saracroche_prefixes),
        "last_success_at": retrieved_at if saracroche_prefixes else None,
    }

    # Source 4: ToastedSpam
    ts = fetch_toastedspam(allow_insecure=args.allow_insecure_sources)
    attach_source_evidence(ts, manifest, "toastedspam", retrieved_at=retrieved_at)
    all_numbers.extend(ts)
    source_stats["toastedspam"] = {
        "status": "ok" if ts else "not_requested",
        "accepted": len(ts),
        "checksum": payload_checksum(ts),
        "last_success_at": retrieved_at if ts else None,
    }

    # Source 5: Community text lists
    cl = fetch_community_text_lists()
    attach_source_evidence(cl, manifest, "community_text_lists", retrieved_at=retrieved_at)
    all_numbers.extend(cl)
    source_stats["community_reports"] = {
        "status": "ok",
        "accepted": len(cl),
        "checksum": payload_checksum(cl),
        "last_success_at": retrieved_at,
    }

    # Deduplicate across all sources (accumulate reports)
    deduped = {}
    for n in all_numbers:
        num = n["number"]
        if num in deduped:
            deduped[num]["reports"] += n["reports"]
            if n.get("first_seen", "") < deduped[num].get("first_seen", "9999"):
                deduped[num]["first_seen"] = n["first_seen"]
            if n.get("last_seen", "") > deduped[num].get("last_seen", ""):
                deduped[num]["last_seen"] = n["last_seen"]
            description = n.get("description", "")
            if description and description not in deduped[num].get("description", ""):
                deduped[num]["description"] = (
                    f"{deduped[num].get('description', '')}; {description}"
                ).strip("; ")
            deduped[num]["evidence"] = merge_evidence(deduped[num].get("evidence"), n.get("evidence"))
        else:
            deduped[num] = n

    print(f"\nTotal unique numbers from all sources: {len(deduped):,}")
    source_names = {
        "ftc_complaints",
        "fcc_complaints",
        "toastedspam",
        "community_reports",
        "community_text_lists",
    }
    if pb:
        source_names.add("phoneblock_bulk")
    if nomorobo:
        source_names.add("nomorobo_irs")
    if saracroche_prefixes:
        source_names.add("saracroche_prefixes")
    merge_into_database(
        list(deduped.values()),
        min_reports=args.min_reports,
        prefixes=saracroche_prefixes,
        source_names=source_names,
        source_stats=source_stats,
    )
    save_source_cursors(next_cursors)
    print("\nDone! Commit and push to update the live database.")


if __name__ == "__main__":
    main()
