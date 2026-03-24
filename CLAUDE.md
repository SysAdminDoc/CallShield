# CallShield — Project Notes

## Overview
Open-source Android spam call/text blocker. No subscriptions, no API keys required, no tracking.

**Released:** v1.2.3 (versionCode 6)

---

## Tech Stack
- Kotlin, Jetpack Compose, Material 3, AMOLED black + Catppuccin Mocha
- Room (SQLite v5) — 6 entities: spam_numbers, spam_prefixes, call_log, wildcard_rules, whitelist, sms_keyword_rules
- OkHttp, Moshi, DataStore Preferences, WorkManager
- Cloudflare Workers (community reports endpoint: `callshield-reports.snafumatthew.workers.dev`)
- Min SDK 29, Target SDK 35

---

## Detection Pipeline

### `SpamRepository.isSpam()` — all calls and SMS number checks
1. Manual whitelist
2. Contact whitelist (`SpamHeuristics.isInContacts`)
3. Callback detection (recently dialed = allow, `CallbackDetector`)
4. Repeated urgent caller (2× in 5 min = allow)
5. User blocklist (Room DB, `isUserBlocked = true`)
6. Database match (GitHub-synced spam_numbers.json + hot_list entries)
7. Prefix rules (wangiri/premium country codes)
8. Wildcard/regex rules
9. Quiet hours (time-based block)
10. Frequency auto-block (3+ calls from same number)
11. Heuristic engine (`SpamHeuristics.analyze`) — VoIP NPA-NXX (~60 ranges), wangiri country codes (~50), neighbor spoof, rapid-fire, **hot campaign range** (+35, "hot_campaign_range")
12. Caller ID overlay (suspicious 30–59 → `CallerIdOverlayService`, live lookup)
13. *(SMS only)* Keyword rules
14. *(SMS only)* SMS content analysis (`SmsContentAnalyzer`) — URL shorteners, suspicious TLDs, **spam domain blocklist** (+50, "spam_domain"), 30+ regex patterns
15. On-device ML scorer (`SpamMLScorer`) — 15-feature logistic regression, threshold 0.7, gated by `mlScorerEnabled`

### `SpamRepository.isSpamSms()` — additional SMS layers
Calls `isSpam()` first, then:
- **SMS context trust** (`SmsContextChecker`) — if user has sent to this number OR received from it on 2+ distinct days → allow through (runs before keyword/content checks)
- Keyword rules (custom user rules)
- SMS content analysis

### RCS Filter (`RcsNotificationListener`)
Runs independently as `NotificationListenerService`. Gated by `blockSmsEnabled` AND `rcsFilterEnabled`.
- Monitors: Google Messages, Samsung Messages, AOSP MMS, Microsoft SMS Organizer
- Cannot block RCS delivery — cancels notification only
- Sender with <7 digits = contact name → URL check only; ≥7 digits → full `isSpamSms()`
- Requires Notification Access (Settings deeplink button in app)

---

## Data Sources

### GitHub Database (weekly CI, `build.yml`)
- FCC Unwanted Calls: Socrata API, up to 500,000 records, min 2 reports filter
- FTC Do Not Call: api.ftc.gov (DEMO_KEY, ~50/day)
- ToastedSpam: community curated list
- Community text lists: additional curated blocklists
- Community reports: Cloudflare Worker → `data/reports/` → `merge_community_reports.py`
- Spam domains: `extract_spam_domains.py` → `data/spam_domains.json` (top 500 domains, min 3 reports)

### Hot List (30-minute CI + device sync, `HotListSyncWorker`)
Three files synced every 30 minutes via GitHub Actions and on-device worker:

| File | Contents | Used by |
|---|---|---|
| `data/hot_numbers.json` | Top 500 numbers trending in last 24h | Room DB (`source="hot_list"`) |
| `data/hot_ranges.json` | NPA-NXX prefixes with 3+ hot numbers (active campaigns) | `SpamHeuristics.updateHotRanges()` |
| `data/spam_domains.json` | Root domains from community SMS reports | `SmsContentAnalyzer.updateSpamDomains()` |

Hot ranges and spam domains are in-memory (`@Volatile Set<String>`) — not persisted to Room, start empty on fresh install.

### Live Lookup (overlay only — never used for blocking)
- SkipCalls, PhoneBlock, WhoCalledMe — spam report aggregators (free, no key)
- OpenCNAM — caller name (CNAM), free 60/hr, no key
- AbstractAPI — carrier/line-type enrichment, optional key, 250/mo free (`abstractApiKey` setting)

### URL Safety (async, post-decision — never blocks delivery)
- URLhaus (abuse.ch): no key. Checks SMS and RCS bodies for phishing/malware URLs.
- Fires `NotificationHelper.notifyPhishingUrl()` if malicious URL found.

---

## ML Scorer (`SpamMLScorer`)
Pure Kotlin logistic regression — no TFLite, no extra deps. Version 2 (15 features).

| # | Feature | Description |
|---|---|---|
| 1 | toll_free | 800/888/877/etc. prefix |
| 2 | high_spam_npa | Area code in high FTC/FCC complaint set |
| 3 | voip_range | NPA-NXX in known high-spam VoIP carrier range |
| 4 | repeated_digits_ratio | Fraction occupied by most-common digit |
| 5 | sequential_asc_ratio | Ascending sequential digit pairs / 9 |
| 6 | all_same_digit | All 10 digits identical |
| 7 | nxx_555 | Exchange is 555 (unassigned) |
| 8 | last4_zero | Last 4 digits are 0000 |
| 9 | invalid_nxx | NXX starts with 0 or 1 (NANP-invalid, often spoofed) |
| 10 | subscriber_all_same | Last 4 digits all same (9999, 1111) |
| 11 | alternating_pattern | Even/odd positions each uniform but different (5050505050) |
| 12 | sequential_desc_ratio | Descending sequential digit pairs / 9 |
| 13 | nxx_below_200 | NXX integer < 200 (often unassigned) |
| 14 | low_digit_entropy | Fewer than 4 distinct digits in full number |
| 15 | subscriber_sequential | Last 4 form complete ascending/descending run (1234, 9876) |

- Threshold: **0.7** — must match in both `SpamMLScorer.kt` and `spam_model_weights.json`
- Weights file: `data/spam_model_weights.json` (version 2), synced weekly via `SyncWorker`
- Training: `scripts/train_spam_model.py` — 50K positives, 50K negatives, 300 epochs, L2=0.01
- `parseAndApply()` size guard: `>= 15` — keep in sync with feature count

---

## Anti-Autodialer — SIT Tone (`SitTonePlayer`)
ITU-T E.180/Q.35 sequence: 985.2 Hz / 1428.5 Hz / 1776.7 Hz × 380ms each, 80ms gaps, played twice.
Via `AudioTrack` on `USAGE_VOICE_COMMUNICATION` / `STREAM_VOICE_CALL`. User-initiated button in overlay only.

---

## Workers

| Worker | Schedule | What it syncs |
|---|---|---|
| `SyncWorker` | Weekly (Monday 6am UTC) | Full spam DB + ML model weights |
| `HotListSyncWorker` | Every 30 min | hot_numbers.json + hot_ranges.json + spam_domains.json |
| `DigestWorker` | Daily | Summary notification — total blocked with source breakdown |

All three are scheduled from `CallShieldApp.onCreate()` and rescheduled on boot via `BootReceiver`.

---

## Settings (DataStore Preferences → `SpamRepository` → `MainViewModel` → `SettingsScreen`)
Block Calls, Block SMS, Block Unknown, Contact Whitelist, STIR/SHAKEN, Neighbor Spoof, Heuristics, SMS Content, Repeat Caller, Quiet Hours (start/end hour), Aggressive Mode, Auto-cleanup (retention days), **ML Scorer**, **RCS Filter**, AbstractAPI Key (text input)

---

## UI Structure
- 6 main tabs: Home, Recent, Log, Lookup, Blocklist, More
- 5 blocklist sub-tabs: Blocklist, Wildcards, Keywords, Whitelist, Database
- More hub: Statistics, Settings, Protection Test, What's New, Quick Links, About
- Caller ID overlay: live multi-source lookup, real-time score, caller name (OpenCNAM), SIT tone button
- DigestWorker daily notification uses `BigTextStyle` with per-source breakdown

---

## Python Scripts (`scripts/`)

| Script | Triggered by | Output |
|---|---|---|
| `import_all_sources.py` | Weekly CI | `data/spam_numbers.json` |
| `merge_community_reports.py` | Daily CI | Merges `data/reports/*.json` into DB |
| `generate_hot_list.py` | Every CI run (30 min + daily + weekly) | `data/hot_numbers.json`, `data/hot_ranges.json` |
| `extract_spam_domains.py` | Daily CI + weekly CI | `data/spam_domains.json` |
| `train_spam_model.py` | Weekly CI | `data/spam_model_weights.json` |

---

## Infrastructure
- Cloudflare Worker: `callshield-reports.snafumatthew.workers.dev` (community report ingest)
- GitHub Actions: `build.yml` (weekly DB + ML + APK), `merge-reports.yml` (daily merge + 30-min hot list)
- Keystore: `callshield-release.jks` (gitignored), password in `build.gradle.kts`

---

## Build
```
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
ANDROID_HOME="$HOME/AppData/Local/Android/Sdk"
./gradlew assembleRelease
```

---

## Gotchas
- Must disable/re-enable branch protection to push (enforce_admins: true)
- GitHubDataSource branch default is `"master"` not `"main"`
- Icons: SmsOff→SpeakerNotesOff, Nearby→NearMe, BlockFlipped→Block
- STIR/SHAKEN: `Connection.VERIFICATION_STATUS_FAILED` (not `Call.Details`)
- `settings.gradle.kts`: `dependencyResolutionManagement` (not `dependencyResolution`)
- Room `NumberCount` data class needs explicit import in DAO
- `SwipeToDismissBox` requires `@OptIn(ExperimentalMaterial3Api::class)`
- SMS receiver uses `Thread+runBlocking` (not `CoroutineScope`) to prevent race with `pendingResult`
- Notification IDs are stable hashes from phone number (no counter overflow)
- Adaptive icon XML overrides PNG mipmaps on API 26+ — deleted so logo PNG is used
- PullToRefresh API unstable in Material3 — removed
- PhoneBlock bulk blocklist requires auth; per-number lookup does not
- FCC Socrata API is slow for large offsets — use CSV bulk download instead
- ML threshold must be **0.7** in BOTH `SpamMLScorer.kt` (`applyDefaultWeights`) AND `spam_model_weights.json` — mismatch causes inconsistent blocking between first-launch and post-sync behavior
- ML feature count must stay in sync across four places: `extractFeatures()` return size, `applyDefaultWeights()` array size, `parseAndApply()` size guard (`>= 15`), and `train_spam_model.py` `FEATURE_NAMES` list
- `RcsNotificationListener.onDestroy()` uses `scope.coroutineContext[Job]?.cancel()` directly — do NOT add a private `CoroutineScope.cancel()` extension (shadows kotlinx stdlib, was removed)
- `SpamMLScorer` parses weights JSON with regex (not Moshi) — do not add Moshi back to it
- `SpamHeuristics.hotCampaignRanges` and `SmsContentAnalyzer.spamDomains` are `@Volatile` in-memory sets — they start empty on process start and are populated by the first `HotListSyncWorker` run
- `SmsContextChecker` normalizes to last-10-digits before comparing against SMS content provider `address` column — addresses in the provider may be stored as `+15551234567`, `15551234567`, or `5551234567`
- `train_spam_model.py` outputs `"threshold": 0.7` — earlier version output `0.5`, which caused a mismatch (fixed)
