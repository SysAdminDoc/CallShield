# CallShield Spam Database

This directory contains the spam number database that the CallShield app pulls from.

## Files

- `spam_numbers.json`: Main spam number database with individual numbers and prefix patterns
- `hot_numbers.json`: Recent community velocity feed for exact-number protection
- `hot_ranges.json`: Recent NPA-NXX campaign ranges derived from the hot feed
- `spam_domains.json`: Maintainer-approved SMS phishing/spam domains
- `spam_model_weights.json`: Versioned on-device GBT and logistic fallback weights
- `source-manifest.json`: Feed access, license, geography, attribution, and parser contract
- `source-snapshot.json`: Per-run source health, checksum, accepted/rejected counts, and failures
- `source-freshness.json`: Each upstream source's last successful import, kept across runs. The weekly liveness workflow checks daily and weekly sources against `stale_after_days`. A source with an `import_flag` in the manifest only imports with that flag, so it's checked once it has been imported and the failure message names the flag
- `spam_domains_approved.json`: Optional maintainer approval input for domain candidates
- `spam_domains_review.json`: Generated domain candidates awaiting approval
- `not_spam_review.json`: Generated community false-positive review candidates
- `merged_report_ids.json`: Ids of reports merged in the last 14 days, so a report the app resends after its original was merged counts once. The ids are random and already appear in the report files
- `reports/*.json`: Pending community reports. `reports/rejected/` holds quarantined files

## Consuming this data

These files are fetched directly by the app and, judging by the clone traffic,
by other tooling as well. This section is the contract for anyone reading them
from outside the app.

### Compatibility

`spam_numbers.manifest.json` carries `format_version`, which governs the shard
layout and the legacy snapshot together. Within one `format_version`:

- fields are never removed and never change meaning or type
- new optional fields may be added, so parse permissively and ignore unknown keys
- `version` is a monotonically increasing integer bumped on every content change
- `updated` is the UTC date (`YYYY-MM-DD`) of that change

A breaking change increments `format_version` and ships alongside the old
version for at least 90 days. `data/spam_numbers.json` is kept as a stable
legacy endpoint for older clients. Current builds should read the manifest and
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
| `spam_numbers.json` + shards | When the maintainer runs a merge | Every 6 hours |
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
  builds. The app doesn't call PhoneBlock at runtime either.

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
1. On first launch (and periodically), the app fetches the signed shard manifest, then only the shards whose hash changed. `spam_numbers.json` stays for older clients and as a fallback
2. Numbers are cached locally in a SQLite database for instant offline lookup
3. The app checks the database version number to know when to pull updates

## Data Sources
- **FTC Complaint Data**: Bulk imported from FTC Do Not Call Registry reports
- **FCC Complaints**: From FCC consumer complaint database
- **Community Reports**: Anonymous in-app reports, stored through the report Worker

## Regenerating the Database and Model (local)

The database, hot lists, and on-device ML model are **maintained locally** and
committed to the repo. There is **no CI/GitHub Actions pipeline**, and the app
pulls the committed `data/*.json` from this repo's raw URL. Regenerate on a
maintainer machine with Python 3.12:

```bash
pip install -r scripts/requirements.txt   # requests, scikit-learn, numpy

# 1. Rebuild the number database from all free public sources
python scripts/import_all_sources.py                       # writes data/spam_numbers.json
python scripts/update_ftc.py --max 50000                   # merge recent FTC complaints
# ToastedSpam serves plain HTTP only (no TLS), so it is skipped by default and a
# poisoned response can't ship hard-blocked numbers. Include it only from a
# trusted network: python scripts/import_all_sources.py --allow-insecure-sources

# 2. Regenerate the hot lists FIRST. They read data/reports/*.json, which the
#    merge step consumes. Each output records the report-queue digest, and the
#    merge refuses to run until all three derived feeds match that digest.
python scripts/generate_hot_list.py                        # trending numbers / NPA-NXX ranges
python scripts/extract_spam_domains.py                     # trending spam domains

# Use this only for a verified source outage or an intentional clear; otherwise
# the generators fail closed and preserve the previous feed. --allow-collapse
# forces the guard but publishes cleared=false, so devices keep their rows.
python scripts/generate_hot_list.py --allow-collapse
python scripts/extract_spam_domains.py --allow-collapse

# Add --cleared only when the emptiness is deliberate AND you want every device
# to DROP its local rows for that feed. It is per feed on purpose: approving a
# collapse of the numbers feed must not silently wipe campaign ranges.
python scripts/generate_hot_list.py --allow-collapse --cleared numbers,ranges
python scripts/extract_spam_domains.py --allow-collapse --cleared domains

# 3. Fold anonymous community reports into the main DB (consumes + clears data/reports/*.json;
#    junk/fictional numbers are dropped, unreadable reports quarantined to data/reports/rejected/)
python scripts/merge_community_reports.py

# 4. Retrain the on-device GBT scorer and emit versioned weights
python scripts/train_spam_model.py --output data/spam_model_weights.json

# 5. Evaluate the shipped model before committing (local quality gate)
python scripts/evaluate_model.py            # exits non-zero if CV F1 regresses

# 6. Sign what devices will download. The app refuses an unsigned or
#    mismatched feed and keeps its last good copy, and the validation
#    workflow fails a push that carries one.
python scripts/feed_signing.py sign
```

`train_spam_model.py` prints the learned per-feature weights and writes a
version-stamped `spam_model_weights.json` (GBT trees + a logistic-regression
fallback). `evaluate_model.py` reports precision/recall/F1 two ways: with the
exact **on-device** inference the app runs (so it catches export/inference
drift the trainer's sklearn-side metrics hide) and via stratified k-fold
cross-validation (an honest generalization estimate). It exits non-zero when the
cross-validated F1 drops below `--min-f1` so it can gate a bad retrain. The
import and merge scripts bump the database `version` themselves, so there's
nothing to edit by hand. Signing is the last step: a signed file changed after
step 6 no longer matches its `.sig`, needs `feed_signing.py sign` again, and
fails the validation run if it's pushed as it is. Commit the regenerated
`data/*.json` together with their `.sig` files.

### Feed signatures

Six files carry a detached signature beside them: `spam_numbers.json`,
`spam_numbers.manifest.json`, `hot_numbers.json`, `hot_ranges.json`,
`spam_domains.json` and `spam_model_weights.json`. Each `<file>.sig` holds a
base64 DER ECDSA P-256 (SHA-256) signature over the file's exact bytes, line
endings included. Shards aren't signed one by one: the signed manifest carries
each shard's SHA-256, and the app checks every shard against it.

The app compiles in the public keys it accepts
(`app/src/main/java/com/sysadmindoc/callshield/data/remote/FeedSignature.kt`)
and refuses a feed whose signature is missing or doesn't verify, keeping the
data it already has. That makes a feed's integrity independent of TLS pinning,
which is what failed from 2026-08-02 when GitHub's certificate changed.

Two keys are trusted, both P-256:

| Key | Public key (SubjectPublicKeyInfo, base64) |
|---|---|
| Signing key | `MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAESGK0kjIAEM7FP2RBLbWctHhYVP7LcNVJmWiuh6k6hkBGHfVXaqw+TOaSVQtbZLZeN5OThnqd0WTEF/CkBJ2gdA==` |
| Backup key | `MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE3eBrWqtgDaKc2HFC6EPtENrh8nlCH/bZ5PstgPpIJBVL8ZEf35UfwtbqWKJ/fQDi1pYKLmvMv/0OC3KSug/fxg==` |

The private keys stay on the maintainer's machine and never enter this
repository. `feed_signing.py` reads the signing key from
`CALLSHIELD_FEED_SIGNING_KEY`, or from `~/.callshield/feed-signing-key.pem`.
It refuses to sign with a key the app doesn't list, and it leaves a signature
alone while it still verifies, so an unchanged feed doesn't get a new `.sig` on
every run.

**Rotating a key.** Create the new key with
`python scripts/feed_signing.py generate-key <path>`, add the public key it
prints to `FeedSignature.kt`, and ship a release. Once that release is the one
people run, sign with the new key (`sign` re-signs every feed, since the old
signatures don't verify under it) and drop the old key from the app in a later
release. If the signing key is lost, sign with the backup key, which every
release already trusts, then rotate a new backup in. A leaked key has to come
out of the app in an urgent release, and installs that don't update keep
trusting it, which is why the backup key should stay offline.

### Mirrors and recovery

The app downloads from `raw.githubusercontent.com` first. If that host is
blocked where someone lives, or this repository ever moves, Settings > Feed
mirror takes a second base URL. The app appends the same paths it asks GitHub
for (`data/hot_numbers.json`, `data/hot_numbers.json.sig`, the manifest, the
shards) and tries the mirror after every GitHub branch has failed. For ten
minutes after GitHub couldn't be reached at all (a timeout, a refused
connection, a name that won't resolve), the mirror goes first, so a sync
behind a block doesn't wait out a timeout for every file. The copy bundled in
the APK is still the last resort.

Any static HTTPS host can be a mirror if it serves this `data/` tree byte for
byte, `.sig` files included. It needs no certificate pin. A mirrored feed goes
through the same signature check as one from GitHub, and a mirrored manifest
through the same version, date and digest checks, so a mirror can't hand out
unsigned data or roll a device back to an older database. jsDelivr is the
quickest mirror, and the app fills it in with one tap:

```
https://cdn.jsdelivr.net/gh/SysAdminDoc/CallShield@master/
```

jsDelivr caches files for up to 12 hours, so a phone using it can run half a
day behind. If the repository moves, the same URL pattern works with the new
owner and name, and users need the new address in Settings until a release
changes the default.

A cache can pair a newly published file with the previous signature. From
GitHub the app fetches both again at the commit the branch points to, which
can't mismatch. A mirror gets no second try, so the app refuses the pair and
keeps what it has until the mirror's cache catches up.
