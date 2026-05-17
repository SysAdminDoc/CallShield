# Codex Change Log

Last updated: 2026-05-17

This is an internal handoff note for follow-on agents. It is not the user-facing product changelog.

## Repository

- `C:\Users\--\repos\CallShield`

## What This Worktree Now Contains

This repository already had a broad UX/premium-polish pass in progress across the main Android surfaces. This session continued with a deeper hardening/audit pass focused on correctness, lifecycle safety, sync reliability, parser robustness, and regression coverage.

## Major Work Completed

### Current session: integration-test foundation, CI gates, and use-case wrappers

Files:

- `.github/workflows/test.yml`
- `.editorconfig`
- `build.gradle.kts`
- `gradle/libs.versions.toml`
- `app/build.gradle.kts`
- `app/gradle.lockfile`
- `app/src/main/AndroidManifest.xml`
- `app/config/ktlint/baseline.xml`
- `app/detekt-baseline.xml`
- `app/src/main/java/com/sysadmindoc/callshield/CallShieldApp.kt`
- `app/src/main/java/com/sysadmindoc/callshield/di/DatabaseModule.kt`
- `app/src/main/java/com/sysadmindoc/callshield/di/RepositoryModule.kt`
- `app/src/main/java/com/sysadmindoc/callshield/di/NetworkModule.kt`
- `app/src/main/java/com/sysadmindoc/callshield/di/DetectionModule.kt`
- `app/src/main/java/com/sysadmindoc/callshield/data/SpamRepository.kt`
- `app/src/main/java/com/sysadmindoc/callshield/data/checker/CheckerDependencies.kt`
- `app/src/main/java/com/sysadmindoc/callshield/data/remote/SpamDataSource.kt`
- `app/src/main/java/com/sysadmindoc/callshield/data/remote/HotFeedDataSource.kt`
- `app/src/main/java/com/sysadmindoc/callshield/data/remote/GitHubDataSource.kt`
- `app/src/main/java/com/sysadmindoc/callshield/domain/usecase/CheckSpamUseCase.kt`
- `app/src/main/java/com/sysadmindoc/callshield/domain/usecase/CheckSpamSmsUseCase.kt`
- `app/src/main/java/com/sysadmindoc/callshield/domain/usecase/SyncDatabaseUseCase.kt`
- `app/src/main/java/com/sysadmindoc/callshield/domain/usecase/ManageBlocklistUseCase.kt`
- `app/src/main/java/com/sysadmindoc/callshield/domain/usecase/ExportLogsUseCase.kt`
- `app/src/main/java/com/sysadmindoc/callshield/domain/model/SpamCheckResult.kt`
- `app/src/main/java/com/sysadmindoc/callshield/domain/model/SyncResult.kt`
- `app/src/main/java/com/sysadmindoc/callshield/domain/repository/SpamCheckRepository.kt`
- `app/src/main/java/com/sysadmindoc/callshield/domain/repository/SyncRepository.kt`
- `app/src/main/java/com/sysadmindoc/callshield/domain/repository/BlocklistRepository.kt`
- `app/src/main/java/com/sysadmindoc/callshield/data/repository/SpamRepositoryAdapter.kt`
- `app/src/main/java/com/sysadmindoc/callshield/data/repository/SpamRepositoryImpl.kt`
- `app/src/main/java/com/sysadmindoc/callshield/data/repository/SettingsRepository.kt`
- `app/src/main/java/com/sysadmindoc/callshield/data/repository/SyncRepository.kt`
- `app/src/main/java/com/sysadmindoc/callshield/data/repository/BlocklistRepository.kt`
- `app/src/main/java/com/sysadmindoc/callshield/data/checker/Checkers.kt`
- `app/src/main/java/com/sysadmindoc/callshield/data/checker/IChecker.kt`
- `app/src/main/java/com/sysadmindoc/callshield/data/model/HashWildcardRule.kt`
- `app/src/main/java/com/sysadmindoc/callshield/data/CallCategory.kt`
- `app/src/main/java/com/sysadmindoc/callshield/service/CallLogScanner.kt`
- `app/src/main/java/com/sysadmindoc/callshield/service/CallShieldScreeningService.kt`
- `app/src/main/java/com/sysadmindoc/callshield/service/RcsNotificationListener.kt`
- `app/src/main/java/com/sysadmindoc/callshield/service/SmsInboxScanner.kt`
- `app/src/main/java/com/sysadmindoc/callshield/service/SmsReceiver.kt`
- `app/src/main/java/com/sysadmindoc/callshield/service/SyncWorker.kt`
- `app/src/main/java/com/sysadmindoc/callshield/service/HotListSyncWorker.kt`
- `app/src/main/java/com/sysadmindoc/callshield/service/DigestWorker.kt`
- `app/src/main/java/com/sysadmindoc/callshield/service/HotDataSync.kt`
- `app/src/main/java/com/sysadmindoc/callshield/ui/screens/onboarding/OnboardingScreen.kt`
- `app/src/main/java/com/sysadmindoc/callshield/ui/MainActivity.kt`
- `app/src/main/java/com/sysadmindoc/callshield/ui/MainViewModel.kt`
- `app/src/main/java/com/sysadmindoc/callshield/ui/screens/main/DashboardScreen.kt`
- `app/src/main/java/com/sysadmindoc/callshield/ui/screens/main/BlocklistScreen.kt`
- `app/src/main/java/com/sysadmindoc/callshield/ui/screens/settings/SettingsScreen.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/androidTest/java/com/sysadmindoc/callshield/data/SpamPipelineIntegrationTest.kt`
- `app/src/androidTest/java/com/sysadmindoc/callshield/data/SmsPipelineIntegrationTest.kt`
- `app/src/androidTest/java/com/sysadmindoc/callshield/data/SyncIntegrationTest.kt`
- `app/src/androidTest/java/com/sysadmindoc/callshield/service/HotListSyncIntegrationTest.kt`
- `app/src/androidTest/java/com/sysadmindoc/callshield/ui/screens/onboarding/OnboardingTest.kt`
- `app/src/androidTest/java/com/sysadmindoc/callshield/ui/screens/main/DashboardTest.kt`
- `app/src/androidTest/java/com/sysadmindoc/callshield/ui/screens/main/BlocklistTest.kt`
- `app/src/androidTest/java/com/sysadmindoc/callshield/ui/screens/settings/SettingsTest.kt`
- `ROADMAP.md`
- `CHANGELOG.md`
- `CLAUDE.md`
- `PROJECT_CONTEXT.md`

Work completed:

- added a constructor-provided `AppDatabase` seam to `SpamRepository` while preserving the production singleton path
- added a narrow `SpamDataSource` seam so `syncFromGitHub()` can be tested without live GitHub requests
- added in-memory Room instrumented tests for the call checker pipeline priority tiers
- added in-memory Room instrumented tests for SMS keyword/content ordering and whitelisted-sender inspection
- added mocked in-memory Room sync integration tests for remote snapshot population and user-block preservation
- added a narrow `HotFeedDataSource` seam and in-memory Room integration coverage for hot numbers, hot ranges, spam domains, duplicate/invalid feed entries, and preservation of stronger existing database rows
- split onboarding's real Android launchers from deterministic screen content and added stable tags for request actions
- added Compose UI tests for the onboarding four-page walkthrough, permission affordances, call-screener setup action, and unsupported-screener fallback
- extracted dashboard hero, setup checklist, and stats-row composables that production uses directly
- added Compose UI tests for dashboard hero copy, setup progress, sync freshness, blocked-count stats, and the call-screener setup action
- added swipe-left removal with snackbar undo for manual blocklist rows
- added Compose UI tests for blocklist add normalization, wildcard regex validation, delete action, and swipe removal
- extracted quiet-hours settings into a production-used composable, added an all-day validation warning, and added Compose tests for toggle callbacks plus hour picker selection
- added Kover 0.9.8 through the version catalog, refreshed the locked Gradle graph, and applied the plugin to the app module
- configured `:app:koverVerifyDebug` with a 35% minimum line threshold over the JVM-tested data/util core, excluding `data.local` because Room is covered by instrumented tests and Kover does not collect device-test coverage
- added CI execution for `:app:koverVerifyDebug` and `:app:koverXmlReportDebug`
- added ktlint-gradle 14.2.0, ktlint CLI 1.8.0, detekt 1.23.8, and a repo `.editorconfig`
- generated ktlint and detekt baselines so the new static-analysis job gates regressions without forcing a broad reformat/refactor
- added a CI `static-analysis` job that runs `:app:ktlintCheck` and `:app:detekt` and uploads both report directories
- added domain use-case wrappers for call/SMS checks, sync, blocklist management, and exports
- routed the live call-screening service, SMS receiver, RCS listener, historical call/SMS scanners, sync worker, and `MainViewModel` blocklist/export operations through those wrappers while preserving `SpamRepository` as the implementation
- moved `SpamCheckResult` and `SyncResult` from the bottom of `SpamRepository.kt` into `domain/model`
- updated call category resolution, lookup/detail UI, use cases, and tests to import the domain result models
- added domain repository contracts for spam checks, database sync, and blocklist management
- added `SpamRepositoryAdapter` to bridge those contracts to the `SpamRepository` facade without changing repository defaults
- updated use cases to depend on domain repository interfaces instead of concrete `SpamRepository`
- split `SpamRepository` into a compatibility facade backed by `SpamRepositoryImpl`, `SettingsRepository`, `SyncRepository`, and `BlocklistRepository`
- moved the checker registry to build against `SpamRepositoryImpl`, keeping the 5-second screening path and one-snapshot settings contract intact
- added Hilt 2.58 to the Gradle plugin/runtime/compiler graph, applied the app plugin, enabled the aggregating task, and annotated `CallShieldApp` with `@HiltAndroidApp`
- rejected stale Hilt 2.52 after KSP processing failed and rejected Hilt 2.59.2 because its Gradle plugin requires AGP 9; 2.58 compiles on the current AGP 8.10.1 stack
- added `DatabaseModule` with Hilt providers for `AppDatabase` and `SpamDao`
- added `RepositoryModule` with Hilt bindings from domain repository interfaces to `SpamRepositoryAdapter`, with the existing `SpamRepository` facade provided from `getInstance()`
- added `NetworkModule` with a Hilt provider for the existing pinned `HttpClient.shared` `OkHttpClient`
- annotated `MainActivity` as a Hilt entry point and migrated `MainViewModel` to `@HiltViewModel` with injected `SpamRepository`, `SyncDatabaseUseCase`, `ManageBlocklistUseCase`, and `ExportLogsUseCase`
- added a Hilt provider for `CheckSpamUseCase` and migrated `CallShieldScreeningService` to `@AndroidEntryPoint` field injection while keeping the one-snapshot 5-second decision path and fail-open behavior
- added AndroidX Hilt Work 1.3.0, installed `HiltWorkerFactory` through `CallShieldApp`, removed WorkManager's default initializer, and migrated `SyncWorker`, `HotListSyncWorker`, and `DigestWorker` to `@HiltWorker` assisted injection
- provided `GitHubDataSource` as the shared Hilt `SpamDataSource` and `HotFeedDataSource` binding for injected feed refresh paths
- added `DetectionModule` and `CheckerDependencies`, then routed the live checker chain, screening-service contact shortcut, app-startup model/hot-data priming, and sync/hot-list workers through injected detection helper dependencies while preserving existing object facades
- converted `SpamHeuristics`, `SmsContentAnalyzer`, `SpamMLScorer`, `CallbackDetector`, `SmsContextChecker`, `CampaignDetector`, and `HashWildcardMatcher` from Kotlin `object` declarations into constructor-injectable classes with shared companion facades for existing static call sites
- regenerated the ktlint baseline after modifying previously-baselined files so the new gate reflects the current debt set
- marked roadmap items 1.2.1 through 1.2.4, 1.3.1 through 1.3.4, 1.4.4, 1.4.5, 1.5.1 through 1.5.4, and 1.6.1 through 1.6.9 done
- verified `:app:compileDebugAndroidTestKotlin`, `testDebugUnitTest`, `:app:lintDebug`, `:app:ktlintCheck`, `:app:detekt`, and the Kover debug coverage gate

### 0. F-Droid submission prep

Files:

- `.gitignore`
- `fastlane/metadata/android/en-US/title.txt`
- `fastlane/metadata/android/en-US/short_description.txt`
- `fastlane/metadata/android/en-US/full_description.txt`
- `fastlane/metadata/android/en-US/changelogs/38.txt`
- `docs/fdroid/com.sysadmindoc.callshield.yml`
- `docs/fdroid-submission.md`
- `docs/reproducible-builds.md`
- `CHANGELOG.md`
- `ROADMAP.md`

Work completed:

- added Fastlane listing metadata for F-Droid-compatible localized store copy
- drafted the `fdroiddata` YAML for `com.sysadmindoc.callshield`, including the v1.7.10 commit hash, upstream release APK URL, and expected release signer SHA256
- documented the remaining fdroidserver/GitLab MR flow and signature-copy verification inputs
- ignored generated APK/AAB hash sidecars so release artifacts stay attached to GitHub Releases instead of entering source control
- marked B.D.1 as WIP because the actual F-Droid merge request and apksigcopier validation require an fdroiddata/GitLab environment

### 1. v1.7.10 roadmap continuation — Compose BOM refresh

Files:

- `gradle/libs.versions.toml`
- `app/build.gradle.kts`
- `app/gradle.lockfile`
- `app/src/main/java/com/sysadmindoc/callshield/ui/MainActivity.kt`
- `app/src/main/java/com/sysadmindoc/callshield/ui/screens/details/NumberDetailScreen.kt`
- `app/src/main/java/com/sysadmindoc/callshield/ui/screens/lookup/LookupScreen.kt`
- `app/src/main/java/com/sysadmindoc/callshield/ui/screens/main/BlockedLogScreen.kt`
- `app/src/main/java/com/sysadmindoc/callshield/ui/screens/main/BlocklistScreen.kt`
- `app/src/main/java/com/sysadmindoc/callshield/ui/screens/onboarding/OnboardingScreen.kt`
- `app/src/main/java/com/sysadmindoc/callshield/ui/screens/recent/RecentCallsScreen.kt`
- `app/src/main/java/com/sysadmindoc/callshield/ui/screens/settings/SettingsScreen.kt`

Work completed:

- upgraded Compose BOM to 2026.05.00, resolving core Compose artifacts to 1.11.1 and Material 3 to 1.4.0
- regenerated app dependency locks across debug, release, and unit-test classpaths so the Compose/lifecycle/core/savedstate graph is coherent
- fixed newly enforced Compose lint by replacing `LocalContext.getString` and `LocalContext.current.resources` reads in composables with `stringResource`, `pluralStringResource`, and `LocalResources`
- bumped app metadata to v1.7.10 / versionCode 38 and synchronized README, CHANGELOG, CODEX_CHANGELOG, and ROADMAP

### 2. v1.7.9 roadmap continuation — WorkManager refresh

Files:

- `gradle/libs.versions.toml`
- `app/build.gradle.kts`
- `app/gradle.lockfile`
- `app/src/main/java/com/sysadmindoc/callshield/service/SyncWorker.kt`
- `app/src/main/java/com/sysadmindoc/callshield/service/HotListSyncWorker.kt`
- `app/src/main/java/com/sysadmindoc/callshield/service/DigestWorker.kt`
- `app/src/test/java/com/sysadmindoc/callshield/service/WorkerScheduleTest.kt`
- `app/src/test/java/com/sysadmindoc/callshield/data/SmsContentAnalyzerTest.kt`

Work completed:

- upgraded WorkManager to 2.11.2 and refreshed all locked WorkManager classpaths
- preserved the existing unique periodic work names and `ExistingPeriodicWorkPolicy.KEEP` behavior
- exposed each worker's WorkRequest construction through internal companion helpers so schedule details are unit-testable
- added schedule contract tests for sync, manual sync, hot-list refresh, and daily digest requests
- stabilized the SMS large-body DoS guard timing test by warming the analyzer outside the measured path
- bumped app metadata to v1.7.9 / versionCode 37 and synchronized README, CHANGELOG, CODEX_CHANGELOG, and ROADMAP

### 3. v1.7.8 roadmap continuation — DataStore privacy hardening

Files:

- `gradle/libs.versions.toml`
- `app/build.gradle.kts`
- `app/gradle.lockfile`
- `app/src/main/java/com/sysadmindoc/callshield/data/SpamRepository.kt`
- `app/src/main/res/xml/backup_rules.xml`
- `app/src/main/res/xml/data_extraction_rules.xml`

Work completed:

- upgraded DataStore Preferences to 1.2.1 and refreshed dependency locks
- audited the settings/backup layer and confirmed there is no current `EncryptedSharedPreferences`, `androidx.security`, or Tink preference path to migrate
- moved the optional AbstractAPI enrichment key to a private DataStore created under `noBackupFilesDir`
- added first-read/save migration that copies any legacy public key into no-backup storage and then removes the public value
- corrected backup-rule comments and user-facing README security copy to distinguish public settings restore from private credential storage
- bumped app metadata to v1.7.8 / versionCode 36 and synchronized README, CHANGELOG, CODEX_CHANGELOG, and ROADMAP

### 3. v1.7.7 roadmap continuation — reproducible-build groundwork

Files:

- `build.gradle.kts`
- `.github/workflows/build.yml`
- `.github/workflows/test.yml`
- `app/build.gradle.kts`
- `app/gradle.lockfile`
- `settings-gradle.lockfile`
- `docs/reproducible-builds.md`
- `scripts/write-release-sha256.ps1`

Work completed:

- enabled Gradle dependency locking across the repo and checked in generated lockfiles
- added `verifyReproducibleBuildInputs` to fail build-script wall-clock metadata embedding
- disabled AGP VCS metadata in release APKs and added `verifyReleaseApkReproducibleMetadata`
- added CI and local SHA256 sidecar generation for APK release artifacts
- added `scripts/compare-apk-contents.ps1` after clean rebuilds showed identical ZIP entries but different APK Signing Block bytes
- documented the signed local release versus unsigned CI artifact hash-comparison workflow without claiming raw signed SHA256 reproducibility
- bumped app metadata to v1.7.7 / versionCode 35 and synchronized README, CHANGELOG, CLAUDE, and ROADMAP

### 4. v1.7.6 roadmap continuation — network dependency hardening

Files:

- `gradle/libs.versions.toml`
- `app/build.gradle.kts`
- `app/src/main/java/com/sysadmindoc/callshield/data/remote/HttpClient.kt`
- `app/src/main/java/com/sysadmindoc/callshield/data/local/AppDatabase.kt`
- `app/src/main/java/com/sysadmindoc/callshield/data/model/SpamNumber.kt`
- `app/src/test/java/com/sysadmindoc/callshield/data/remote/HttpClientTest.kt`

Work completed:

- upgraded AGP to 8.10.1, Kotlin/KSP to 2.2.21, Room to 2.8.4, and OkHttp to 5.3.2
- added central OkHttp `CertificatePinner` coverage for GitHub, Cloudflare Worker, URLhaus, AbstractAPI, and caller-ID enrichment endpoints
- added a focused unit test for pinned-host inventory, pin format, and enforcement behavior
- bumped app metadata to v1.7.6 / versionCode 34 and synchronized README, CHANGELOG, CLAUDE, and ROADMAP

### 5. Main UX / premium-polish pass already present in the tree

These areas were already improved earlier in the thread and remain part of the current worktree:

- `app/src/main/java/com/sysadmindoc/callshield/ui/MainActivity.kt`
- `app/src/main/java/com/sysadmindoc/callshield/ui/screens/recent/RecentCallsScreen.kt`
- `app/src/main/java/com/sysadmindoc/callshield/ui/screens/lookup/LookupScreen.kt`
- `app/src/main/java/com/sysadmindoc/callshield/ui/screens/more/MoreScreen.kt`
- `app/src/main/java/com/sysadmindoc/callshield/ui/screens/main/BlockedLogScreen.kt`
- `app/src/main/java/com/sysadmindoc/callshield/ui/screens/main/BlocklistScreen.kt`
- `app/src/main/java/com/sysadmindoc/callshield/ui/screens/details/NumberDetailScreen.kt`
- `app/src/main/java/com/sysadmindoc/callshield/ui/screens/settings/SettingsScreen.kt`
- `app/src/main/java/com/sysadmindoc/callshield/ui/screens/stats/StatsScreen.kt`
- `app/src/main/java/com/sysadmindoc/callshield/ui/screens/more/ProtectionTestScreen.kt`
- `app/src/main/java/com/sysadmindoc/callshield/ui/screens/onboarding/OnboardingScreen.kt`
- `app/src/main/res/values/strings.xml`

That work included:

- richer recent-calls and lookup UX
- better app shell/search behavior
- improved blocked log, blocklist, number detail, settings, stats, diagnostics, and onboarding
- stronger copy/resource consistency
- a more polished More hub and supporting navigation

### 3. Data sync integrity hardening

Files:

- `app/src/main/java/com/sysadmindoc/callshield/data/SpamRepository.kt`
- `app/src/main/java/com/sysadmindoc/callshield/data/local/SpamDao.kt`
- `app/src/test/java/com/sysadmindoc/callshield/data/SpamRepositorySyncTest.kt`

Problems fixed:

- Sync refreshes could overwrite or discard user-owned blocked numbers because rows are unique by number and some replace flows were too blunt.
- Hot-list replacement could clobber stronger existing entries from the main database.
- Source-based cleanup risked deleting user-blocked rows during feed refreshes.

Fixes made:

- preserved user-blocked numbers during GitHub database refresh
- made `deleteBySource` avoid deleting user-blocked rows
- added DAO helpers for safer merge logic
- replaced naive hot-list replacement with merge-aware logic that preserves stronger existing rows and existing user-block state
- added focused regression tests for the sync merge/sanitization helpers

### 4. Callback allow-through hardening

Files:

- `app/src/main/java/com/sysadmindoc/callshield/data/SpamRepository.kt`
- `app/src/main/java/com/sysadmindoc/callshield/data/CallbackDetector.kt`
- `app/src/test/java/com/sysadmindoc/callshield/data/CallbackDetectorTest.kt`

Problems fixed:

- callback/repeated-call allow-through previously ran too early in the spam pipeline and could weaken explicit protection behavior
- the repeated-urgent call query counted the user's own outgoing calls, which could inflate the "urgent caller" signal
- legitimate callback traffic could still pollute the campaign-burst detector

Fixes made:

- moved callback allow-through later in the decision flow so explicit block data and wildcard rules win first
- limited repeated-urgent call detection to incoming/missed call-log entries instead of all recent calls
- moved campaign-detector recording to happen after trusted callback allow-through checks
- added focused tests for the call-log query builders so the filtering logic stays stable

### 5. Blocklist/whitelist reconciliation hardening

Files:

- `app/src/main/java/com/sysadmindoc/callshield/data/SpamRepository.kt`
- `app/src/test/java/com/sysadmindoc/callshield/data/SpamRepositorySyncTest.kt`

Problem fixed:

- blocklist and whitelist writes could leave contradictory local state even though whitelist always wins during spam evaluation, creating "I blocked it but it still rings" style confusion.

Fixes made:

- blocking a number now clears any existing whitelist entry for that number
- whitelisting a number now removes the user-block override state for that number
- shared database rows keep their base source metadata while the user-block flag is cleared
- added focused regression tests for the whitelist reconciliation helper

### 5. Dashboard stats correctness

Files:

- `app/src/main/java/com/sysadmindoc/callshield/ui/MainViewModel.kt`
- `app/src/main/java/com/sysadmindoc/callshield/ui/DashboardTimeWindows.kt`
- `app/src/test/java/com/sysadmindoc/callshield/ui/DashboardTimeWindowsTest.kt`

Problems fixed:

- "today / this week / last week" counters were previously based on rolling offsets instead of true local calendar boundaries.

Fixes made:

- introduced explicit dashboard time-window calculation for local calendar day/week boundaries
- updated `MainViewModel` to re-evaluate those windows over time
- added unit tests covering midnight snapping plus Sunday/Monday week-start behavior

### 6. Overlay lifecycle and stale-state hardening

Files:

- `app/src/main/java/com/sysadmindoc/callshield/service/CallerIdOverlayService.kt`

Problems fixed:

- repeated overlay launches could leave old lookup work running
- stale lookup results could update a newer overlay session
- an old auto-dismiss callback could dismiss a newer overlay
- button-triggered fire-and-forget work used ad hoc scopes

Fixes made:

- introduced session-aware overlay handling with per-session guards
- cancel old lookup jobs before showing a replacement overlay
- clear and replace dismiss callbacks safely
- prevent stale handler posts from mutating a newer overlay
- moved overlay action side effects onto `CallShieldApp.appScope` so they can outlive the overlay itself without leaking per-click scopes

### 7. After-call notification flow cleanup

Files:

- `app/src/main/java/com/sysadmindoc/callshield/service/CallShieldScreeningService.kt`
- `app/src/main/java/com/sysadmindoc/callshield/service/NotificationHelper.kt`
- `app/src/main/java/com/sysadmindoc/callshield/data/SpamRepository.kt`

Problems fixed:

- allowed unknown calls could trigger two different "was this spam?" feedback notifications: one immediately and one after a delay.
- the immediate notification path left dead helper code behind.

Fixes made:

- removed the immediate prompt path from `CallShieldScreeningService`
- kept the single delayed after-call feedback notification
- removed the now-unused repository/helper notification method

### 8. SMS receiver and phishing URL hardening

Files:

- `app/src/main/java/com/sysadmindoc/callshield/service/SmsReceiver.kt`
- `app/src/main/java/com/sysadmindoc/callshield/data/remote/UrlSafetyChecker.kt`
- `app/src/test/java/com/sysadmindoc/callshield/data/remote/UrlSafetyCheckerTest.kt`

Problems fixed:

- SMS processing spun a raw thread per received message and used the receiver context for follow-on work that could outlive `onReceive`.
- URL extraction could re-check duplicate links and included trailing punctuation, creating unnecessary network chatter and false negatives.

Fixes made:

- moved SMS blocking work onto the app-wide coroutine scope instead of creating a raw thread per SMS
- switched follow-on phishing notifications to use the application context
- normalized and deduplicated candidate URLs before URLhaus checks
- stripped common trailing punctuation from extracted URLs
- added focused regression tests for URL extraction/normalization

### 9. GitHub feed parsing hardening

Files:

- `app/src/main/java/com/sysadmindoc/callshield/data/remote/GitHubDataSource.kt`
- `app/src/test/java/com/sysadmindoc/callshield/data/remote/GitHubDataSourceTest.kt`

Problems fixed:

- hot-list, hot-range, and spam-domain parsing relied on brittle regex/string splitting even though the feed files are structured JSON envelopes.
- parsing was fragile against minor shape changes and harder to maintain.

Fixes made:

- replaced brittle parsing with Moshi-backed, shape-aware parsing
- supported both current metadata-envelope JSON and legacy top-level array shapes
- trimmed and normalized parsed values defensively
- added unit tests for current and legacy payload shapes

### 10. Backup/privacy boundary tightening

Files:

- `app/src/main/res/xml/data_extraction_rules.xml`

Problem fixed:

- device-to-device transfer rules were broader than the app's privacy posture and included the entire `files/` domain, which could migrate operational artifacts such as local crash logs.

Fix made:

- narrowed device-transfer backup scope to user data only (`database` plus `file/datastore/`) and explicitly left operational/generated files local

### 11. ML model sync hardening

Files:

- `app/src/main/java/com/sysadmindoc/callshield/data/SpamMLScorer.kt`
- `app/src/test/java/com/sysadmindoc/callshield/data/SpamMLScorerTest.kt`

Problems fixed:

- remote model sync could overwrite the cached model file without first proving the downloaded JSON was actually usable
- a bad model payload could downgrade the in-memory scorer to defaults even when a previously valid model was already loaded

Fixes made:

- added a safe model-apply path that snapshots and restores the prior scorer state when new JSON is invalid
- only persist downloaded model JSON after it parses successfully
- switched model-file writes to a temp-file flow before replacing the cached file
- added a regression test proving invalid model JSON no longer replaces an already-loaded valid model

### 12. Backup emergency-contact preservation

Files:

- `app/src/main/java/com/sysadmindoc/callshield/data/BackupRestore.kt`
- `app/src/test/java/com/sysadmindoc/callshield/data/BackupRestoreTest.kt`

Problem fixed:

- backup/restore did not preserve the whitelist `isEmergency` flag, so emergency contacts could silently downgrade to normal whitelist entries after migration/restore.

Fixes made:

- added `isEmergency` to the backup whitelist payload with a backward-compatible default
- now export emergency state in backups and restore it correctly
- added tests covering the new field and backward-compatible default behavior

### 13. Small UX / locale cleanup

Files:

- `app/src/main/java/com/sysadmindoc/callshield/ui/screens/more/MoreScreen.kt`

Fix made:

- replaced locale-sensitive `String.format("%,d", ...)` usage with `NumberFormat.getIntegerInstance()` for safer localized display and to clear the lint warning

### 14. Premium UX / UI refinement pass

Files:

- `app/src/main/java/com/sysadmindoc/callshield/ui/theme/Theme.kt`
- `app/src/main/java/com/sysadmindoc/callshield/ui/MainActivity.kt`
- `app/src/main/java/com/sysadmindoc/callshield/ui/screens/main/BlocklistScreen.kt`
- `app/src/main/java/com/sysadmindoc/callshield/ui/screens/main/DashboardScreen.kt`
- `app/src/main/res/values/strings.xml`

Problems fixed:

- the shared app chrome still mixed premium surfaces with relatively plain Material defaults, which made tab-to-tab navigation feel less intentional than the stronger individual screens
- search states were functional but not especially polished: the idle state was better than the no-results state, and the shell did not clearly communicate context/mode
- Blocklist remained one of the densest screens in the product: unlabeled stacked FABs, crowded count-heavy tabs, generic empty states, and flatter list treatment made a key power-user workflow feel more utilitarian than premium
- repeated status badges were implemented inconsistently across screens

Fixes made:

- added a reusable `StatusPill` primitive in the theme so badges and state labels now share a more coherent visual treatment
- upgraded the main app chrome into a stronger premium header surface with accent wash, richer mode/status pills, a more intentional search affordance, and improved search field treatment
- upgraded search no-results feedback from a bare icon/text state into a guided premium card with useful recovery hints
- rebuilt Blocklist as a clearer workspace: short tab labels, a contextual overview card for the active rule set, labeled primary actions, import/export surfaced where users actually need them, and richer empty states
- replaced ambiguous stacked FAB-only actions with a labeled extended FAB for the active Blocklist workspace
- improved Blocklist item readability by formatting phone numbers, adding clearer badges, and making trusted/emergency/database entries feel more deliberate and easier to scan
- tightened Blocklist entry dialogs with sanitized phone-number input, better validation, clearer confirmation states, and trimmed writes
- switched Dashboard setup badges onto the shared status-pill treatment for better consistency with the rest of the shell

### 15. Lookup and More premium-flow refinement

Files:

- `app/src/main/java/com/sysadmindoc/callshield/ui/screens/lookup/LookupScreen.kt`
- `app/src/main/java/com/sysadmindoc/callshield/ui/screens/more/MoreScreen.kt`
- `app/src/main/res/values/strings.xml`

Problems fixed:

- Lookup still behaved more like a utility form than a polished analysis flow: it had abrupt error/success feedback, weak idle guidance, and a less premium sense of progress while checks were running
- Lookup action feedback relied on toasts instead of calmer, more integrated in-screen messaging
- More was visually solid but still read like a long stack of separate cards and links rather than a clearly grouped control center with stronger trust framing

Fixes made:

- rebuilt Lookup around clearer state-driven UX: stronger header framing, richer input treatment, explicit idle/progress/error cards, and a more polished result presentation with better explainability
- switched Lookup action feedback from toasts to snackbar-style in-screen confirmation so block/report/trust actions feel calmer and more consistent
- tightened Lookup copy so it emphasizes on-device analysis, explainability, and trusted-number handling
- regrouped More into clearer sections for protection tools, release/diagnostics, and project/support so the hub feels more intentional and easier to scan
- added stronger trust signals to More (`On-device`, `Open source`, `No account`) and richer support-link subtitles so the product reads as more transparent and premium
- refined More’s action rows and snapshot presentation to reduce repetition and give the hub a cleaner, more curated feel

### 16. Recent and onboarding premium-state refinement

Files:

- `app/src/main/java/com/sysadmindoc/callshield/ui/screens/recent/RecentCallsScreen.kt`
- `app/src/main/java/com/sysadmindoc/callshield/ui/screens/onboarding/OnboardingScreen.kt`
- `app/src/main/res/values/strings.xml`

Problems fixed:

- Recent still had relatively plain permission and empty states, so one of the app’s most frequent review surfaces felt more functional than polished when the user hit edge conditions
- Recent’s summary area was useful but visually flatter than the newer premium surfaces
- Onboarding already covered the right setup steps, but its progress and footer treatments still felt more mechanical than confidence-building
- onboarding checklist badges were less visually consistent with the status-pill language used elsewhere in the app

Fixes made:

- upgraded Recent summary treatment with clearer hierarchy, live/refreshing state badges, and localized count formatting
- rebuilt Recent permission and empty states into richer premium cards with better recovery guidance and a direct reset action when filters produce no matches
- added helper guidance rows to Recent so the screen explains privacy and recovery paths more clearly when permission is missing
- upgraded onboarding progress into a stronger setup-status card with concise required/optional progress pills
- added trust badges to the onboarding welcome page so the product’s privacy/open-source posture is visible immediately
- wrapped onboarding navigation into a premium footer card and switched checklist badges onto the shared `StatusPill` treatment for better consistency with the rest of the app

## Verification Completed

All of these passed after the latest changes:

- `.\gradlew testDebugUnitTest --rerun-tasks --console=plain`
- `.\gradlew assembleDebug --rerun-tasks --console=plain`
- `.\gradlew lintDebug --rerun-tasks --console=plain`
- `.\gradlew testDebugUnitTest --console=plain`
- `.\gradlew assembleDebug --console=plain`
- `.\gradlew lintDebug --console=plain`

Verification note:

- one initial Gradle run hit the same Windows/Kotlin-daemon temp-directory cleanup issue seen earlier in this thread; after `.\gradlew --stop` and clearing `app\build\tmp\kotlin-classes\debug`, verification passed normally

Lint report path:

- `C:\Users\--\repos\CallShield\app\build\reports\lint-results-debug.html`

## Current Worktree Notes

- No commit was created in this thread after the latest audit/hardening work.
- `SpamRepository.kt` and `CallerIdOverlayService.kt` had pre-existing local edits before this audit. This pass made additive fixes inside those files and did not intentionally revert earlier local work.
- There is still no emulator/device QA captured in this handoff. Verification so far is build, unit-test, and lint based.

## Highest-Value Remaining Follow-Up

1. Run live emulator/device QA on onboarding, recent calls, lookup, blocked log, More, and the caller-ID overlay.
2. Validate overlay behavior during rapid successive incoming calls to confirm the session guards behave correctly in real system conditions.
3. Smoke-test GitHub feed refresh with real network responses and bundled fallbacks.
4. Continue auditing long-tail heuristics/telephony paths such as callback detection, notification/action compatibility across upgrades, and backup/restore boundaries.
