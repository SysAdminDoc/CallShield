# CallShield Project Context

Last verified: 2026-05-17

## Live State

- Branch: `master`, tracking `origin/master`.
- Latest observed commit before this pass: `c20f01a chore: prep fdroid submission`.
- Current release: v1.7.10, `versionCode` 38.
- Stack: Kotlin Android, Jetpack Compose BOM 2026.05.00, AGP 8.10.1, Kotlin/KSP 2.2.21, Room 2.8.4, WorkManager 2.11.2, OkHttp 5.3.2, DataStore 1.2.1.
- Source shape after this pass: 80 main Kotlin files, 35 JVM test files, 11 instrumented test files.
- Shared project memory still lags around v1.7.2; treat live repo docs, Gradle files, and git history as authoritative.

## Architecture Notes

- The call/SMS decision path is a priority-sorted `IChecker` pipeline built once by `SpamRepository`.
- `CallShieldScreeningService` has a hard 5-second Android deadline and must keep using one DataStore snapshot per call.
- Room migrations are explicit from DB v5 onward; v1-v4 remain destructive legacy fallbacks because schemas were not exported.
- `HttpClient.shared` owns the pinned OkHttp client. New HTTPS endpoints need SPKI pins and `HttpClientTest` coverage.
- Optional AbstractAPI credentials live in a private no-backup DataStore and are not part of the blocking path.

## Current Roadmap Position

- Completed in this pass: roadmap 1.2.1, 1.2.2, 1.2.3, 1.2.4, 1.3.1, 1.3.2, 1.3.3, and 1.3.4.
- `SpamRepository(context, database, remote)` now supports injected `AppDatabase` and `SpamDataSource` dependencies, enabling in-memory Room integration tests without committing to the larger Hilt/DI refactor.
- `HotDataSync.refresh(context, source, repo, dao)` now supports an injected `HotFeedDataSource`, enabling hot-feed integration tests without live GitHub requests.
- Added instrumented tests:
  - `SpamPipelineIntegrationTest`: manual whitelist, STIR/SHAKEN failed/trusted ordering, user blocklist, prefix, wildcard, hash wildcard, and frequency tiers.
  - `SmsPipelineIntegrationTest`: whitelisted-sender inspection, keyword-before-content ordering, and SMS content-analysis block behavior.
  - `SyncIntegrationTest`: mocked remote database sync, Room population, and user-block preservation during remote refresh.
  - `HotListSyncIntegrationTest`: mocked hot-feed refresh for hot number insertion, duplicate/invalid-entry tolerance, hot-range refresh, spam-domain refresh, and stronger existing-row preservation.
  - `OnboardingTest`: deterministic Compose coverage for the four-page walkthrough, permission affordances, call-screener setup action, and unavailable-screener fallback.
  - `DashboardTest`: deterministic Compose coverage for dashboard hero copy, setup progress, sync freshness, blocked-count stats, and call-screener setup action.
  - `BlocklistTest`: deterministic Compose coverage for manual add normalization, wildcard regex validation, delete action, and swipe removal.
  - `SettingsTest`: deterministic Compose coverage for quiet-hours toggle callback, same-hour validation warning, hour picker selection, and hour-label formatting.
- Manual blocklist rows now support swipe-left removal with snackbar undo, matching the blocked-log recovery pattern.
- Quiet-hours settings now show an explicit all-day warning when start and end match.

## Next Practical Task

Roadmap 1.4.4 is next: add a code-coverage gate via Kover with a ratcheting threshold.

Verify the current Gradle plugin catalog and CI shape before adding Kover, then keep the threshold conservative enough for the current test mix.
