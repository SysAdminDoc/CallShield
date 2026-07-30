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

import json
import os
import re
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from phone_normalization import validated_report_number

from pipeline_io import atomic_write_json

DATA_DIR = Path(os.environ.get("CALLSHIELD_DATA_DIR", Path(__file__).parent.parent / "data"))
REPORTS_DIR = Path(os.environ.get("CALLSHIELD_REPORTS_DIR", DATA_DIR / "reports"))
OUTPUT_FILE = DATA_DIR / "spam_domains.json"

MIN_REPORTS = 3    # Domain must be tied to 3+ DISTINCT reported numbers
MAX_DOMAINS = 500  # Top N domains

URL_RE = re.compile(r'https?://([^/\s\'"<>]+)|www\.([^\s/\'"<>]+)', re.IGNORECASE)
DOMAIN_RE = re.compile(r"^[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?$")

# Established legitimate domains — never flag these regardless of report count
DOMAIN_WHITELIST = {
    "google.com", "apple.com", "amazon.com", "microsoft.com",
    "facebook.com", "instagram.com", "twitter.com", "x.com",
    "youtube.com", "gmail.com", "icloud.com", "paypal.com",
    "github.com", "cloudflare.com", "amazonaws.com", "azure.com",
    "outlook.com", "yahoo.com", "usps.com", "fedex.com", "ups.com",
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
    if domain in DOMAIN_WHITELIST:
        return ""
    return domain


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


def main():
    print("=== CallShield Spam Domain Extractor ===\n")

    # Count DISTINCT reported numbers per domain, not report files. The worker
    # dedups per (IP, number) for only 5 minutes, so one reporter re-submitting
    # the same number could otherwise manufacture the MIN_REPORTS quorum for an
    # arbitrary domain and have it flagged malicious on every device.
    domain_numbers: dict[str, set[str]] = {}
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
                if not number:
                    continue

                reports_scanned += 1
                for domain in domains:
                    domain_numbers.setdefault(domain, set()).add(number)

            except Exception as e:
                print(f"  Skipping {report_file.name}: {e}")

    print(f"Scanned {reports_scanned} SMS spam reports")

    domain_counts: Counter = Counter({d: len(nums) for d, nums in domain_numbers.items()})

    # Filter to domains reported alongside enough distinct numbers
    spam_domains = [
        d for d, c in domain_counts.most_common()
        if c >= MIN_REPORTS
    ][:MAX_DOMAINS]

    output = {
        "generated": datetime.now(timezone.utc).isoformat(),
        "count": len(spam_domains),
        "min_reports": MIN_REPORTS,
        "domains": spam_domains,
    }

    atomic_write_json(OUTPUT_FILE, output)

    print(f"Spam domains: {len(spam_domains)} (min {MIN_REPORTS} reports)")
    print(f"Written to: {OUTPUT_FILE}")

    if spam_domains[:5]:
        print("\nTop 5 spam domains:")
        for d in spam_domains[:5]:
            print(f"  {d} ({domain_counts[d]} reports)")


if __name__ == "__main__":
    main()
