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

## Consuming this data

These files are fetched directly by the app and, judging by the clone traffic,
by other tooling as well. This section is the contract for anyone reading them
from outside the app.

### Compatibility

`spam_numbers.manifest.json` carries `format_version`; it governs the shard
layout and the legacy snapshot together. Within one `format_version`:

- fields are never removed and never change meaning or type
- new optional fields may be added, so parse permissively and ignore unknown keys
- `version` is a monotonically increasing integer bumped on every content change
- `updated` is the UTC date (`YYYY-MM-DD`) of that change

A breaking change increments `format_version` and ships alongside the old
version for at least 90 days. `data/spam_numbers.json` is kept as a stable
legacy endpoint for older clients; current builds should read the manifest and
the 256 content-addressed shards under `spam_number_shards/` and fetch only the
shards whose hashes changed.

### Schemas

`spam_numbers.json`

| Field | Type | Notes |
|---|---|---|
| `version` | int | Monotonic; bumped on every content change |
| `updated` | string | UTC `YYYY-MM-DD` |
| `sources` | string[] | Source ids present in this build, matching `source-manifest.json` |
| `numbers[]` | object[] | `number` (E.164), `type`, `reports` (int), `first_seen`, `last_seen`, `description`, `sources[]` |
| `prefixes[]` | object[] | `prefix` (E.164 prefix), `type`, `description`. Prefix rows carry no per-row provenance |

`spam_numbers.manifest.json`

| Field | Type | Notes |
|---|---|---|
| `format_version` | int | Contract version for the shard layout |
| `version` / `updated` | int / string | Mirror the database values above |
| `legacy_path` | string | Where the single-file snapshot still lives |
| `shard_directory` | string | Directory holding the shards |
| `shard_count` | int | Currently 256, keyed by the first byte of the number hash |
| `shards[]` | object[] | Per shard: `id`, `path`, `sha256`, `bytes`, `numbers`, `prefixes`. Fetch only the shards whose `sha256` changed |

`hot_numbers.json`, `hot_ranges.json`, `spam_domains.json`

| Field | Type | Notes |
|---|---|---|
| `generated` | string | ISO-8601 UTC timestamp of the generating run |
| `input_report_digest` | string | SHA-256 of the report queue the run consumed |
| `count` | int | Number of entries, matching the items array |
| `cleared` | bool | **Load-bearing.** `true` means the publisher deliberately published an empty feed; `false` on an empty feed means the run produced nothing and consumers should keep what they already have rather than deleting rows |
| `numbers[]` / `ranges[]` / `domains[]` | array | The entries; each feed also echoes the thresholds it applied |

An empty feed with `cleared: false` should never be treated as an instruction to
delete. That distinction is the whole reason the field exists.

### Cadence

| File | Regenerated | Consumers should poll |
|---|---|---|
| `spam_numbers.json` + shards | On merge, roughly daily when reports arrive | Every 6 hours |
| `hot_numbers.json`, `hot_ranges.json`, `spam_domains.json` | Same run as the merge | Every 30 minutes |
| `spam_model_weights.json` | On retrain, irregular | With the database |
| `source-manifest.json` | On a feed change | With the database |

Conditional requests are honoured by GitHub raw. Use them.

### Licence and attribution

The repository is MIT, but the data carries obligations inherited from its
upstream feeds, and those travel with any redistribution. `source-manifest.json`
is authoritative: every source declares its `license`, `attribution`,
`redistributable` flag and `geography`. Two that matter in practice:

- **Saracroche** French range data is CC BY-NC-SA 4.0. Attribution must be
  retained and the non-commercial and share-alike terms pass downstream.
- **PhoneBlock** bulk data is not redistributable and is excluded from shipped
  builds; only the per-number hashed lookup is used at runtime.

Before redistributing any subset, read `redistributable` on every source listed
in the `sources` array of the rows you are taking.


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
