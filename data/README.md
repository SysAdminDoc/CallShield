# CallShield Spam Database

This directory contains the spam number database that the CallShield app pulls from.

## Files

- `spam_numbers.json` - Main spam number database with individual numbers and prefix patterns
- `hot_numbers.json` - Recent community velocity feed for exact-number protection
- `hot_ranges.json` - Recent NPA-NXX campaign ranges derived from the hot feed
- `spam_domains.json` - Maintainer-approved SMS phishing/spam domains
- `spam_model_weights.json` - Versioned on-device GBT and logistic fallback weights
- `source-manifest.json` - Feed access, license, geography, attribution, and parser contract
- `source-snapshot.json` - Per-run source health, checksum, accepted/rejected counts, and failures
- `spam_domains_approved.json` - Optional maintainer approval input for domain candidates
- `spam_domains_review.json` - Generated domain candidates awaiting approval
- `not_spam_review.json` - Generated community false-positive review candidates
- `reports/*.json` - Pending community reports; `reports/rejected/` contains quarantined files

## Contributing

### Report a Spam Number
1. [Open an Issue](../../issues/new?template=spam_report.md) with the number and details
2. Or submit a PR directly editing `spam_numbers.json`

### Format
```json
{
  "number": "+1XXXXXXXXXX",
  "type": "spam|robocall|scam|telemarketer|debt_collector|sms_spam|ai_voice|not_spam|unknown",
  "reports": 1,
  "first_seen": "YYYY-MM-DD",
  "last_seen": "YYYY-MM-DD",
  "description": "Brief description of the call"
}
```

## How the App Uses This Data
1. On first launch (and periodically), the app fetches `spam_numbers.json` from this repo's raw URL
2. Numbers are cached locally in a SQLite database for instant offline lookup
3. The app checks the database version number to know when to pull updates

## Data Sources
- **FTC Complaint Data** - Bulk imported from FTC Do Not Call Registry reports
- **FCC Complaints** - From FCC consumer complaint database
- **Community Reports** - User-submitted via GitHub Issues and PRs

## Regenerating the Database and Model (local)

The database, hot lists, and on-device ML model are **maintained locally** and
committed to the repo — there is **no CI/GitHub Actions pipeline** (the app then
pulls the committed `data/*.json` from this repo's raw URL). Regenerate on a
maintainer machine with Python 3.12:

```bash
pip install -r scripts/requirements.txt   # requests, scikit-learn, numpy

# 1. Rebuild the number database from all free public sources
python scripts/import_all_sources.py                       # writes data/spam_numbers.json
python scripts/update_ftc.py --max 50000                   # merge recent FTC complaints
# ToastedSpam serves plain HTTP only (no TLS) — it is skipped by default so a
# poisoned response can't ship hard-blocked numbers. Include it only from a
# trusted network: python scripts/import_all_sources.py --allow-insecure-sources

# 2. Regenerate the hot lists FIRST — they read data/reports/*.json, which the
#    merge step consumes. Each output records the report-queue digest, and the
#    merge refuses to run until all three derived feeds match that digest.
python scripts/generate_hot_list.py                        # trending numbers / NPA-NXX ranges
python scripts/extract_spam_domains.py                     # trending spam domains

# Use this only for a verified source outage or an intentional clear; otherwise
# the generators fail closed and preserve the previous feed.
python scripts/generate_hot_list.py --allow-collapse
python scripts/extract_spam_domains.py --allow-collapse

# 3. Fold anonymous community reports into the main DB (consumes + clears data/reports/*.json;
#    junk/fictional numbers are dropped, unreadable reports quarantined to data/reports/rejected/)
python scripts/merge_community_reports.py

# 4. Retrain the on-device GBT scorer and emit versioned weights
python scripts/train_spam_model.py --output data/spam_model_weights.json

# 5. Evaluate the shipped model before committing (local quality gate)
python scripts/evaluate_model.py            # exits non-zero if CV F1 regresses
```

`train_spam_model.py` prints the learned per-feature weights and writes a
version-stamped `spam_model_weights.json` (GBT trees + a logistic-regression
fallback). `evaluate_model.py` reports precision/recall/F1 two ways: with the
exact **on-device** inference the app runs (so it catches export/inference
drift the trainer's sklearn-side metrics hide) and via stratified k-fold
cross-validation (an honest generalization estimate); it exits non-zero when the
cross-validated F1 drops below `--min-f1` so it can gate a bad retrain. Bump the
`version` field in `spam_numbers.json` so clients re-sync, then commit the
regenerated `data/*.json`.
