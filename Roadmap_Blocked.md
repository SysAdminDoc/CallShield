# CallShield — Blocked Roadmap Items

Items moved here from ROADMAP.md because they require external action, dedicated sessions, or have dependency gates that prevent autonomous implementation.

## Blocked on File-Hygiene Policy

- [ ] P3 — Reconcile the drifting version and count claims
  Why: the requested acceptance spans the tracked `data/README.md` plus ignored
  the project working notes, while the session's explicit hygiene rule permits commits only to
  code, `.gitignore`, `CHANGELOG.md`, and the root `README.md`.
  Blocker: completing and committing the verifier/documentation changes would
  violate that higher-priority file policy. The ignored stale `PROJECT_CONTEXT.md`
  was removed locally as requested, but the remaining documentation and verifier
  contract cannot be shipped without an explicit policy change.
  Complexity: S

## Blocked on Model Artifact and Evaluation Approval

- [ ] P3 — Evaluate a bundled LiteRT text classifier for SMS scam detection
  Why: the acceptance requires an actual bundled model and tokenizer, measured low-end latency/APK impact, and a licensed evaluation artifact with an agreed false-positive budget for every locale.
  Blocker: the repository contains only the synthetic CC0 evaluation fixtures and regex analyzer; it does not contain a licensed model/tokenizer or an approved per-locale false-positive budget. Choosing or training one would require external model licensing and human evaluation-policy input.

## Blocked on Dedicated Session (too large for autonomous batch)

- [ ] P1 — AGP 9 + Kotlin 2.4.20+ + Hilt 2.60.1 + Moshi→kotlinx.serialization migration tranche
  Why: AGP 8.x is on a deprecation path; Hilt 2.59+ drops AGP 8 support; core-ktx 1.19.0 and lifecycle 2.11.0 require AGP 9.1 and compileSdk 37; Moshi 1.x codegen requires KSP1 while KSP2 is now default; AGP 10 removes all escape hatches mid-2026. All four must move together. This tranche also carries the **CVE-2026-53914** fix — the AGP-8/Hilt-2.58 stack cannot read Kotlin 2.4 metadata, so the app remains on Kotlin 2.3.21 until the dedicated migration. Interim mitigation (no remote/shared Gradle build cache) is already documented in `gradle.properties`, so the vector is not reachable until then.
  Blocker: XL complexity — touches every build file, all lockfiles, all JSON parsing sites. Requires a dedicated session with build validation.
  Evidence: AGP 9 release notes; Dagger 2.59 release; Kotlin 2.3.0/2.3.20 changelogs; Moshi KSP2 compatibility issues; CVE-2026-53914 (GHSA-r937-wjx7-w2jp).
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

- [ ] P1 — Purge the historical Cloudflare account metadata blob
  Why: `worker/.wrangler/cache/wrangler-account.json` remains readable from public commit `98a2a5c` even though `.wrangler/` is now ignored.
  Blocker: Safe removal requires the operator to rotate and re-scope the Worker's `GITHUB_TOKEN`, then authorize a repository-wide `git-filter-repo` rewrite and force-push before verifying the historical path is gone from GitHub. The secret rotation cannot be completed from repository state.
  Evidence: commit `98a2a5c`; `.gitignore`; `worker/.wrangler/cache/wrangler-account.json` (history only).
  Complexity: S (operator action, destructive history rewrite)

- [ ] P2 — Provision atomic Cloudflare report-rate controls and redeploy the Worker
  Why: Source now fails closed without `RATE_LIMIT`, but `wrangler.toml` still has a placeholder namespace and eventual consistency makes KV read-modify-write non-atomic under concurrent requests. The deployed Worker also cannot receive the source fixes until its bindings and secrets are provisioned.
  Blocker: Requires the operator's Cloudflare account to provision a Workers rate-limiting binding or Durable Object, supply the real KV/account configuration, set `GITHUB_TOKEN` and `REPORTER_BUCKET_SECRET`, and deploy.
  Evidence: `worker/wrangler.toml`; `worker/community-reports-worker.js` (`validateReportEnvironment`, `checkRateLimit`, `checkDedup`).
  Complexity: M (operator account + infrastructure migration)

- [ ] P3 — Verify the deployed Cloudflare Worker against repository source
  Why: Source behavior is covered locally, but safe confirmation of the live POST, secret bindings, atomic limiter, and deployed revision belongs after the operator redeploy above.
  Blocker: Depends on the Cloudflare provisioning/redeploy item and operator access; probing state-changing abuse controls before that would create public report commits without proving the intended configuration.
  Evidence: `worker/community-reports-worker.js`; deployed `callshield-reports.workers.dev` endpoint.
  Complexity: S (operator verification)

- [ ] P3 — Validate STIR PASSporT/attestation exposure, else formally close 2.3.1–2.3.5
  Why: Android's current public documentation says carrier SIP headers are not shared directly with apps because they contain PII; a default caller-ID/spam app receives only the `getCallerNumberVerificationStatus()` verdict. The API 35 and API 37 emulators available in this session have no cellular carrier or STIR/SHAKEN call path, so they cannot satisfy the roadmap's shipping-device validation requirement.
  Blocker: Requires a physical, shipping Android 11+ device on a STIR-capable carrier plus an authenticated incoming call. Capture the `Call.Details` surface and extras there before formally rejecting 2.3.1–2.3.5.
  Evidence: https://developer.android.com/develop/connectivity/telecom/dialer-app/prevent-spoofing ; AOSP `CallScreeningService`; `data/StirShakenSemantics.kt`.
  Complexity: S

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

- [ ] P2 — wrangler.toml carries a placeholder KV namespace id; the pending worker security redeploy cannot run from repo state
  Why: worker/wrangler.toml:14 has `id = "REPLACE_WITH_KV_NAMESPACE_ID"`. `wrangler deploy` fails on the invalid binding — or, forced without it, deploys with rate-limiting/dedup silently disabled (checkRateLimit/checkDedup are permissive when env.RATE_LIMIT is unbound, worker:129/172). This operationally blocks the already-logged "deployed Worker is stale" redeploy. Live damage corroborated 2026-07-30: data/reports accepted +15551234567 twice (555-NPA, rejected by current repo worker code) and +12385233476 twice ~10 s apart.
  Blocker: Requires the operator's Cloudflare account (`wrangler kv namespace list` / dashboard) to recover the real namespace id; keep it in untracked config or an env-specific override, then run `wrangler deploy`.
  Evidence: worker/wrangler.toml:14; worker/community-reports-worker.js:129/172; data/reports/ 2026-07-30 files; git log community-report commits.
  Complexity: S (operator minutes)

## Blocked on Permission Decision

- [ ] P1 — Android 17 SMS-read strategy: OTP 3-hour read-delay resilience + capability detection
  Why: API 37 withholds OTP-bearing SMS for three hours unless the app is the default SMS handler or uses the User Consent/Retriever APIs.
  Blocker: Choosing default-SMS ownership versus a consent-based partial-content path is a product and permission-surface decision. It also overlaps the separately blocked SMS Screening Provider migration and must be settled before implementation can define honest degraded-mode behavior.
  Evidence: Android 17 and Android 16 behavior-change documentation; `service/SmsReceiver.kt`.
  Complexity: L

- [ ] P2 — Active calendar-event blocking ("meeting mode")
  Why: SpamBlocker's most-requested feature. Block non-contacts during active calendar events.
  Blocker: Requires READ_CALENDAR permission which is not currently declared — increases permission surface and needs product decision on whether to add it.
  Evidence: SpamBlocker wiki templates; `CalendarContract.Events` API.
  Touches: new `CalendarEventChecker`, `AndroidManifest.xml` (READ_CALENDAR), Settings toggle, tests.
  Complexity: M

## Blocked on Device Verification

- [ ] P3 — Narrow the ProGuard keeps to the actual reflective surface
  Why: `proguard-rules.pro` keeps `com.squareup.moshi.**`, all of `kotlin.reflect.jvm.internal.**`, and the entire `com.sysadmindoc.callshield.service.**` package (manifest components are kept by AGP automatically). That locks dead weight into the APK and disables shrinking across three trees.
  Blocker: The failure mode is silent and runtime-only — the file's own comments record a previous attempt where enumerating the Backup payload classes individually stripped Kotlin metadata, so Moshi either threw or wrote obfuscated keys that restore silently lost. `assembleRelease` succeeding proves nothing; this needs the minified APK installed on a device with a full backup/restore round-trip plus a screening smoke test (the already-logged release-build verification item).
  Evidence: `app/proguard-rules.pro:8,15,55`; the KotlinJsonAdapterFactory reflection comments in the same file.
  Complexity: M

- [ ] P3 — Ratchet the detekt complexity gates
  Why: `CyclomaticComplexMethod: 45` (default 15) and `LongMethod: 250` (default 60) are set where nothing can hit them, so new code inherits the exemption.
  Blocker: Measured this session — the codebase sits AT the ceiling, so ratcheting requires refactoring first, not just a config change. At CCM 30 / LongMethod 150 exactly six violations appear, all in code that must not be churned without on-device verification: `BlockReasoning.explain` (150 lines, complexity 44), `CallerIdOverlayService.showOverlay` (235 lines, complexity 36) and `runLiveLookups` (complexity 38), and `BackupRestore.toBackupSettings` (complexity 37). CCM 45 is already the tightest value that passes today. Refactor those four functions with device smoke tests, then tighten the thresholds in the same change.
  Evidence: `app/config/detekt/detekt.yml:8-20`; trial run at CCM 30 / LongMethod 150 on 2026-07-30.
  Complexity: L

## Blocked on Lockfile Regeneration

- [ ] P2 — Refresh Activity/Lifecycle/Navigation dependencies as one tested tranche
  Blocker: Requires Gradle lockfile regeneration across all configurations. Best done in a dedicated session with full build validation.
  Complexity: M

## Blocked on Data Pipeline Coordination

- [ ] P2 — Threshold-based community reputation weighting
  Blocker: Requires coordinated schema changes to hot-list JSON, Python merge scripts, and app-side consumer code. Must maintain backward compatibility with existing v1.7.x clients.
  Complexity: M

## Blocked on External Registration

- [ ] P1 — Google Developer Verification readiness
  Blocker: Registration requires an operator identity/business decision, Google account action, fee, and release-key registration. The project rule prohibiting software signing also prevents autonomous creation or validation of a stable release signing key.
  Evidence: Google Developer Verification rollout requirements; F-Droid's published objection.
  Complexity: M

- [ ] P2 — Weblate translation setup
  Blocker: Requires registration at hosted.weblate.org (manual web action) and GitHub webhook configuration.
  Complexity: S

- [ ] P2 — Define optional authenticated reputation adapters with strict key isolation
  Why: Hiya, Nomorobo, Tellows, First Orion, TNS, IPQS, Twilio, Call Control, and GSE expose useful risk/identity signals but require contracts, keys, quotas, and privacy review.
  Blocker: Provider contracts, credentials, quota terms, retention/region policies, and the operator's consent decision are external inputs. No adapter may be enabled or validated from repository state alone.
  Evidence: official developer portals and API documentation listed in `RESEARCH.md`.
  Complexity: L

## Blocked on Future Platform Availability

- [ ] P3 — Rich Call Data display in caller-ID overlay
  Blocker: FCC RCD rules not yet finalized (expected 2027+). No carriers currently transmit RCD data. Forward-compat work only.
  Complexity: M

## P0 — Stable release signing key

The release-signing gate cannot be finalized until the operator confirms which
keystore is canonical. The repository contains a local `callshield-release.jks`
and F-Droid metadata pins a signing fingerprint, but the available evidence
does not prove that the keystore and fingerprint match the installed release.
Choosing a key affects upgrade continuity and is an operator-owned release
decision. Once confirmed, restore the item to ROADMAP.md and wire the signing
policy into the release build.

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

## Roadmap cleanup — 2026-08-10 — ROADMAP.md

**Blocked on:** The source roadmap marked this work as parked, optional, or dependent on external input.

Blocked items moved from the actionable roadmap:

| ID | Item | Source | Decision Needed |
|----|------|--------|-----------------|
| B.?.1 | **AI Answer Bot** that engages spam callers to waste their time | [9 RoboKiller] | Legal/ethical: TCPA + state recording-consent laws; harassment exposure. Lean reject for now |
| B.?.2 | **Audio CAPTCHA screening** ("press 1 to connect") | [10 YouMail] | UX cost vs spam-reduction; pilot opt-in for unknown callers |
| B.?.3 | **Visual voicemail with spam-priority sorting** | [10 YouMail] | Scope creep — replaces dialer/voicemail. Keep in mind for a separate sister app |
| B.?.4 | **Auto-attendant / IVR for first-time callers** | [10 YouMail] | Same scope concern as B.?.3 |
| B.?.5 | **Truecaller-style B2B verified-caller display** | [29 Truecaller] | Requires partnerships; conflicts with on-device-first |
