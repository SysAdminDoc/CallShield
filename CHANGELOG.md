# Changelog

All notable changes to CallShield will be documented in this file.

## v1.7.12 — 2026-06-27

### Hardening

- Added a durable pending blocked-call log queue. `CallShieldScreeningService`
  writes an idempotent Room row before responding to Android, then a
  Hilt-backed WorkManager retry worker flushes it into `call_log` without
  duplicate final rows, notifications, or widget refreshes.
- Upgraded Room schema to v10 with `call_log.logKey` and
  `pending_blocked_call_logs`, plus migration and DAO coverage for duplicate
  suppression and retry timing.
- Added a shared ASCII-only digit utility and routed security-sensitive phone
  extraction paths away from Unicode digit matching.
- Added Cloudflare Worker rate-limit/dedup tests and Android 429 retry-delay
  feedback for community report submissions.

### Testing

- Verified `testDebugUnitTest`: 645 tests, 0 failures, 0 errors.

## v1.7.11 — 2026-05-18

### Fixed

- **Profile selection auto-reset** ([#2](https://github.com/SysAdminDoc/CallShield/issues/2)) — Selecting a blocking profile (Work / Personal / Sleep / Maximum / Off) appeared to "reset" on the Dashboard chip row after process death or ViewModel recreation. The profile's underlying flag changes (block calls, aggressive mode, time block, etc.) were correctly applied and persisted, but the active-profile *indicator* itself was held in an in-memory `MutableStateFlow` and lost on every VM init. Now persisted to DataStore under `KEY_ACTIVE_PROFILE`; the dashboard chip stays selected across restarts.

## [Unreleased]

Distribution prep after the v1.7.10 release.

### UX and visual polish

- Extended the premium Compose component system across the major user-facing
  flows, including dashboard scan actions, number details, recent-call recovery,
  blocklist utilities, diagnostics, onboarding, and settings.
- Replaced mixed raw action buttons and ad hoc icon wells with shared premium
  actions, compact actions, and icon tiles so destructive, recovery, loading,
  and secondary states read consistently across the app.
- Tightened trust-critical copy and semantics for reporting, source checks,
  permission recovery, import/export, and settings save actions.

### Fixed

- Routed lookup, manual block, whitelist, recent-call, and blocked-log
  phone-number inputs/actions through the shared ASCII-only digit helper, with
  regression coverage for Unicode digit spoofing.
- Added bounded response-body guards and typed fallback statuses for
  enrichment lookups so oversized or malformed third-party responses do not
  crash overlay, details, or line-type parsing.
- Added answered-caller trust with configurable count/window limits so repeated
  answered callers can bypass weaker heuristic/ML suspicion while explicit
  block rules still win first.
- Added a backup restore preview step with parsed counts, conflict warnings,
  and explicit Merge or Replace apply modes so restores validate before
  mutating local blocklist state.
- Enabled checked-in Room schema export with instrumented migration coverage
  from database versions 5 through 9, plus a CI guard that fails on
  uncommitted schema drift.
- Added a high-API instrumented smoke lane for target-SDK permission and
  protected-service declarations while keeping the full API 29 emulator suite.
- Migrated Compose ViewModel Flow collection to lifecycle-aware
  `collectAsStateWithLifecycle()` so UI-only collectors stop with their
  lifecycle instead of continuing while screens are stopped.
- Aligned community-report number normalization with the hardened
  ASCII-only screening normalizer across the Android app, Cloudflare Worker,
  and Python report/import scripts, with regression coverage for Unicode
  digit spoofing and overlong numbers.
- Preserved pending community-report evidence for hot-list and spam-domain
  generation by deriving those feeds before the destructive report merge, with
  a Python regression test wired into CI.
- Added privacy-preserving SMS spam community reports that submit only
  sanitized domain and URL-signal indicators, never raw message text, while
  feeding fixture domains into `spam_domains.json`.

### Architecture

- Added production-used domain use-case wrappers for call spam checks, SMS spam
  checks, database sync, blocklist management, and log/blocklist export, then
  routed the live screening/scanner/sync/ViewModel entrypoints through them
  while preserving `SpamRepository` as the backing implementation.
- Moved `SpamCheckResult` and `SyncResult` into `domain/model` so use cases,
  UI, services, and tests now consume the same domain result types instead of
  repository-local data classes.
- Added domain repository contracts for spam checks, database sync, and
  blocklist management, then routed the use cases through a
  `SpamRepositoryAdapter` bridge ahead of the larger repository split.
- Split `SpamRepository` into a compatibility facade over
  `SpamRepositoryImpl`, `SettingsRepository`, `SyncRepository`, and
  `BlocklistRepository`, preserving existing singleton callers while moving
  detection, settings, sync, and blocklist/log concerns behind data-layer
  collaborators.
- Added the Hilt 2.58 Gradle plugin/runtime/compiler on the KSP path and
  annotated `CallShieldApp` with `@HiltAndroidApp`, establishing the DI
  migration baseline while staying compatible with AGP 8.10.1.
- Added a Hilt `DatabaseModule` that provides `AppDatabase` and `SpamDao`
  singletons for the upcoming repository and consumer injection steps.
- Added a Hilt `RepositoryModule` that provides the existing
  `SpamRepository` facade and binds the spam-check, sync, and blocklist domain
  repository interfaces to `SpamRepositoryAdapter`.
- Added a Hilt `NetworkModule` that provides the existing pinned
  `HttpClient.shared` as the singleton `OkHttpClient`.
- Migrated `MainActivity` and `MainViewModel` onto Hilt, with
  `MainViewModel` now receiving the existing `SpamRepository` facade plus
  sync, blocklist, and export use cases through constructor injection.
- Migrated `CallShieldScreeningService` onto Hilt field injection for the
  `SpamRepository` facade and call-spam use case while preserving the existing
  one-snapshot, fail-open 5-second screening flow.
- Added AndroidX Hilt Work 1.3.0, installed `HiltWorkerFactory`, removed the
  default WorkManager initializer, and migrated sync, hot-list, and digest
  workers to `@HiltWorker` assisted injection without changing schedules.
- Added a Hilt `DetectionModule` plus `CheckerDependencies` so the live
  checker pipeline, screening service, app-startup hot-data/model priming, and
  sync workers consume detection helpers through an injectable seam while the
  remaining object-to-class conversion continues.
- Converted the remaining detection helper singletons into
  constructor-injectable classes backed by shared compatibility facades, keeping
  existing UI/test utility call sites source-compatible while Hilt consumers
  receive injectable instances.

### Testing

- Upgraded AndroidX Test/Espresso to the 1.7.0/3.7.0 line and refreshed locks
  so Compose instrumented settings coverage runs on the API 36 emulator.
- Added Kover 0.9.8 to the locked Gradle graph and wired CI to run
  `:app:koverVerifyDebug` plus `:app:koverXmlReportDebug`, gating the
  JVM-tested data/util core at a 35% minimum line-coverage threshold.
- Added ktlint 1.8.0 and detekt 1.23.8 as locked Gradle checks, with
  baselines for existing style/complexity debt and a CI `static-analysis` job
  that fails on new ktlint or detekt findings.
- Added an `AppDatabase` constructor seam to `SpamRepository` so Android
  integration tests can run the repository against an in-memory Room database
  without starting the larger DI refactor.
- Added instrumented call-pipeline integration coverage for manual whitelist,
  STIR/SHAKEN failed/trusted ordering, user blocklist, prefix, wildcard,
  hash-wildcard, and frequency escalation priority tiers.
- Added instrumented SMS-pipeline coverage for whitelisted-sender inspection,
  keyword-before-content ordering, and generic SMS content-analysis blocking.
- Added a `SpamDataSource` seam and mocked in-memory Room sync integration
  tests for `syncFromGitHub()`, including remote snapshot population and
  preservation of user-block flags during remote refresh.
- Added a `HotFeedDataSource` seam for hot-list feeds and instrumented
  in-memory Room coverage for hot number insertion, stronger existing-row
  preservation, hot-range refresh, spam-domain refresh, and invalid-entry
  tolerance.
- Added deterministic onboarding Compose UI coverage for the four-page setup
  walkthrough, permission request affordances, call-screener setup action, and
  unsupported-screener fallback state.
- Extracted dashboard hero, setup checklist, and stats-row composables for
  deterministic Compose coverage of protection hero copy, sync freshness,
  blocked-count stats, and the call-screener setup action.
- Added blocklist swipe-left removal with snackbar undo for manual block
  entries, plus Compose coverage for manual add normalization, regex validation,
  delete action, and swipe removal.
- Extracted quiet-hours settings into a deterministic composable, added an
  all-day warning when start and end match, and covered the toggle callback,
  validation message, and hour picker in Compose tests.

### Localization

- Audited all 100 tracked main Kotlin files for hardcoded user-facing strings
  and recorded the findings in `docs/hardcoded-string-audit.md`.
- Moved clear backup/restore, blocklist import/export, CSV export, spam share,
  sync result, Quick Settings tile, clipboard, search-result, recent-duration,
  and number-detail stragglers into `strings.xml` resources and plurals.

### F-Droid

- Added Fastlane listing metadata under `fastlane/metadata/android/en-US/`.
- Added a draft `fdroiddata` metadata file with the v1.7.10 build, upstream
  binary URL, expected signer fingerprint, and tag/update settings.
- Added an F-Droid submission runbook with the remaining GitLab MR,
  fdroidserver lint/build, and signature-copy verification steps.

## [v1.7.10] - 2026-05-14

Continuation roadmap pass focused on the Compose dependency refresh.

### Compose stack

- **Compose BOM 2026.05.00 upgrade** — moved the UI stack from the 2024.12
  BOM to the 2026.05 release train, resolving Compose UI/Foundation/Runtime to
  1.11.1 and Material 3 to 1.4.0.
- **Locked graph refresh** — regenerated dependency locks across debug,
  release, and unit-test classpaths so every app configuration resolves the
  same Compose, lifecycle, core, savedstate, and profileinstaller graph.
- **Compose resource lint cleanup** — replaced stale `LocalContext.getString`
  and `LocalContext.resources` reads in composables with `stringResource`,
  `pluralStringResource`, or `LocalResources`, keeping snackbar, toast,
  semantic, and validation copy configuration-aware.
- **Verification** — `lintDebug` is green on the refreshed Compose train; the
  full reproducible-build, unit-test, and lint pipeline was rerun after the
  migration.

## [v1.7.9] - 2026-05-14

Continuation roadmap pass focused on WorkManager dependency and scheduling
hardening.

### Background work

- **WorkManager 2.11.2 upgrade** — upgraded the background-work stack and
  refreshed dependency locks across debug, release, and unit-test classpaths.
- **Schedule contract coverage** — added JVM tests for `SyncWorker`,
  `HotListSyncWorker`, and `DigestWorker` WorkRequest construction, covering
  repeat intervals, connected-network constraints, initial delay, and backoff
  policy.
- **Schedule construction seam** — centralized each worker's WorkRequest
  creation behind testable companion helpers while preserving the existing
  unique periodic work names and `KEEP` enqueue policy.
- **Stable DoS-guard verification** — warmed the SMS analyzer before timing
  the large-body guard so the test measures analyzer behavior rather than JVM
  regex initialization.

## [v1.7.8] - 2026-05-14

Continuation roadmap pass focused on DataStore and backup privacy hardening.

### Settings and backup privacy

- **DataStore 1.2.1 upgrade** — upgraded the settings dependency and refreshed
  the locked dependency graph.
- **No deprecated encrypted-preferences path** — audited the settings and
  backup layer; CallShield does not use `EncryptedSharedPreferences`,
  `androidx.security`, or Tink-backed preferences today, so no encrypted
  preference migration target exists.
- **No-backup credential storage** — moved the optional AbstractAPI enrichment
  key into a private DataStore under `noBackupFilesDir`, with one-time
  migration from the legacy public settings key.
- **Scoped restore boundary** — public DataStore settings and the Room
  database remain restorable, while optional local credentials are kept out of
  Android Auto Backup scope.
- **Backup-rule documentation** — corrected backup rule comments and README
  copy so they no longer imply that every preference is excluded.

## [v1.7.7] - 2026-05-14

Continuation roadmap pass focused on reproducible-build groundwork.

### Build integrity

- **Gradle dependency locking** — enabled repository-wide dependency locking
  and checked in the generated lockfiles for release/debug/test resolution.
- **Build metadata guard** — added `verifyReproducibleBuildInputs` to fail if
  Gradle build scripts start embedding wall-clock build metadata into APK
  inputs.
- **AGP VCS metadata disabled** — release APKs no longer include
  `META-INF/version-control-info.textproto`, and
  `verifyReleaseApkReproducibleMetadata` fails if it returns.
- **Release hash sidecars** — CI release artifacts now include `.sha256`
  sidecars for artifact integrity, and local signed releases can generate the same sidecar via
  `scripts/write-release-sha256.ps1`.
- **APK content comparator** — added `scripts/compare-apk-contents.ps1` for
  rebuild checks that compare ZIP entries while making APK Signing Block
  differences explicit.
- **Verification runbook** — documented the fixed inputs, signed-vs-unsigned
  artifact distinction, offline rebuild command, and hash comparison workflow
  in `docs/reproducible-builds.md`.

## [v1.7.6] - 2026-05-14

Continuation roadmap pass focused on network dependency hardening.

### Network and dependency hardening

- **OkHttp 5 upgrade** — upgraded OkHttp from 4.12.0 to 5.3.2 and kept
  callers on the shared `HttpClient` derived-client pattern.
- **Central certificate pinning** — added SPKI pins for GitHub raw/API,
  Cloudflare community reports, URLhaus, AbstractAPI, and the free caller-ID
  enrichment hosts.
- **Kotlin, AGP, and Room alignment** — upgraded AGP to 8.10.1, Kotlin/KSP to
  2.2.21, and Room to 2.8.4 so the OkHttp 5 dependency stack builds cleanly
  with current metadata.
- **Pinning regression coverage** — added `HttpClientTest` to lock the pinned
  host inventory and pin-format requirements.

## [v1.7.5] - 2026-05-13

Continuation polish pass focused on Statistics localization and scan feedback.

### Stats and scan feedback polish

- **Localized chart labels** — the weekly Statistics chart now uses locale
  weekday abbreviations instead of hardcoded English day names.
- **Resource-backed detection labels** — Statistics detection-source names are
  now string resources, including the corrected "Prefix match" label.
- **Consistent scan errors** — call-log and SMS inbox scan permission/failure
  messages now use the same resource-backed copy system as the rest of the UI.
- **Cleaner legend formatting** — source legend count/percentage labels now
  use a formatted string resource for localization-ready output.

## [v1.7.4] - 2026-05-13

Continuation polish pass focused on the advanced settings credential flow.

### Settings trust polish

- **Safer optional API-key control** — the AbstractAPI key field is now masked
  by default, with explicit show/hide control rather than a permanently visible
  credential field.
- **Clear saved-state feedback** — the control now distinguishes "Not
  configured", "Saved locally", and "Unsaved changes" so users can tell whether
  a key is active or pending.
- **Lower-friction saving** — the save action is disabled until the local value
  changes, and switches to a clear-key action when a stored key is being
  removed.
- **Trust copy** — advanced settings now state that the optional key is stored
  only on the device and can be left blank to disable carrier enrichment.

## [v1.7.3] - 2026-05-13

Premium-polish pass focused on trust, visual discipline, state recovery,
and user-facing release clarity.

### UX and visual polish

- **Shared shape and typography rhythm** — standardized premium surfaces to
  modest rectangular 12 dp corners, removed negative/expanded letter tracking,
  and kept status backdrops within the existing no-pill design rule.
- **App chrome refinement** — tightened the top shell spacing, reduced header
  card weight, and removed the selected bottom-navigation pill indicator. The
  active tab is now communicated through icon/text color and weight.
- **State treatment consistency** — aligned buttons, filter chips, dialogs,
  progress bars, tabs, cards, and icon backdrops around the same restrained
  radius system.

### Trust and feedback

- **Blocked Log recovery states** — empty and filtered log states now explain
  what is happening, use a proper premium state card, and provide a "Show all
  activity" recovery action when filters hide existing records.
- **Trusted-source sheet feedback** — the push-alert source picker now shows
  installed-source coverage, total supported-source coverage, and skeleton
  loading while app labels resolve.
- **Lookup/report semantics** — spam report actions now use a flag icon rather
  than a favorite icon, and Number Detail database/risk labels use the shared
  rectangular status treatment instead of default Material chip backdrops.

## [v1.7.2] - 2026-05-13

Extreme hardening pass. Eight surgical fixes across UI design rules,
phone-number spoofing defence, regex-DoS guards, unbounded in-memory
maps, and crash-log durability. 28 new JVM unit tests; full suite at 613
passing.

### Security & correctness

- **ASCII-only phone-number normalization** — `normalizePhoneNumber()`
  now drops Arabic-Indic (٠-٩), fullwidth (０-９), and other non-ASCII
  digits that `Char.isDigit()` previously accepted. Visually-identical
  homoglyph caller IDs no longer bypass exact blocklist matches. Also
  strips zero-width / RTL marks (ZWSP, LRM, RLM, BOM) injected into
  spoofed numbers before the `+` check.
- **SMS body DoS guard (`SmsContentAnalyzer.MAX_ANALYSIS_LENGTH = 16 KB`)**
  — caps the input fed into the regex sweep. Multi-MB SMS bodies on the
  inbox-scan path can no longer pin the 5 s screening deadline.
- **Multipart SMS reassembly cap (`SmsReceiver.MAX_REASSEMBLED_BODY = 16 KB`)**
  — a malformed delivery claiming hundreds of segments can no longer
  drive `joinToString` into unbounded memory.
- **WildcardRule ReDoS hardening** — rejects catastrophic-backtracking
  shapes (`(a+)+`, `(a*)+`, `(a|aa)+`) at validation time, before the
  regex even compiles. Phone-shaped patterns (`^\+?1?\d{10}$`, area-code
  alternations) still pass.
- **CrashReporter atomic write** — crash logs now write to `*.txt.tmp`
  and atomically rename. Power loss or a second crash mid-write can no
  longer leave a half-written report that looks legitimate.

### Reliability

- **OneShotNoticeGate bounded map** — added a 1 024-entry LRU cap on
  top of the existing 6 h TTL prune. Long-lived processes that see many
  unique callers can no longer grow the notice-gate map without bound.
- **NotificationHelper PendingIntent ID separation** — `notifyAfterCall`
  now derives its request codes from `stableId(number, salt)` with
  distinct salts per intent rather than `number.hashCode()` and
  `hashCode() + 1`, removing collisions with block-notification intents
  for adjacent hashes.
- **`updateSummary` cancel guarded against SecurityException** — the
  no-blocks-yet `NotificationManager.cancel(SUMMARY_ID)` path now
  swallows the API 33+ revoke-between-check race the same way
  `safeNotify()` does.

### Design system

- **No pill / oval backdrops** — removed every `RoundedCornerShape(999.dp)`
  in the app:
  - `StatusPill` (Theme.kt) now uses 6 dp corner radius and differentiates
    by colour/border/font-weight, not shape.
  - Onboarding and Protection-Test progress bars switched from
    `RoundedCornerShape(999.dp)` to 4 dp.
  - Blocked-log count badge switched from `CircleShape` to
    `RoundedCornerShape(10.dp)` (text-bearing badge, no longer fully
    rounded).

### Tests

- 28 new JVM unit tests across `NormalizePhoneNumberTest`,
  `SmsContentAnalyzerTest`, `OneShotNoticeGateTest`, `WildcardRuleTest`.
- Full suite: 613 tests passing in ~10 s.

## [v1.7.1] - 2026-04-29

### Improved

- **Caller-ID overlay race & feedback** — `CallerIdOverlayService` no longer blocks on all three external lookups; first spam-hit-wins via the `Race.kt` helper, so user-visible callerID appears sooner.
- **External lookup robustness** — tightened `ExternalLookup.kt` against transient races and stale results when one provider returns much later than the others.
- **Push-alert allow feedback** — `PushAlertRegistry` + `OneShotNoticeGate` now surface "Allowed by you" notices the next time a previously-allowed number rings, so the user can revoke without digging into logs.

## [v1.7.0] - 2026-04-24

Round-2/3 borrow-and-harden pass. Competitor-OSS research
(aj3423/SpamBlocker, adamff-dev/spam-call-blocker-app, rspamd patterns)
distilled into two user-facing detection-pipeline improvements plus a
behavior-preserving refactor of the block-response decision table.
Fourteen new JVM unit tests cover the priority ladder and the
silence-vs-reject branches.

### Added

- **STIR/SHAKEN Trusted-Caller Allow** — new detection layer that short-
  circuits the weaker statistical blockers (heuristic, ML, campaign-burst,
  frequency-escalation) when the carrier signs a `PASSED` attestation on
  the calling number. Paired with the existing STIR_SHAKEN block slot
  (which fires on `FAILED`) to form a clean decision table: PASSED → allow,
  FAILED → block, NOT_VERIFIED / null → no opinion.
  - New checker: `data/checker/Checkers.kt::StirShakenTrustChecker` at
    priority slot `STIR_SHAKEN_TRUSTED = 5_300`. Sits **below** every
    explicit user rule (manual whitelist, contact whitelist, user
    blocklist, prefix, wildcard, hash-wildcard, system-block-list) so an
    intentionally blocked number stays blocked even if the carrier
    verifies it. Sits **above** heuristic / ML / campaign-burst /
    frequency so it does what it's here to do.
  - New setting: `KEY_STIR_TRUSTED_ALLOW`, **defaulted on**. Toggle
    exposed in Settings → Detection Engines → "STIR Trusted-Caller Allow".
  - Source: Round-2 OSS research — aj3423/SpamBlocker + adamff-dev
    attestation-level filtering. We intentionally do NOT treat attestation
    `C` (verified-fail) as a sole block signal because US wholesale
    carriers routinely stamp C on legitimate traffic.
- **Auto-Mute Low-Confidence Blocks** — new opt-in setting that silences
  blocks with `confidence < 60` to voicemail instead of hard-rejecting
  them. Users can then review uncertain calls after the fact without an
  audible interruption. High-confidence hits (database match, user
  blocklist, STIR FAILED, heuristic ≥ 60) are always hard-rejected even
  with auto-mute on.
  - New helper: `CallShieldScreeningService.shouldSilence(silentVoicemail,
    autoMuteLowConf, confidence)` — pure decision function, testable
    without a CallScreeningService / Android runtime.
  - New constant: `AUTO_MUTE_CONFIDENCE_THRESHOLD = 60`.
  - New setting: `KEY_AUTOMUTE_LOW_CONFIDENCE`, defaulted off. Toggle
    exposed in Settings → Detection Engines → "Auto-Mute Low-Confidence
    Blocks".
  - `KEY_SILENT_VOICEMAIL` still wins when on — silence-every-block
    beats silence-only-uncertain.
- **14 new JVM unit tests** — `StirShakenTrustCheckerTest` (8 cases:
  enablement gates, pure decision paths, full priority-ladder regression
  sweep) and `CallShieldScreeningServiceAutoMuteTest` (6 cases: the three
  decision table branches + confidence-threshold boundary).

### Changed

- `CallShieldScreeningService.respondBlock()` now delegates response-
  shape decisions to a new private `buildBlockResponse(prefs, confidence)`
  helper. All three response shapes (silent-voicemail / auto-mute /
  hard-reject) share one reviewable decision table. Behavior-preserving
  for v1.6.3 users — same `CallResponse.Builder()` flag sequence is still
  emitted per branch.
- `CheckerPriority` ladder extended with `STIR_SHAKEN_TRUSTED = 5_300`.
  Existing slot numbers are unchanged.
- ROADMAP `Current State` header rolled forward from v1.2.8 → v1.7.0 and
  now documents the round-1 architecture refactor (IChecker pipeline,
  Race.kt, PushAlertChecker) that shipped in the v1.6.x series.

### Fixed

- Internal audit caught during the v1.7.0 development cycle: the
  first-cut priority slot for `STIR_SHAKEN_TRUSTED` was placed above
  `USER_BLOCKLIST`, which would have let a carrier-signed PASSED call
  override an intentional user block. Corrected to slot `5_300` before
  ship; added a regression test suite in `StirShakenTrustCheckerTest`
  that asserts the ladder invariant against every explicit user rule
  so the bug cannot return silently.

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
