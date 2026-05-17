# CallShield Project Context

Last verified: 2026-05-17

## Live State

- Branch: `master`, tracking `origin/master`.
- Latest observed commit before this pass: `c20f01a chore: prep fdroid submission`.
- Current release: v1.7.10, `versionCode` 38.
- Stack: Kotlin Android, Jetpack Compose BOM 2026.05.00, AGP 8.10.1, Kotlin/KSP 2.2.21, Room 2.8.4, WorkManager 2.11.2, OkHttp 5.3.2, DataStore 1.2.1.
- Source shape after this pass: 87 main Kotlin files, 35 JVM test files, 11 instrumented test files.
- CI now runs Kover 0.9.8 for the debug JVM unit-test report. The coverage gate is scoped to `data.*` and `util.*`, excludes `data.local.*`, and currently passes at 41.06% line coverage against a 35% floor.
- CI now also has a `static-analysis` job running ktlint 1.8.0 and detekt 1.23.8. Existing debt is captured in `app/config/ktlint/baseline.xml` and `app/detekt-baseline.xml`.
- Shared project memory still lags around v1.7.2; treat live repo docs, Gradle files, and git history as authoritative.

## Architecture Notes

- The call/SMS decision path is a priority-sorted `IChecker` pipeline built once by `SpamRepository`.
- `domain/usecase` now has production-used wrappers for spam checks, SMS checks, sync, blocklist management, and exports. They still delegate to `SpamRepository`; repository interfaces are next.
- `domain/model` now owns `SpamCheckResult` and `SyncResult`. No standalone settings model existed before 1.5.2, so settings keys/flows remain in `SpamRepository` until the repository-interface split.
- `CallShieldScreeningService` has a hard 5-second Android deadline and must keep using one DataStore snapshot per call.
- Room migrations are explicit from DB v5 onward; v1-v4 remain destructive legacy fallbacks because schemas were not exported.
- `HttpClient.shared` owns the pinned OkHttp client. New HTTPS endpoints need SPKI pins and `HttpClientTest` coverage.
- Optional AbstractAPI credentials live in a private no-backup DataStore and are not part of the blocking path.

## Current Roadmap Position

- Completed in this pass: roadmap 1.2.1, 1.2.2, 1.2.3, 1.2.4, 1.3.1, 1.3.2, 1.3.3, 1.3.4, 1.4.4, 1.4.5, 1.5.1, and 1.5.2.
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

Roadmap 1.5.3 is next: define repository interfaces in `domain/repository`.

Keep the next move narrow: introduce interfaces that match the already-wired use cases first, then make `SpamRepository` implement them without splitting storage/network/settings responsibilities yet.
