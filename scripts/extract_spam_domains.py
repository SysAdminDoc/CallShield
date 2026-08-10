#!/usr/bin/env python3
"""
CallShield Spam Domain Extractor

Scans community SMS reports for URLs, extracts root domains,
and outputs data/spam_domains.json. The Android app loads this
every 30 minutes to score SMS messages containing known phishing
or spam domains — a layer that regex alone cannot provide.

Called by the local/community report merge workflow and
during the weekly full database rebuild.
"""

import argparse
import json
import os
import re
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from publicsuffixlist import PublicSuffixList
from phone_normalization import validated_report_number

from pipeline_io import (
    FeedCollapseError,
    atomic_write_json,
    ensure_feed_not_collapsed,
    report_queue_digest,
)
from report_dedup import validated_reporter_bucket

DATA_DIR = Path(os.environ.get("CALLSHIELD_DATA_DIR", Path(__file__).parent.parent / "data"))
REPORTS_DIR = Path(os.environ.get("CALLSHIELD_REPORTS_DIR", DATA_DIR / "reports"))
OUTPUT_FILE = DATA_DIR / "spam_domains.json"
APPROVED_FILE = DATA_DIR / "spam_domains_approved.json"
REVIEW_FILE = DATA_DIR / "spam_domains_review.json"

MIN_REPORTS = 3    # Domain must be tied to 3+ DISTINCT reported numbers
MIN_REPORTERS = 2  # And at least two privacy-preserving reporter buckets
MAX_DOMAINS = 500  # Top N domains

URL_RE = re.compile(r'https?://([^/\s\'"<>]+)|www\.([^\s/\'"<>]+)', re.IGNORECASE)
DOMAIN_RE = re.compile(r"^[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?$")
PUBLIC_SUFFIX_LIST = PublicSuffixList()

# Established legitimate domains — never flag these regardless of report count
DOMAIN_WHITELIST = {
    "google.com", "apple.com", "amazon.com", "microsoft.com",
    "facebook.com", "instagram.com", "twitter.com", "x.com",
    "youtube.com", "gmail.com", "icloud.com", "paypal.com",
    "github.com", "cloudflare.com", "amazonaws.com", "azure.com",
    "outlook.com", "yahoo.com", "usps.com", "fedex.com", "ups.com",
    "chase.com", "bankofamerica.com", "wellsfargo.com", "citi.com",
    "capitalone.com", "americanexpress.com", "visa.com", "mastercard.com",
    "irs.gov", "ssa.gov", "medicare.gov", "healthcare.gov",
}


def extract_domain(raw: str) -> str:
    """Normalize a URL match to its root domain."""
    domain = raw.lower().split(":")[0].split("/")[0].split("?")[0].split("#")[0]
    if domain.startswith("www."):
        domain = domain[4:]
    return domain.strip()


def normalize_domain(raw: str) -> str:
    """Normalize and validate a report-provided domain indicator."""
    domain = extract_domain(raw).strip(".")
    if len(domain) < 5 or len(domain) > 253:
        return ""
    if "." not in domain:
        return ""
    if not DOMAIN_RE.match(domain):
        return ""
    labels = domain.split(".")
    if any(not label or len(label) > 63 for label in labels):
        return ""
    if any(label.startswith("-") or label.endswith("-") for label in labels):
        return ""
    registrable = PUBLIC_SUFFIX_LIST.privatesuffix(domain)
    if registrable is None:  # `co.uk`, `com`, etc. are registries, not hosts.
        return ""
    if registrable in DOMAIN_WHITELIST:
        return ""
    return domain


def approved_domains() -> set[str]:
    """Load the maintainer-approved set; newly observed domains stay in review."""
    if not APPROVED_FILE.exists():
        return set()
    try:
        payload = json.loads(APPROVED_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return set()
    raw_domains = payload.get("domains", []) if isinstance(payload, dict) else payload
    if not isinstance(raw_domains, list):
        return set()
    return {
        domain
        for raw in raw_domains
        if isinstance(raw, str) and (domain := normalize_domain(raw))
    }


def report_domains(report: dict) -> set[str]:
    """Return body-free domain indicators, falling back to legacy fixtures."""
    domains: set[str] = set()

    sms_domains = report.get("sms_domains")
    if isinstance(sms_domains, list):
        for raw in sms_domains:
            if isinstance(raw, str):
                domain = normalize_domain(raw)
                if domain:
                    domains.add(domain)

    # Backward compatibility only: historical local reports and older tests
    # used raw SMS text before the worker started dropping body fields.
    if not domains:
        sms_body = report.get("sms_body") or report.get("body") or ""
        if isinstance(sms_body, str):
            for match in URL_RE.finditer(sms_body):
                raw = match.group(1) or match.group(2) or ""
                domain = normalize_domain(raw)
                if domain:
                    domains.add(domain)

    return domains


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--allow-collapse",
        action="store_true",
        help="publish a smaller/empty feed after verifying the source or an intentional clear",
    )
    args = parser.parse_args(argv)
    print("=== CallShield Spam Domain Extractor ===\n")
    input_report_digest = report_queue_digest(REPORTS_DIR)

    # Count DISTINCT reported numbers per domain, not report files. The worker
    # dedups per (IP, number) for only 5 minutes, so one reporter re-submitting
    # the same number could otherwise manufacture the MIN_REPORTS quorum for an
    # arbitrary domain and have it flagged malicious on every device.
    domain_numbers: dict[str, set[str]] = {}
    domain_reporters: dict[str, set[str]] = {}
    reports_scanned = 0

    if REPORTS_DIR.exists():
        for report_file in sorted(REPORTS_DIR.glob("*.json")):
            try:
                with open(report_file) as f:
                    report = json.load(f)

                if report.get("type") == "not_spam":
                    continue

                domains = report_domains(report)
                if not domains:
                    continue

                number = validated_report_number(report.get("number", ""))
                reporter_bucket = validated_reporter_bucket(report.get("reporter_bucket"))
                if not number or not reporter_bucket:
                    continue

                reports_scanned += 1
                for domain in domains:
                    domain_numbers.setdefault(domain, set()).add(number)
                    domain_reporters.setdefault(domain, set()).add(reporter_bucket)

            except Exception as e:
                print(f"  Skipping {report_file.name}: {e}")

    print(f"Scanned {reports_scanned} SMS spam reports")

    domain_counts: Counter = Counter({d: len(nums) for d, nums in domain_numbers.items()})

    candidates = [
        domain
        for domain, count in domain_counts.most_common()
        if count >= MIN_REPORTS and len(domain_reporters.get(domain, set())) >= MIN_REPORTERS
    ]
    approved = approved_domains()
    spam_domains = [domain for domain in candidates if domain in approved][:MAX_DOMAINS]

    review_output = {
        "generated": datetime.now(timezone.utc).isoformat(),
        "input_report_digest": input_report_digest,
        "count": len([domain for domain in candidates if domain not in approved]),
        "candidates": [
            {
                "domain": domain,
                "distinct_numbers": domain_counts[domain],
                "distinct_reporters": len(domain_reporters[domain]),
            }
            for domain in candidates
            if domain not in approved
        ],
    }

    output = {
        "generated": datetime.now(timezone.utc).isoformat(),
        "input_report_digest": input_report_digest,
        "count": len(spam_domains),
        "min_reports": MIN_REPORTS,
        "min_distinct_reporters": MIN_REPORTERS,
        "domains": spam_domains,
    }

    try:
        ensure_feed_not_collapsed(
            OUTPUT_FILE,
            output,
            item_key="domains",
            absolute_floor=1,
            previous_ratio=0.10,
            allow_collapse=args.allow_collapse,
        )
    except FeedCollapseError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2

    # The primary feed is checked before either review or primary output is
    # replaced, preserving both artifacts if the source collapses.
    atomic_write_json(REVIEW_FILE, review_output)
    atomic_write_json(OUTPUT_FILE, output)

    print(
        f"Spam domains: {len(spam_domains)} approved "
        f"(min {MIN_REPORTS} numbers / {MIN_REPORTERS} reporters)"
    )
    print(f"Written to: {OUTPUT_FILE}")

    if spam_domains[:5]:
        print("\nTop 5 spam domains:")
        for d in spam_domains[:5]:
            print(f"  {d} ({domain_counts[d]} reports)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
