# CallShield Project Context

Last verified: 2026-05-17

## Live State

- Branch: `master`, tracking `origin/master`.
- Latest observed commit before this pass: `c20f01a chore: prep fdroid submission`.
- Current release: v1.7.10, `versionCode` 38.
- Stack: Kotlin Android, Jetpack Compose BOM 2026.05.00, AGP 8.10.1, Kotlin/KSP 2.2.21, Room 2.8.4, WorkManager 2.11.2, OkHttp 5.3.2, DataStore 1.2.1.
- Source shape after this pass: 78 main Kotlin files, 35 JVM test files, 5 instrumented test files.
- Shared project memory still lags around v1.7.2; treat live repo docs, Gradle files, and git history as authoritative.

## Architecture Notes

- The call/SMS decision path is a priority-sorted `IChecker` pipeline built once by `SpamRepository`.
- `CallShieldScreeningService` has a hard 5-second Android deadline and must keep using one DataStore snapshot per call.
- Room migrations are explicit from DB v5 onward; v1-v4 remain destructive legacy fallbacks because schemas were not exported.
- `HttpClient.shared` owns the pinned OkHttp client. New HTTPS endpoints need SPKI pins and `HttpClientTest` coverage.
- Optional AbstractAPI credentials live in a private no-backup DataStore and are not part of the blocking path.

## Current Roadmap Position

- Completed in this pass: roadmap 1.2.1 and 1.2.2.
- `SpamRepository(context, database)` now supports an injected `AppDatabase`, enabling in-memory Room integration tests without committing to the larger Hilt/DI refactor.
- Added instrumented tests:
  - `SpamPipelineIntegrationTest`: manual whitelist, STIR/SHAKEN failed/trusted ordering, user blocklist, prefix, wildcard, hash wildcard, and frequency tiers.
  - `SmsPipelineIntegrationTest`: whitelisted-sender inspection, keyword-before-content ordering, and SMS content-analysis block behavior.

## Next Practical Task

Roadmap 1.2.3 is next: `syncFromGitHub()` mocked integration coverage. It needs a mockable remote data-source seam because `GitHubDataSource` is currently a concrete network class created by `SpamRepository`.

Keep the seam narrow: introduce an interface around update-check/database-fetch behavior, adapt the current `GitHubDataSource`, and write the in-memory Room sync test before any repository split.
