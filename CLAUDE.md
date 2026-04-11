# CallShield — Project Notes

## Overview
Open-source Android spam call/text blocker. 57 Kotlin files, ~9,200 lines, 5 Python scripts. 32,933 spam numbers from FCC/FTC/community. 15-layer detection + ML scorer + RCS filter + 30-min hot list sync. Real-time multi-source caller ID overlay with SIT tone anti-autodialer. URLhaus phishing detection. Anonymous community contribution via Cloudflare Worker. No API keys required.

**Released:** v1.2.9 (versionCode 12, backup format v2)

---

## Tech Stack
- Kotlin 2.1, Jetpack Compose, Material 3, Premium AMOLED black + Catppuccin Mocha (+ CatTeal, CatLavender)
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
4. Repeated urgent caller (2x in 5 min = allow)
5. User blocklist (Room DB, `isUserBlocked = true`)
6. Database match (GitHub-synced spam_numbers.json + hot_list entries)
7. Prefix rules (wangiri/premium country codes)
8. Wildcard/regex rules
9. Quiet hours (time-based block, end hour is EXCLUSIVE — "22 to 7" blocks 22:00–6:59)
10. Frequency auto-block (3+ calls from same number)
11. Heuristic engine (`SpamHeuristics.analyze`) — VoIP NPA-NXX (~60 ranges), wangiri (~50), neighbor spoof, rapid-fire, **hot campaign range** (+35, "hot_campaign_range")
11.5. Campaign burst detection (`CampaignDetector`) — in-memory NPA-NXX prefix tracking, 1-hour window, 5-call threshold (+75, "campaign_burst")
12. Caller ID overlay (suspicious 30-59 -> `CallerIdOverlayService`, live lookup)
13. *(SMS only)* Keyword rules
14. *(SMS only)* SMS content analysis (`SmsContentAnalyzer`) — URL shorteners, suspicious TLDs, **spam domain blocklist** (+50, "spam_domain"), 30+ regex patterns
15. On-device ML scorer (`SpamMLScorer`) — GBT v3 (20 features) with logistic regression v2 fallback (15 features), threshold 0.7, gated by `mlScorerEnabled`

### `SpamRepository.isSpamSms()` — additional SMS layers
Calls `isSpam()` first, then:
- **SMS context trust** (`SmsContextChecker`) — if user has sent to this number OR received from it on 2+ distinct days -> allow through (runs before keyword/content checks)
- Keyword rules (custom user rules)
- SMS content analysis

### RCS Filter (`RcsNotificationListener`)
Runs independently as `NotificationListenerService`. Gated by `blockSmsEnabled` AND `rcsFilterEnabled`.
- Monitors: Google Messages, Samsung Messages, AOSP MMS, Microsoft SMS Organizer
- Cannot block RCS delivery — cancels notification only
- Sender with <7 digits = contact name -> URL check only; >=7 digits -> full `isSpamSms()`
- Requires Notification Access (Settings deeplink button in app)

---

## Data Sources

### GitHub Database (weekly CI, `build.yml`)
- FCC Unwanted Calls: Socrata API, up to 500,000 records, min 2 reports filter
- FTC Do Not Call: api.ftc.gov (DEMO_KEY, ~50/day)
- ToastedSpam: community curated list
- Community text lists: additional curated blocklists
- Community reports: Cloudflare Worker -> `data/reports/` -> `merge_community_reports.py`
- Spam domains: `extract_spam_domains.py` -> `data/spam_domains.json` (top 500 domains, min 3 reports)

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
Pure Kotlin ML scorer — no TFLite, no extra deps. Version 3: Gradient-Boosted Trees (50 trees, depth 4, 20 features, pure Kotlin inference). Version 2 fallback: Logistic regression (15 features).

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
| 16 | time_of_day_sin | Sine encoding of call hour (captures cyclic time patterns) |
| 17 | time_of_day_cos | Cosine encoding of call hour (captures cyclic time patterns) |
| 18 | geographic_distance | Distance between caller NPA and callee NPA centroids |
| 19 | short_number | Number has fewer than 10 digits (short codes, international) |
| 20 | plus_one_prefix | Number starts with +1 (NANP prefix present) |

- Threshold: **0.7** — must match in both `SpamMLScorer.kt` and `spam_model_weights.json`
- Weights file: `data/spam_model_weights.json` (version 3), synced weekly via `SyncWorker`
- Training: `scripts/train_spam_model.py` — 50K positives, 50K negatives, 300 epochs, L2=0.01
- `parseAndApply()` size guard: `>= 15` for v2 compat, `>= 20` for v3 — keep in sync with feature count

---

## Anti-Autodialer — SIT Tone (`SitTonePlayer`)
ITU-T E.180/Q.35 sequence: 985.2 Hz / 1428.5 Hz / 1776.7 Hz x 380ms each, 80ms gaps, played twice.
Via `AudioTrack` on `USAGE_VOICE_COMMUNICATION` / `STREAM_VOICE_CALL`. User-initiated button in overlay only.

---

## Workers

| Worker | Schedule | What it syncs |
|---|---|---|
| `SyncWorker` | Every 6 hours | Full spam DB + ML model weights |
| `HotListSyncWorker` | Every 30 min | hot_numbers.json + hot_ranges.json + spam_domains.json |
| `DigestWorker` | Daily | Summary notification — total blocked with source breakdown |

All three are scheduled from `CallShieldApp.onCreate()` and rescheduled on boot via `BootReceiver`.

---

## Settings (DataStore Preferences -> `SpamRepository` -> `MainViewModel` -> `SettingsScreen`)
Block Calls, Block SMS, Block Unknown, Contact Whitelist, STIR/SHAKEN, Neighbor Spoof, Heuristics, SMS Content, Repeat Caller, Quiet Hours (start/end hour), Aggressive Mode, Auto-cleanup (retention days), **ML Scorer**, **RCS Filter**, AbstractAPI Key (text input)

---

## UI Structure
- 6 main tabs: Home, Recent, Log, Lookup, Blocklist, More
- 5 blocklist sub-tabs: Blocklist, Wildcards, Keywords, Whitelist, Database
- More hub: Statistics, Settings, Protection Test, What's New, Quick Links, About
- Statistics: weekly bar chart (Canvas), source breakdown donut chart, monthly trend, hourly heatmap, area code breakdown
- Caller ID overlay: live multi-source lookup, real-time score, caller name (OpenCNAM), SIT tone button, rounded bottom corners with accent line
- After-call feedback notification with Block/Whitelist actions on allowed unknown calls
- DigestWorker daily notification uses `BigTextStyle` with per-source breakdown
- Onboarding: 4 pages with permission request + call screener role setup, pill-shaped animated page indicators
- Protection test: 11 checks with staggered entrance animations and protection score percentage
- Dashboard: hero entrance animation, weekly trend indicator, last blocked preview, active profile indicator
- Changelog: vertical timeline with connected rail and "LATEST" badge

---

## Premium Design System (`Theme.kt`)
- `PremiumCard` — card with subtle 1dp border (accent-tinted or white@6%), used across all screens
- `SectionHeader` — uppercase label with 3dp accent bar
- `GradientDivider` — horizontal divider that fades in from transparent
- `ShimmerBox` / `SkeletonListItem` — animated loading placeholders
- `accentGlow` modifier — radial color glow behind elements
- `hapticTick` / `hapticConfirm` — unified haptic feedback (15ms light / 40ms firm)
- Surface hierarchy: Black(#000) → Surface(#080808) → SurfaceVariant(#111113) → SurfaceBright(#1A1A1E) → SurfaceElevated(#1E1E22)
- Typography: negative letterSpacing on headlines, positive on labels
- All screens import via `import ui.theme.*`

---

## Backup/Restore (`BackupRestore`)
- Format version 2 — includes blocklist, whitelist, wildcard rules, **SMS keyword rules**
- Backup shared via `FileProvider` + share intent
- Restore from URI with per-item error tolerance
- v1 backups restore fine (just without keyword rules)

---

## Python Scripts (`scripts/`)

| Script | Triggered by | Output |
|---|---|---|
| `import_all_sources.py` | Weekly CI | `data/spam_numbers.json` |
| `merge_community_reports.py` | Daily CI | Merges `data/reports/*.json` into DB (files deleted only after persist) |
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

## Version History (v1.2.x)
- **v1.2.0** — ML scorer, RCS filter, hot list sync, SIT tone, URL safety, SMS context trust
- **v1.2.1** — Audit round 1: 12 critical bug fixes (regex crash, handler NPE, ANR, JSON injection, CSV, data loss)
- **v1.2.2** — Audit round 2: 7 fixes (boot receiver, worker resilience, deprecated icons, Python scripts)
- **v1.2.3** — UX + performance: onboarding refresh, widget perf, RecentCalls batch queries, permission request
- **v1.2.4** — README rewrite, protection test expansion (ML/RCS/hot list), theme colors, detection icons
- **v1.2.5** — Backup keyword rules, proguard hardening (GitHubDataSource JSON models), final icon migration
- **v1.2.6** — Premium UI overhaul (PremiumCard, accentGlow, GradientDivider, shimmer skeletons, haptic feedback), 12 bug fixes (race conditions, JSON injection, thread leaks, date grouping), undo on swipe-delete, confirmation dialogs, directional tab transitions, changelog timeline, widget protection status
- **v1.2.7** — Build fix, deprecated icon migration (AutoMirrored ViewList/TrendingUp/Down/Flat), zero compilation warnings, atomic database transactions, hot list parser fix, quiet hours end-time exclusive fix
- **v1.2.8** — GBT ML model (v3, 20 features, pure Kotlin tree ensemble), campaign burst detection (NPA-NXX, 1hr/5-call), after-call feedback notifications, 378 strings extracted to strings.xml, 150 unit tests + CI pipeline (test.yml), full accessibility pass (100 content descriptions, semantic grouping, 48dp targets), weekly bar chart + source donut + monthly trend in Stats, signing credentials to local.properties, network_security_config.xml, restricted file_paths/backup_rules, proguard rules for GBT/Campaign, call log scanner Dispatchers.IO fix, sync freshness recompute, onboarding permission status + notification/overlay requests, ANSWER_PHONE_CALLS permission
- **v1.2.9** — Audit round 3: CampaignDetector.recordCall moved AFTER whitelist/dialed/urgent short-circuits (contact false-positive fix), BackupRestore.restoreFromUri wrapped in .use (FD leak fix), NotificationHelper.safeNotify helper honoring POST_NOTIFICATIONS at runtime for all 5 notify sites + try/catch on revoke-race, DigestWorker permission guard, RecentCallsScreen stable itemsIndexed key + per-row state keyed by call identity, BlockedLogScreen grouped view stable key, SearchResultsView stable items key

---

## Gotchas
- Must disable/re-enable branch protection to push (enforce_admins: true)
- GitHubDataSource branch default is `"master"` not `"main"`
- All Material icons must use `AutoMirrored.Filled.*` variants (ArrowBack, ArrowForward, PhoneCallback, TextSnippet, PlaylistAdd, OpenInNew, CallReceived, CallMade, PhoneMissed, ViewList, TrendingUp, TrendingDown, TrendingFlat) — `Icons.Default.*` versions are deprecated
- STIR/SHAKEN: `Connection.VERIFICATION_STATUS_FAILED` (not `Call.Details`)
- `settings.gradle.kts`: `dependencyResolutionManagement` (not `dependencyResolution`)
- Room `NumberCount` data class needs explicit import in DAO
- `SwipeToDismissBox` requires `@OptIn(ExperimentalMaterial3Api::class)`
- SMS receiver uses `Thread+runBlocking` (not `CoroutineScope`) to prevent race with `pendingResult`
- Notification IDs are stable hashes from phone number (golden ratio hash, overflow is intentional)
- Adaptive icon XML overrides PNG mipmaps on API 26+ — deleted so logo PNG is used
- PullToRefresh API unstable in Material3 — removed
- PhoneBlock bulk blocklist requires auth; per-number lookup does not
- FCC Socrata API is slow for large offsets — use CSV bulk download instead
- ML threshold must be **0.7** in BOTH `SpamMLScorer.kt` (`applyDefaultWeights`) AND `spam_model_weights.json` — mismatch causes inconsistent blocking between first-launch and post-sync behavior
- ML feature count must stay in sync across four places: `extractFeatures()` return size, `applyDefaultWeights()` array size, `parseAndApply()` size guard (`>= 15`), and `train_spam_model.py` `FEATURE_NAMES` list
- `RcsNotificationListener.onDestroy()` uses `scope.coroutineContext[Job]?.cancel()` directly — do NOT add a private `CoroutineScope.cancel()` extension (shadows kotlinx stdlib, was removed)
- `SpamMLScorer` parses weights JSON with regex (not Moshi) — do not add Moshi back to it. `bias` and `threshold` are `@Volatile`.
- `SpamHeuristics.hotCampaignRanges` and `SmsContentAnalyzer.spamDomains` are `@Volatile` in-memory sets — they start empty on process start and are populated by the first `HotListSyncWorker` run
- `SmsContextChecker` normalizes to last-10-digits before comparing against SMS content provider `address` column. Calendar.MONTH is 0-indexed — must add +1 for date grouping.
- `train_spam_model.py` outputs `"threshold": 0.7` — earlier version output `0.5`, which caused a mismatch (fixed)
- `CallShieldTileService` uses coroutine-based async — do NOT use `runBlocking` (causes Quick Settings ANR). Re-checks `qsTile` in Main context to avoid stale reference.
- `CallerIdOverlayService.isOverlayActive` volatile flag guards all `handler.post` lambdas — must be set to false in `dismiss()` before `removeOverlay()`. Overlay uses `GradientDrawable` with rounded bottom corners + top accent line.
- `NotificationHelper` uses `synchronized(lock)` for rate-limiting fields — both `notifyBlocked()` and `updateSummary()` access fields through the lock. Do NOT revert to `@Volatile`.
- `LogExporter` uses RFC 4180 CSV escaping — quotes fields, escapes internal quotes, strips newlines
- `merge_community_reports.py` deletes report files only AFTER DB is persisted (not before)
- `HotListSyncWorker` uses `mapNotNull` with per-entry try-catch — one bad entry doesn't break the entire sync
- `DigestWorker.doWork()` is wrapped in try-catch with `Log.e` — DB errors don't crash the worker
- Proguard: `SpamDatabase`, `SpamNumberJson`, `SpamPrefixJson`, `HotNumber`, `BackupKeyword` all need keep rules (Moshi reflection)
- `BlocklistScreen` wildcard dialog validates regex with try-catch and shows inline error message
- `NumberDetailScreen` uses `rememberCoroutineScope` for web lookups (not `MainScope()` — was a coroutine leak). Web lookup button disabled during loading.
- `UrlSafetyChecker` and `CommunityContributor` escape quotes/backslashes/newlines/tabs in JSON string interpolation
- `SitTonePlayer.playSequence()` and `playTone()` are `suspend` functions using `delay()` — do NOT revert to `Thread.sleep`
- `SpamDao.insertBlockedCall` uses `OnConflictStrategy.REPLACE` for undo-on-delete support
- `SpamDao.replaceBySource()` and `replaceGithubData()` use `@Transaction` for atomic delete+insert — prevents detection gaps during sync
- `BlockedLogScreen` clear log requires confirmation dialog — do NOT call `clearLog()` without showing `showClearDialog`
- `ChangelogScreen` uses a vertical timeline layout with `isLatest` and `isLast` flags — first entry must be `isLatest=true`, last must be `isLast=true`
- `CallShieldWidget` reads protection status via `repo.blockCallsEnabled.first()` — needs the SpamRepository import
- `syncFromGitHub(force)` — manual sync uses `force=true` (always re-downloads), background SyncWorker uses `force=false` (SHA-checked). "Already up to date" does NOT update the sync timestamp (only actual data fetches do).
- `CallShieldScreeningService.onScreenCall()` is wrapped in try-catch with guaranteed `respondAllow()` fallback — service must ALWAYS respond to avoid hanging calls
- `CallLogScanner.ScanResult` and `SmsInboxScanner.ScanResult` have `error: String?` field — permission denials and exceptions surface to the UI instead of showing empty results
- UI state that must survive rotation uses `rememberSaveable` (selectedTab, showSearch, filterMode, grouped, tabIndex, currentView)
- `MainViewModel.activeProfile` tracks which blocking profile is active — resets to null on error or individual setting change
- `GitHubDataSource.fetchHotList()` parses JSON by splitting on `{` and matching fields with regex — do NOT use the old `split(","number")` approach which drops the first entry
- Quiet hours end time is EXCLUSIVE: "22 to 7" blocks 22:00–6:59, calls resume at 7:00. Uses `now < end` not `now <= end`.
- `SmsReceiver` URL check coroutine has try-catch — prevents silent coroutine failure on URLhaus errors
- `CallLogScanner.scan()` must use `withContext(Dispatchers.IO)` — cursor queries on Main thread cause ANR/silent failure. `SmsInboxScanner` already did this correctly.
- Dashboard `isCallScreener` uses `DisposableEffect` lifecycle observer to re-check on resume — do NOT use plain `remember` (stale after granting role from system dialog)
- Dashboard sync freshness text uses `remember(lastSync, syncState)` — adding `syncState` as key forces recomputation when sync completes
- Onboarding permission launcher callback tracks grant status via `permsGranted` state — do NOT leave callback empty
- Onboarding uses `.navigationBarsPadding()` for gesture nav compatibility — `enableEdgeToEdge()` draws behind nav bar
- `ANSWER_PHONE_CALLS` permission required by some OEMs (Samsung, Xiaomi) for `CallScreeningService` to fully function
- `MainViewModel.scanningCalls`/`scanningSms` prevent double-tap during scan — buttons show `CircularProgressIndicator` while active
- `CampaignDetector` is in-memory only — data resets on process death. This is intentional (campaigns are short-lived).
- `SpamMLScorer` v3 GBT parser uses `findMatchingBracket()` for nested JSON arrays — do NOT simplify to regex-only parsing
- `extractFeatures()` returns 20 features in v3 (15 original + 5 behavioral). Size guard in `parseAndApply()` accepts >= 15 for backward compat.
- After-call feedback notification fires 10 seconds after an allowed unknown call. Uses `SpamActionReceiver` with `FEEDBACK_SPAM`/`FEEDBACK_NOT_SPAM` actions.
- `network_security_config.xml` disables cleartext traffic globally — do NOT add `cleartextTrafficPermitted="true"` for any domain
- `CampaignDetector.recordCall(normalized)` MUST run AFTER the manual/contact whitelist, `CallbackDetector.wasRecentlyDialed`, and `CallbackDetector.isRepeatedUrgentCall` short-circuits — running before them makes legitimate contact/family calls feed the spam-campaign detector and causes false-positive `campaign_burst` blocks for unknown callers in the same NPA-NXX.
- All `NotificationManager.notify(...)` posts must go through `NotificationHelper.safeNotify(...)` (or guard with `CallShieldPermissions.hasNotificationPermission(context)`) and be wrapped in `try/catch (SecurityException)` — API 33+ `POST_NOTIFICATIONS` can be revoked between check and post.
- `BackupRestore.restoreFromUri` reads the backup via `openInputStream(uri)?.use { it.bufferedReader().readText() }` — Kotlin's `Reader.readText()` does NOT close its underlying stream, so the outer `.use { }` is load-bearing (FD leak fix).
- `RecentCallsScreen` `itemsIndexed` uses `key = { _, call -> "${call.number}|${call.date}|${call.type}" }` and its per-row `visible` / `LaunchedEffect` are keyed by `(call.number, call.date)`. Do NOT revert to `remember { ... }` / `LaunchedEffect(Unit)` — filter-mode changes will scramble per-row animation state.
- `BlockedLogScreen` has TWO branches — grouped view (`itemsIndexed(groupedList, key = { _, item -> item.first.number })`) and non-grouped swipe list (`itemsIndexed(filtered, key = { _, call -> call.id })`). Both must have keys; the grouped branch was previously unkeyed.
- `MainActivity.SearchResultsView` uses `items(results, key = { it.number })` — needs `import androidx.compose.foundation.lazy.items`. Do NOT revert to `items(results.size)`.
