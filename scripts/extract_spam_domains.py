#!/usr/bin/env python3
"""
CallShield Spam Domain Extractor

Scans community SMS reports for URLs, extracts root domains,
and outputs data/spam_domains.json. The Android app loads this
every 30 minutes to score SMS messages containing known phishing
or spam domains — a layer that regex alone cannot provide.

Called by GitHub Actions after each community report merge and
during the weekly full database rebuild.
"""

import json
import re
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

DATA_DIR = Path(__file__).parent.parent / "data"
REPORTS_DIR = DATA_DIR / "reports"
OUTPUT_FILE = DATA_DIR / "spam_domains.json"

MIN_REPORTS = 3    # Domain must appear in 3+ distinct reports to be included
MAX_DOMAINS = 500  # Top N domains

URL_RE = re.compile(r'https?://([^/\s\'"<>]+)|www\.([^\s/\'"<>]+)', re.IGNORECASE)

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


def main():
    print("=== CallShield Spam Domain Extractor ===\n")

    domain_counts: Counter = Counter()
    reports_scanned = 0

    if REPORTS_DIR.exists():
        for report_file in sorted(REPORTS_DIR.glob("*.json")):
            try:
                with open(report_file) as f:
                    report = json.load(f)

                if report.get("type") == "not_spam":
                    continue

                # Support both sms_body and body field names
                sms_body = report.get("sms_body") or report.get("body") or ""
                if not sms_body:
                    continue

                reports_scanned += 1
                for match in URL_RE.finditer(sms_body):
                    raw = match.group(1) or match.group(2) or ""
                    domain = extract_domain(raw)
                    if domain and len(domain) >= 5 and "." in domain and domain not in DOMAIN_WHITELIST:
                        domain_counts[domain] += 1

            except Exception as e:
                print(f"  Skipping {report_file.name}: {e}")

    print(f"Scanned {reports_scanned} SMS spam reports")

    # Filter to domains with enough independent reports, rank by frequency
    spam_domains = [
        d for d, c in domain_counts.most_common(MAX_DOMAINS * 2)
        if c >= MIN_REPORTS
    ][:MAX_DOMAINS]

    output = {
        "generated": datetime.now(timezone.utc).isoformat(),
        "count": len(spam_domains),
        "min_reports": MIN_REPORTS,
        "domains": spam_domains,
    }

    with open(OUTPUT_FILE, "w") as f:
        json.dump(output, f, indent=2)

    print(f"Spam domains: {len(spam_domains)} (min {MIN_REPORTS} reports)")
    print(f"Written to: {OUTPUT_FILE}")

    if spam_domains[:5]:
        print("\nTop 5 spam domains:")
        for d in spam_domains[:5]:
            print(f"  {d} ({domain_counts[d]} reports)")


if __name__ == "__main__":
    main()
