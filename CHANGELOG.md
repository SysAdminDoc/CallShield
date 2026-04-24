# Changelog

All notable changes to CallShield will be documented in this file.

## [v1.6.3] - 2026-04-24

Hardening pass targeting defects the v1.6.1 audit missed. Nine
surgical correctness fixes across the screening pipeline, backup
system, SMS receiver, and several services.

### Fixed (high)

- **PushAlertChecker — title/body scope for anchored digit match**
  (`data/checker/PushAlertChecker.kt`). The v1.6.1 fix added a
  lookbehind/lookahead to the 7-digit match so "5551234" wouldn't
  match as a substring of a longer digit run, but the regex still
  ran against `title + "\n" + body`. Since `\n` counts as a
  non-digit boundary, a standalone 7-digit run in the **title**
  (order ID "Order #5551234", delivery PIN, tracking number) could
  allow an unrelated caller whose last-7 happened to match. The
  digit match is now scoped to `alert.body` only. Phrase matches
  (`your driver`, `out for delivery`, …) still run against the full
  title+body because phrases legitimately appear in titles.
- **BackupRestore — schedule fields now round-trip** (`data/BackupRestore.kt`).
  `BackupWildcard`/`BackupKeyword` dropped `scheduleDays`/`scheduleStartHour`/
  `scheduleEndHour` on export. A time-gated rule restored from a v2
  backup silently lost its schedule and fired 24/7. Backup version
  bumped to 3; readers still accept v1–v3.
- **SmsReceiver — removed misleading `abortBroadcast()`** (`service/SmsReceiver.kt`).
  CallShield isn't the default SMS app, so `abortBroadcast()` on
  `SMS_RECEIVED_ACTION` is either unordered or a no-op depending on
  OEM/API level — the message still landed in the inbox. The call
  (with its apologetic comment) suggested CallShield blocked SMS
  delivery when it only logs the block. Comment rewritten to be
  explicit: we log, we do not suppress.
- **CallerIdOverlayService — publish session id before `addView`**
  (`service/CallerIdOverlayService.kt`). Lookup jobs scheduled
  during view construction could compare against an older
  `activeSessionId` and mis-attribute results to the prior session.
  Belt-and-braces: set the id first, unwind on addView failure.

### Fixed (medium)

- **CallbackDetector — `NUMBER LIKE ?` SQL prefilter** (`data/CallbackDetector.kt`).
  The outgoing-24h and incoming-5min queries previously scanned the
  full CallLog window and post-filtered in Kotlin. On heavy users
  that's hundreds of rows decoded per screening call. SQLite now
  narrows on the trailing 7 digits before handing the cursor back;
  Kotlin still does an exact 10-digit match because CallLog formats
  vary (parentheses, dashes, country-code presence).
- **CallShieldTileService — Mutex-serialized toggles** (`service/CallShieldTileService.kt`).
  Two rapid QS-tile taps could both read the current state, both
  compute the opposite, and both write — leaving the toggle
  apparently stuck. A single `Mutex` now serializes the
  read-modify-write on each tap.
- **SpamMLScorer — sweep stale `.tmp` on `loadWeights`** (`data/SpamMLScorer.kt`).
  A process kill between `writeText` and `renameTo` left an orphan
  `spam_model_weights.json.tmp` that accumulated forever because
  `loadWeights` only reads the final file. Startup now deletes any
  stale tmp before reading.
- **WildcardRule — regex path uses `numberVariants`** (`data/model/WildcardRule.kt`).
  The glob path matched an input across its `+1`, bare-digit, and
  `1`-prefixed forms; the regex path only matched the raw input.
  A user-written regex anchored to E.164 (`^\+1832555\d{4}$`)
  silently missed SMS senders arriving without the `+1`. Regex now
  tries every variant for parity.

### Fixed (low)

- **SitTonePlayer — AtomicBoolean CAS for single-flight** (`service/SitTonePlayer.kt`).
  The `@Volatile var isPlaying` check-then-set wasn't atomic; two
  coroutines entering `play()` concurrently could both see `false`
  and both proceed. Replaced with `AtomicBoolean.compareAndSet`.

### Tests

- `CallbackDetectorTest` updated for the new query builder signature
  and the `NUMBER LIKE '%<last7>'` prefilter.
- `PushAlertCheckerTest` gains two regression cases covering the
  body-only digit scoping.
- `WildcardRuleTest` (new) covers the glob+regex parity and the
  ReDoS guard.
- `BackupRestoreTest` updated for v3 schema, including schedule-field
  round-trip cases.
- **577 unit tests pass.**

### Known follow-ups (not fixed this pass)

Real findings from the audit that deserve their own session:

- Contact cache has no `ContactsContract` observer — a contact added
  via the OS remains "unknown" for up to 60s. Needs lifecycle wiring
  and should land alongside a sibling observer for
  `BlockedNumberContract`.
- `MainActivity` uses `collectAsState` (not `collectAsStateWithLifecycle`)
  so the dashboard minute-ticker keeps running in background. Touches
  many screens; do in one sweep.
- Backup restore is additive; picking the wrong file mixes two users'
  state. Needs a product decision on Replace-vs-Merge prompt.
- Screening-service block logging is fire-and-forget on `appScope`;
  under memory pressure Android can reclaim the process before the
  Room insert runs. Needs `goAsync`-equivalent keep-alive.
- `MainViewModel` is 400+ lines with 28 StateFlows; wants to split
  into domain-specific ViewModels.
- Phone-number normalization duplicated across 13+ screens — wants
  a `PhoneUtils` extraction.

## [v1.6.2] - 2026-04-24

Maintenance release. No app-code changes since v1.6.1 — bundles the
refreshed spam database and a CI cadence fix.

### Changed
- **CI**: `merge-reports.yml` now runs weekly (Mon 08:00 UTC) instead of
  every 30 minutes. The hot-list refresh cadence was generating ~48
  commits/day to master; the weekly build workflow already covers the
  same ground. No effect on shipped app behavior.
- **Data**: refreshed community hot list and campaign-range aggregates.

## [v1.6.1] - 2026-04-22

Post-release audit fixes. Every item came out of a v1.6.0 code review;
no new features.

### Fixed (critical)
- **STIR/SHAKEN bypassed whitelist**: `CallShieldScreeningService` ran the STIR check inline *before* `isSpam()`, so a user's manual whitelist entry (emergency contact added via the app UI, not the device address book) was hard-rejected whenever the caller failed carrier verification. STIR is now a regular `IChecker` at priority 8,500 — below MANUAL_WHITELIST (10k) and CONTACT_WHITELIST (9k), above every block. Verification status threaded through `CheckContext.verificationStatus`.

### Fixed (high)
- **PushAlertChecker direct-number match anchoring**: a spam caller's last 7 digits appearing as a *substring* of any digit run in a recent notification (order ID, tracking number, dial-in PIN) used to return `allow("push_alert")`. Now anchored with `(?<!\d)…(?!\d)` boundaries so only standalone 7-digit runs qualify. TTL on both match paths unified to 10 minutes (was 30 min for number match).
- **PushAlertChecker trust-phrase list tightened**: dropped the bare `calendar` regex (matched any notification mentioning "calendar"). Tightened `(is |has )?outside` to require a subject word ("is outside", "arriving outside", "I'm outside", "we're outside") so weather notifications no longer fire it.
- **Verification/MFA phrases now package-gated**: "verify", "verification code", "OTP" etc. only fire for the four messaging-app packages that actually send SMS codes. Outlook MFA pushes no longer unblock unrelated callers. `appointment reminder` is gated to calendar apps.

### Fixed (medium)
- `util/Race.kt`: a competitor that threw was synthesizing `onTimeout` as its result and, for callers where `decisive(onTimeout) == true`, winning the race on the failure. Now failures are tracked as a separate state and only decrement the `remaining` tally.
- `PushAlertRegistry` opt-out updates are now atomic via `applyOptOuts(Set)`: prune-before-publish eliminates the window where a concurrent screening verdict could read stale alerts from a just-disabled package. Defensive `HashSet(disabled)` copy on every write guards future callers.
- `SystemBlockList.isBlocked`: a `SecurityException` (default-dialer role revoked mid-session) now clears the lookup cache in addition to marking availability off — prevents a stale `true` entry from the previous role session influencing subsequent checks.

### Fixed (low)
- `CheckerPipeline.run` now bails before each checker when `ctx.timeLeftMillis() <= 0` — cheap insurance in case a future checker blocks long enough to eat the 5-second deadline.

### Added
- `PushAlertCheckerTest` — 11 regression tests pinning the H1/H2/H3 fixes (anchored digit match, dropped bare phrases, package-gated MFA).

## [v1.6.0] - 2026-04-22

Peer-inspired track — features ported from the strongest OSS Android call/SMS blockers
(SpamBlocker, YetAnotherCallBlocker, Saracroche, Fossify Phone, BlackList).

### Added
- **Priority-sorted checker pipeline (A1)** — every detection layer is now an `IChecker` implementation with an explicit priority. 13 checkers (manual whitelist → ML scorer) run through `CheckerPipeline.run` with first-non-null wins. Replaces the 140-line `isSpam()` waterfall with a 25-line dispatcher. Each layer is testable in isolation and the "why blocked" trail is explicit. Inspired by SpamBlocker's `IChecker` architecture.
- **Budget-aware parallel race (A2)** — `util/Race.kt` races N suspend blocks against a budget, returns the first decisive result, cancels losers. Built on `Channel` + `select` + `AtomicInteger`. Foundation for reputation-API work under the 5-second CallScreeningService deadline.
- **Push-alert bridge (A3)** — notifications from 24 messaging/delivery/rideshare apps (Uber, DoorDash, Amazon, FedEx, Google Messages, Gmail, Outlook, etc.) can vouch for an unknown caller within 30 minutes. Direct number match OR trust-phrase match ("your driver", "verification code") allows the call through. Biggest false-positive fix in the OSS landscape.
- **A3 allowlist editor** — modal bottom sheet lists every trusted source package with per-package switches + "Restore defaults". `PackageManager`-resolved labels, installed-first sort. Opt-out semantics — future default additions propagate automatically. Gated behind the master toggle.
- **System block-list checker (A4)** — read-only bridge to `BlockedNumberContract.BlockedNumbers`. If the user has marked a number via stock Phone/Messages, CallShield respects it. Graceful degradation via `SecurityException` catch for non-default-dialer installs.
- **Length-locked `#` wildcard rules (A5)** — Saracroche-style range patterns like `+33162######`. Pure character-index matching, no regex JIT. Covers any NPA-NXX in one rule. New "Ranges" tab with pattern overlap detection, coverage count pill, and a safety rail rejecting patterns that cover >100M numbers. Country-prefix variant generator matches national-format and international-format numbers from the same rule.
- **Per-rule schedule gating (A7)** — every rule type (`WildcardRule`, `HashWildcardRule`, `SmsKeywordRule`) now carries an optional day-of-week + hour-window schedule. Rules can be restricted to e.g. "Mon–Fri 09:00–17:00". Shared `ScheduleSection` composable across all three rule-add dialogs; list items show an "Active …" pill when gating. `daysMask == 0` is the "no gating" sentinel so legacy rules behave identically.

### Fixed (opening audit pass, opus-4.7 context)
- SpamMLScorer double-scoring: `isSpam()` + `confidence()` each ran feature extraction + tree traversal. Replaced with `verdict()` — single pass returns `(score, confidence, isSpam)`. Material on the 5-second deadline.
- SpamMLScorer parse-failure leak: `parseAndApply` mutated state to defaults mid-parse. Now `parseModel` is pure (returns `ModelState?`) and the caller commits atomically. A corrupt sync can no longer expose default weights to live scorers.
- CoroutineScope leaks in `SpamActionReceiver` and `CallShieldWidget` — each allocated a fresh scope per invocation. Both now use `CallShieldApp.appScope`.
- CallShieldScreeningService hot path: 4+ individual DataStore `Flow.first()` reads before `isSpam()` took its own snapshot. Now single `readPrefsSnapshot()` threaded through the entire call chain.
- CallShieldScreeningService response-loss: Room logging ran before `respondToCall`, risking "Android auto-allows after 5s" if the service was unbound mid-write. Response now happens first; logging offloaded to `appScope`.
- Room `LIKE` wildcard injection in `searchNumbers` / `searchLog` — user-typed `%` or `_` was silently treated as SQL wildcard. Pre-escaped via `escapeLikeQuery()` with `ESCAPE '\'` on the queries.
- `SmsContentAnalyzer`: dead code (unused `body.lowercase()`) removed; `PHONE_IN_BODY` regex switched to `(?i)` flag for consistency.

### Performance
- Single-snapshot prefs sharing across `isSpam` + `isSpamSms` removes one DataStore round-trip on every SMS.
- Checker-level `isEnabled(ctx)` gating skips expensive checks (regex compile, ML traversal, heuristic analyze) before any work, not after.
- `HashWildcardMatcher.matches` is ~20 ns per call — beats regex by 100x on length-locked range patterns.
- `PushAlertRegistry` is lock-bounded but uses a 128-entry ring buffer; dedup of identical sequential alerts.

### Database
- Room `DB_VERSION = 9`. Three additive migrations layered on top of v1.5:
  - `MIGRATION_6_7`: new `hash_wildcard_rules` table.
  - `MIGRATION_7_8`: schedule columns on `hash_wildcard_rules`.
  - `MIGRATION_8_9`: schedule columns on `wildcard_rules` and `sms_keyword_rules`.
- All additive, all `DEFAULT 0` sentinels — zero risk for existing data.

## [v1.5.0] - 2026-04-15

### Fixed
- SpamMLScorer race condition: replaced 6 independent @Volatile fields with single atomic ModelState snapshot to prevent half-updated model reads during concurrent scoring
- WildcardRule glob-to-regex: all regex metacharacters (`.`, `(`, `)`, `[`, `]`, etc.) now properly escaped — previously only `+` was escaped, causing false matches on patterns like "212.555*"
- Frequency auto-escalation: unbounded time window caused legitimate callers with 3+ calls over months to be auto-blocked; now uses 7-day sliding window
- RcsNotificationListener: replaced verbose `scope.coroutineContext[Job]?.cancel()` with idiomatic `scope.cancel()`
- SyncWorker: non-retryable failures (HTTP 404) were silently reported as success to WorkManager; now correctly returns Result.failure()
- SpamActionReceiver: bare CoroutineScope without SupervisorJob could crash before pendingResult.finish() on uncaught exceptions
- MainViewModel scan guard: TOCTOU race allowed duplicate concurrent call log / SMS scans; flag now set before coroutine launch
- isWangiriCountryCode: Caribbean +1 NPAs (876 Jamaica, 284 BVI, 649 Turks & Caicos, etc.) were never detected as wangiri due to overly broad US/CA exemption

### Performance
- Consolidated 7 separate OkHttpClient instances into shared `HttpClient.shared` singleton with per-caller derived builders — eliminates redundant connection pools and thread pools
- Cached hot-path Room queries (prefixes, wildcards, keyword rules) behind @Volatile lazy fields with write-through invalidation — removes per-call disk I/O from the 5-second screening deadline
- DataStore settings in isSpam(): single `dataStore.data.first()` read replaces 8+ separate Flow .first() collector operations
- CampaignDetector.trimTrackedPrefixes: replaced O(n log n) sort with O(n) min-scan inside synchronized block

### Hardening
- WildcardRule: user-provided regex patterns capped at 200 chars to guard against ReDoS on the call screening path
- SmsContentAnalyzer: URL regex pattern length-capped at 2048 chars to prevent ReDoS on pathological SMS bodies

## [v1.4.0]

- Changed: Update hot list + merge community reports 2026-04-13T15:33
- Changed: Update hot list + merge community reports 2026-04-13T14:38
- Changed: Update hot list + merge community reports 2026-04-13T13:40
- Changed: Update hot list + merge community reports 2026-04-13T12:56
- Changed: Update hot list + merge community reports 2026-04-13T12:29
- Changed: Update hot list + merge community reports 2026-04-13T11:31
- Changed: Update hot list + merge community reports 2026-04-13T10:36
- Changed: Update hot list + merge community reports 2026-04-13T09:41
- Changed: Update hot list + merge community reports 2026-04-13T08:47
- Changed: Update hot list + merge community reports 2026-04-13T08:44
