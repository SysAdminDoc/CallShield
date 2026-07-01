# Research - CallShield

## Executive Summary

CallShield is a privacy-first Android call/SMS/RCS spam blocker with a strong current architecture: Kotlin/Compose, Hilt, Room, WorkManager, pinned OkHttp clients, on-device scoring, a priority-sorted checker pipeline, RCS notification filtering, URLhaus checks, community reporting, reproducible-build groundwork, and local verification. The highest-value direction is not feature sprawl; it is tightening trust boundaries around the existing pipeline: finish UI phone-number spoofing fixes, cap and type remote enrichment failures, keep STIR/SHAKEN from being over-presented as caller safety, prove permission-degraded behavior, make future list subscriptions reversible, and preserve Android 16/17 compatibility as platform SMS and notification behavior changes.

Top opportunities, in priority order:
- P0: Finish ASCII-only phone-number handling in all UI entry/review paths.
- P1: Bound every enrichment response body and return typed failures instead of silent null/UNKNOWN states.
- P1: Make STIR/SHAKEN/PASSporT UI language risk-neutral; A-level attestation is not proof that a caller is safe.
- P1: Add answered-caller, emergency-callback, and SMS-burst context rules through the checker pipeline.
- P1: Add a per-permission/role degraded-mode matrix for dashboard, onboarding, settings, diagnostics, call screening, SMS, RCS, and overlay flows.
- P1: Add source-health and privacy diagnostics for SkipCalls, PhoneBlock, WhoCalledMe, OpenCNAM, AbstractAPI, URLhaus, GitHub data sync, and community reports.
- P1: Add feed safety rails before external blocklist subscriptions: caps, preview, attribution, disable, and rollback.
- P2: Expand Android 16/17 smoke tests for edge-to-edge, predictive back, notification, SMS OTP delay, and target-SDK branching.
- P2: Add selective backup/export/restore sections so users can move rules without importing unrelated logs or settings.

## Product Map

- Core workflows: screen incoming calls under the `CallScreeningService` deadline; block/silence using local rules, Android system block list, STIR/SHAKEN, heuristics, campaign detection, and ML; filter SMS/RCS; inspect logs and reasons; sync public spam data; submit community reports.
- User personas: privacy-focused Android and F-Droid users, power users who want explainable local rules, and non-experts who need safe defaults plus direct recovery actions.
- Platforms and distribution: Android minSdk 29 / targetSdk 36; GitHub Releases and direct APK install; Fastlane/F-Droid metadata and reproducible-build docs exist; F-Droid, IzzyOnDroid, and Accrescent publication remain externally blocked.
- Key integrations and data flows: GitHub raw feeds to WorkManager/Room; Cloudflare Worker report submission; URLhaus SMS URL checks; optional AbstractAPI no-backup key; SkipCalls, PhoneBlock, WhoCalledMe, and OpenCNAM overlay enrichment; local backup/restore and CSV export.

## Competitive Landscape

- SpamBlocker: The closest OSS benchmark. It does optional-permission call/SMS blocking, SMS screening provider mode, rule priorities, answered/emergency context, API presets, conflict diagnostics, and recent large-response crash fixes well. CallShield should borrow the context-rule discipline and diagnostics, but avoid arbitrary workflow scripting in screening paths.
- Fossify Phone and Silence: Good references for privacy-first call UX, multi-SIM ergonomics, contact-group rules, answered-call trust, and no-tracking positioning. CallShield should learn role/permission clarity without becoming a full dialer.
- YetAnotherCallBlocker, Carrion, NoPhoneSpam, and F-Droid Call Blocker: Useful simple blocking references for crowdsourced databases, STIR/SHAKEN enforcement, prefix blocks, history, dual-SIM, backup/restore, and no-internet modes. Avoid unmaintained Java-era broadcast assumptions and under-tested migration paths.
- Google Phone/Messages: Moving trust UX toward contact verification, on-device scam warnings, and RCS/Messages-integrated signals. CallShield should learn cautious confidence language and metadata fallback, but should not promise privileges only the system dialer or Messages app can provide.
- Hiya, RoboKiller, YouMail, and carrier blockers: Commercial products sell caller identity, risk routing to voicemail, spam text protection, visual voicemail, assistants, synthetic-voice warnings, and business identity. CallShield should borrow risk routing, recovery, and source-health UX, while rejecting accounts, contact upload, ads, cloud audio, and voicemail replacement.
- Pi-hole and similar blocklist systems: The useful analogy is not a plugin marketplace; it is subscribed-list safety: fetch caps, source attribution, dry-run diffs, rebuild/rollback, and per-source disable before user-supplied feeds affect live decisions.

## Security, Privacy, and Reliability

- Verified: `app/src/main/java/com/sysadmindoc/callshield/ui/screens/lookup/LookupScreen.kt`, `BlocklistScreen.kt`, `BlockedLogScreen.kt`, and `RecentCallsScreen.kt` still use `Char.isDigit()` or Unicode-aware digit filters in UI-created actions. This can diverge from the ASCII-only checker/data path.
- Verified: `ExternalLookup.kt`, `WebLookup.kt`, and `NumberTypeChecker.kt` use `ResponseBody.string()` and broad `catch (_: Exception)` fallbacks. Existing roadmap covers byte caps, but the app also needs typed result states so unavailable sources are not presented as clean results.
- Verified: `settings_stir_trusted_allow_desc` warns about over-attestation, but `ROADMAP.md` item 2.3.2 still says A-level should display as green. TNS 2026 and FCC rulemaking both show A-level attestation alone is not enough consumer identity evidence.
- Verified: `AndroidManifest.xml` declares `READ_CALL_LOG`, `READ_CONTACTS`, `READ_SMS`, `RECEIVE_SMS`, `SYSTEM_ALERT_WINDOW`, notification access service, and call-screening service entrypoints. Dashboard and Protection Test check several readiness states, but there is no explicit per-permission degraded-mode contract covering every revoked permission/role.
- Verified: `CommunityContributor.kt` and `worker/community-reports-worker.js` still have a raw-number report pipeline; the hash-report migration is correctly parked in `Roadmap_Blocked.md` because it requires client, Worker, merge-script, and existing-report migration coordination.
- Verified: Android 17 delays standard OTP SMS visibility for most apps, while Android 16 target-SDK behavior affects edge-to-edge, predictive back, and text rendering. Existing `TargetSdkBehaviorSmokeTest.kt` should grow into a standing compatibility matrix.
- Verified: `BackupRestore.kt` now validates backup payloads and supports merge/replace preview, but exports/restores the app's backup sections as one combined payload rather than user-selected sections.

## Architecture Assessment

- The checker pipeline remains the right boundary for new trust rules. Add answered-caller, emergency-callback, and SMS-burst behavior as priority slots rather than branching in services.
- Remote enrichment should return typed source results: success, clean, not-found, timeout, rate-limited, oversized, parse-error, disabled, and unavailable. That gives overlay, diagnostics, and export code the same truth without leaking raw numbers into logs.
- Subscription work should land behind a dedicated repository/model boundary before B.F.7/B.F.13. Do not merge user-supplied lists directly into the same path as trusted GitHub hot feeds without source attribution and rollback.
- PASSporT parsing and attestation scoring should update `BlockReasoning.kt`, overlay UI, tests, and settings copy together. The UI should say what was cryptographically attested, not that the caller is safe.
- Permission and role readiness belong in one shared model consumed by dashboard, onboarding, settings, Protection Test, and instrumentation tests. `CallShieldPermissions.kt` is already the right starting point.
- Existing roadmap coverage is already strong for i18n/l10n, accessibility, distribution, offline resilience, testing, and future platform ideas. New roadmap work should therefore favor trust, observability, compatibility, and reversible data changes.

## Rejected Ideas

- Full arbitrary workflow scripting from SpamBlocker: too much execution and maintenance risk for a 5-second local screening path.
- Treating A-level STIR/SHAKEN as a green safe badge: rejected because TNS and FCC sources show attestation does not prove caller identity or legality.
- Moving SMS screening provider mode back into the main roadmap now: it is correctly blocked because it needs a substantial provider implementation and SMS-app compatibility research.
- Required Play Integrity, GMS-only checks, or account-backed reputation: incompatible with F-Droid/de-Googled users and the no-accounts philosophy.
- Contact upload, address-book graph matching, or always-on cloud reputation: incompatible with on-device-first privacy.
- Cloud audio, assistant bots, voicemail replacement, or automatic call transcription: incompatible with the no-cloud-audio direction and too large for the current Android call-screening advantage.
- KMP/iOS before telecom trust work: CallShield's strongest current value is Android-native call/SMS/RCS integration; portability should wait behind Room/AGP migration and trust hardening.
- Plugin marketplace before bounded subscriptions and diagnostics: executable third-party logic is the wrong first extension point; reversible data feeds are safer.

## Sources

Direct OSS competitors:
- https://github.com/aj3423/SpamBlocker
- https://github.com/aj3423/SpamBlocker/releases
- https://github.com/aj3423/SpamBlocker/wiki/SMS-Screening-protocol
- https://f-droid.org/packages/spam.blocker/
- https://github.com/adamff-dev/spam-call-blocker-app
- https://github.com/FossifyOrg/Phone
- https://gitlab.com/xynngh/YetAnotherCallBlocker
- https://f-droid.org/packages/com.callblocker/
- https://github.com/x13a/Silence

Commercial, carrier, and platform:
- https://blog.google/security/android-fake-call-detection/
- https://www.hiya.com/
- https://robokiller.com/
- https://www.youmail.com/features/
- https://www.t-mobile.com/benefits/scam-shield
- https://www.att.com/security/active-armor/
- https://www.verizon.com/solutions-and-services/add-ons/protection-and-security/call-filter/

Standards and platform APIs:
- https://developer.android.com/reference/android/telecom/CallScreeningService.CallResponse.Builder
- https://developer.android.com/about/versions/16/behavior-changes-16
- https://developer.android.com/about/versions/17/behavior-changes-17
- https://www.rfc-editor.org/rfc/rfc8225.html
- https://datatracker.ietf.org/doc/html/rfc8588
- https://tnsi.com/resource/com/tns-2026-robocall-report-going-further-than-stir-shaken-blog/
- https://www.federalregister.gov/documents/2025/12/05/2025-22063/advanced-methods-to-target-and-eliminate-robocalls
- https://www.fcc.gov/consumers/guides/stop-unwanted-robocalls-and-texts

Dependencies, security, and adjacent systems:
- https://developer.android.com/build/releases/agp-9-0-0-release-notes
- https://android-developers.googleblog.com/2026/03/room-30-modernizing-room.html
- https://github.com/google/dagger/releases
- https://square.github.io/okhttp/changelogs/changelog/
- https://nvd.nist.gov/vuln/detail/cve-2024-7254
- https://docs.pi-hole.net/main/pihole-command/

## Open Questions

None that block prioritization. External publication, hash-report migration, SMS screening provider mode, and AGP/Room/Hilt migration need dedicated implementation sessions, not more research.
