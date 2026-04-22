# Changelog

All notable changes to CallShield will be documented in this file.

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
