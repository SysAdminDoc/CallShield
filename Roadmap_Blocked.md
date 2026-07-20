# CallShield — Blocked Roadmap Items

Items moved here from ROADMAP.md because they require external action, dedicated sessions, or have dependency gates that prevent autonomous implementation.

## Blocked on Dedicated Session (too large for autonomous batch)

- [ ] P1 — AGP 9 + Kotlin 2.3 + Hilt 2.59 + Moshi→kotlinx.serialization migration tranche
  Why: AGP 8.x is on a deprecation path; Hilt 2.59+ drops AGP 8 support; Moshi 1.x codegen requires KSP1 while KSP2 is now default; AGP 10 removes all escape hatches mid-2026. All four must move together.
  Blocker: XL complexity — touches every build file, all lockfiles, all JSON parsing sites. Requires a dedicated session with build validation.
  Evidence: AGP 9 release notes; Dagger 2.59 release; Kotlin 2.3.0/2.3.20 changelogs; Moshi KSP2 compatibility issues.
  Touches: `build.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, all Gradle lockfiles, `proguard-rules.pro`, all JSON parsing sites, Hilt module files.
  Complexity: XL

- [ ] P1 — Compose Testing v2 migration
  Why: Compose Testing v1 APIs are deprecated in BOM 2026.04.01. The 11 instrumented UI test files use v1 APIs. Must migrate before the next BOM bump.
  Blocker: Requires BOM version investigation to identify which specific APIs changed and how to migrate each test file.
  Evidence: Compose BOM 2026.04.01 deprecation notes; `app/src/androidTest/` test files.
  Touches: all `androidTest` Compose UI test files, `app/build.gradle.kts` test dependencies.
  Complexity: M

- [ ] P1 — SMS Screening Provider mode (permission reduction)
  Why: SpamBlocker v5.9 implemented Android's SMS screening ContentProvider protocol, eliminating RECEIVE_SMS permission. Cleaner architecture.
  Blocker: Substantial new ContentProvider implementation; needs Android SMS screening API research and backwards-compat with existing SmsReceiver.
  Evidence: SpamBlocker v5.9 release; Android SMS screening API docs.
  Touches: new `service/SmsScreeningProvider.kt`, `AndroidManifest.xml`, `SmsReceiver.kt`, settings toggle, tests.
  Complexity: L

- [ ] P1 — Hash community report submissions for privacy
  Why: CommunityContributor.kt transmits raw E.164 numbers to the Cloudflare Worker, which writes them to GitHub as a public phone number directory.
  Blocker: Requires coordinated changes across client, Worker, merge scripts, and migration plan for existing raw-number reports. Breaking change to report pipeline.
  Evidence: PhoneBlock privacy model; `data/CommunityContributor.kt`; `worker/community-reports-worker.js`.
  Touches: `CommunityContributor.kt`, `worker/community-reports-worker.js`, `scripts/merge_community_reports.py`, report JSON schema.
  Complexity: M

## Blocked on External Action

- [ ] P1 — F-Droid submission and signature-copy verification
  Why: Metadata, Fastlane listing, release signer handoff, and local runbook exist, but the actual publication requires an fdroiddata fork/MR plus fdroidserver/apksigcopier verification in that environment.
  Blocker: Requires external GitLab/fdroiddata workflow and F-Droid review.
  Evidence: `docs/fdroid/com.sysadmindoc.callshield.yml`, `docs/fdroid-submission.md`, `fastlane/metadata/android/en-US/`.
  Complexity: M

- [ ] P1 — IzzyOnDroid submission
  Why: Fastest FOSS distribution path — developer-signed APKs accepted, daily update checker.
  Blocker: Requires manual GitHub issue submission at IzzyOnDroid/repo and Exodus Privacy scan confirmation.
  Evidence: IzzyOnDroid inclusion policy docs; existing Fastlane metadata.
  Complexity: S

- [ ] P2 — Accrescent submission
  Why: GrapheneOS-friendly distribution channel with key pinning and signed metadata.
  Blocker: Requires Accrescent publisher registration and external repository metadata review.
  Evidence: Accrescent publishing requirements; existing Fastlane metadata and GitHub release artifacts.
  Complexity: S

## Blocked on Permission Decision

- [ ] P2 — Active calendar-event blocking ("meeting mode")
  Why: SpamBlocker's most-requested feature. Block non-contacts during active calendar events.
  Blocker: Requires READ_CALENDAR permission which is not currently declared — increases permission surface and needs product decision on whether to add it.
  Evidence: SpamBlocker wiki templates; `CalendarContract.Events` API.
  Touches: new `CalendarEventChecker`, `AndroidManifest.xml` (READ_CALENDAR), Settings toggle, tests.
  Complexity: M

## Blocked on Lockfile Regeneration

- [ ] P2 — Refresh Activity/Lifecycle/Navigation dependencies as one tested tranche
  Blocker: Requires Gradle lockfile regeneration across all configurations. Best done in a dedicated session with full build validation.
  Complexity: M

## Blocked on Data Pipeline Coordination

- [ ] P2 — Threshold-based community reputation weighting
  Blocker: Requires coordinated schema changes to hot-list JSON, Python merge scripts, and app-side consumer code. Must maintain backward compatibility with existing v1.7.x clients.
  Complexity: M

## Blocked on External Registration

- [ ] P2 — Weblate translation setup
  Blocker: Requires registration at hosted.weblate.org (manual web action) and GitHub webhook configuration.
  Complexity: S

## Blocked on Future Platform Availability

- [ ] P3 — Rich Call Data display in caller-ID overlay
  Blocker: FCC RCD rules not yet finalized (expected 2027+). No carriers currently transmit RCD data. Forward-compat work only.
  Complexity: M

## Existing Roadmap Items with External Dependencies

Items from the main ROADMAP.md that are tracked there but blocked:

- Phase 3 API Server (3.1): Backend development requiring cloud hosting decisions
- Phase 4.1 KMP/iOS: Architecture-level cross-platform decision
- Phase 5.1 Carrier Integration: Business development engagement
- Phase 5.2 Federated Learning: Privacy research prerequisite
- Phase 5.4 Monetization: Product/business decision
- B.?.1 AI Answer Bot: Legal/ethical TCPA decision
- B.?.2 Audio CAPTCHA: UX pilot decision
- B.E.1 Enterprise/MDM: Market decision
- 1.7.8 Play Integrity: GMS-only, feature-flag decision for FOSS builds
