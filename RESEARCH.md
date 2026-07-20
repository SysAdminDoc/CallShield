# Research — CallShield
Date: 2026-07-20 — replaces all prior research. Anchored to v1.7.13 (versionCode 41).

## Executive Summary

CallShield is a privacy-first, on-device Android call/SMS/RCS spam blocker with an unusually mature codebase: a priority-sorted `IChecker` pipeline (~29 layers), a pure-Kotlin gradient-boosted-tree scorer, Hilt DI, Room 2.8.4, WorkManager, pinned OkHttp 5, reproducible-build groundwork, and broad JVM + instrumented tests. The prior research pass (which flagged UI digit sanitization, remote body caps, SMS-body redaction, URLhaus privacy mode, per-permission degraded-mode matrix, selective backup, external-blocklist subscription, and temporary allow/block windows) is now **fully shipped** — verified in code — so the trust-hardening backlog it drove is essentially closed. The highest-value direction has shifted from "close local trust gaps" to **surviving imminent platform changes and reaching OTT/RCS coverage that Google is closing off to third parties.** Top opportunities in priority order: (P1) decide and implement an Android 17 SMS-read strategy before the OTP 3-hour read-delay lands; (P2) make blocked-event notifications survive Android 16 forced-grouping/cooldown; (P2) extend notification-layer screening beyond Google/Samsung Messages to Signal/WhatsApp/OTT (SpamBlocker Extended's differentiator); (P2) rule-priority conflict detection; (P2) region/CNAP-based rules; (P2) CallScreeningService bind-lifecycle/5s-deadline instrumentation; and a cluster of P3 correctness items (frequency decay, quiet-hours timezone, model-sync observability). The dependency tree is clean of reachable CVEs (see below — CVE-2024-7254 was investigated and found not reachable).

## Product Map

- **Core workflows:** screen incoming calls under the `CallScreeningService` 5s deadline via the priority-sorted checker ladder (whitelist → system block list → STIR/SHAKEN → DB/prefix/wildcard/range → context allows → quiet hours → frequency → heuristics → campaign burst → ML); filter SMS and RCS (Google/Samsung Messages notifications); inspect logs with per-decision reasoning; sync public spam data; submit anonymous community reports; subscribe to external CSV/TXT/BIND blocklists.
- **User personas:** privacy-focused Android / F-Droid / de-Googled users; power users who want explainable local rules and per-rule schedules; non-experts who need safe defaults plus one-tap recovery actions.
- **Platforms & distribution:** Android minSdk 29 / targetSdk 36; GitHub Releases + Obtainium today; F-Droid, IzzyOnDroid, Accrescent publication tracked but externally blocked. No GMS-only paths.
- **Key integrations & data flows:** GitHub raw feeds → WorkManager → Room; Cloudflare Worker for anonymous reports; URLhaus for SMS URL safety (query-stripped); optional AbstractAPI no-backup key; SkipCalls/PhoneBlock/WhoCalledMe/OpenCNAM overlay enrichment; local selective backup/restore + CSV export.

## Competitive Landscape

- **SpamBlocker (aj3423)** — the OSS benchmark, moving fast in 2026: SMS Screening Service + open protocol (v5.9), SMS Reply auto-response (v5.12), 7726 report preset (v5.13), rule-priority **conflict detection** (v5.10), geo/region + "Local Number" regex and **CNAP** name-trust modes (v5.1/5.2), multi-SIM (v5.0), caller-ID floating overlay (v5.7), call/SMS throttling + min-interval. **Learn:** conflict-detection UX, region/CNAP rules, throttling. **Avoid:** arbitrary workflow scripting inside the 5s screening path.
- **SpamBlocker Extended (`dev.kerballone.spamblocker`, F-Droid 2026-07-14)** — the single most novel FOSS capability found: **Notification Screening** applies block rules to RCS, Signal, WhatsApp, email, and *any* app that posts a notification, with lightweight chime/vibrate/flashlight alerts. **Learn:** notification-layer OTT coverage is the practical answer to E2EE RCS. **Avoid:** over-broad notification reading without per-source opt-in and clear privacy disclosure.
- **Fossify Phone/Messages** — actively maintained but detection is basic number/pattern blocking only (no new spam features in 2026). CallShield already exceeds it; learn nothing on detection, watch its UX polish.
- **YetAnotherCallBlocker** — effectively abandoned (last release 2021, last commit 2024). Its crowd-DB is stale; a gap CallShield can fill.
- **Google Phone/Messages on-device detection** — Messages Scam Detection (Gemini Nano), in-call financial-app scam protection, RCS "Fake Call Detection" handshake, Key Verifier. **Most of these are platform-internal with no third-party API.** CallShield cannot call them; the durable third-party foothold remains `CallScreeningService` + notification-listener + on-device lists/patterns.
- **Hiya / Truecaller / RoboKiller / YouMail / Nomorobo** — 2025-26 differentiator is **real-time on-device AI/synthetic-voice ("deepfake") detection**, now paywalled by Hiya and Truecaller. **Learn:** attestation as a first-class scoring signal (Hiya powers AT&T with it). **Reject:** accounts, contact upload, cloud audio, ads, voicemail replacement.
- **Pi-hole / URLhaus (adjacent)** — the safe-ingestion pattern (attribution, fetch caps, dry-run diff, per-source disable, privacy-aware submission) is already adopted via the external-blocklist subscription feature; keep it as the model for any future feed.

## Security, Privacy, and Reliability

- **Verified NOT reachable — CVE-2024-7254 (protobuf DoS):** investigated on the lockfile. The only protobuf on the release runtime classpath is AndroidX's **repackaged** `androidx.datastore:datastore-preferences-external-protobuf:1.2.1` (relocated package, not `com.google.protobuf`); all `com.google.protobuf:*` artifacts are scoped to `_internal-unified-test-platform-*` (AGP test infra), not shipped. Forcing `protobuf-javalite ≥ 3.25.5` would be a no-op on the APK. Additionally, Preferences DataStore parses only the app's own trusted local settings file — never attacker-controlled proto — so the deeply-nested-message DoS vector is not attacker-reachable. No action; OkHttp 5.3.2, Moshi 1.15.1, Room 2.8.4, WorkManager 2.11.2 are also clean at pinned versions. (Cross-ref: roadmap B.U.9 already covers Glance ≥1.1.1 if/when the RemoteViews widget migrates to Glance.)
- **Verified (prior gaps now CLOSED):** UI ASCII-only digit sanitization (`PhoneDigits.kt`), bounded HTTP bodies (`BoundedResponseBody.kt` — JSON 64KB / HTML 128KB / CNAM 16KB), SMS-body redaction (`SmsBodyRedactor.kt`), URLhaus query-stripping (`UrlSafetyChecker.normalizeRemoteLookupUrl`), per-permission degraded-mode matrix (`CallShieldPermissions.PermissionCapabilityContract`), selective backup (`BackupRestore.BackupSection`), external-blocklist subscription (`ExternalBlocklistSubscription.kt`/`ExternalBlocklistParser.kt`), and temporary allow/block windows (`SpamNumber.expiresAt` + `TemporaryAllowChecker`). No further work needed on these.
- **Platform risk (NEW):** Android 16 (API 36, your target) **forces notification grouping and applies cooldown-muting** to rapid same-app notifications. CallShield posts per-event blocked-call/SMS notifications plus a digest; later events can be silently bundled/muted. Needs an explicit group key + summary strategy or a single updating notification.
- **Platform risk (NEW, architectural):** Android 17 (API 37) withholds **OTP-containing SMS for 3 hours** from apps that read SMS directly, unless the app is the default SMS app / assistant / companion or uses SMS Retriever / User Consent. A scam-SMS classifier that reads `SMS_RECEIVED` is functionally crippled for OTP-bearing texts on API 37. Also on Android 16, **cross-process ordered-broadcast priority is no longer honored** — `SmsReceiver` priority 999 no longer guarantees CallShield sees SMS before other apps.
- **Correctness (NEW, from code recon):** frequency-escalation counter has no time decay/pruning (early-window calls weigh equally and are never pruned); quiet-hours uses raw device time with no timezone/DST handling; ML model-weight sync parses trees with regex and silently falls back to LR on malformed JSON with no health signal/logging; contacts-only mode degrades silently if `READ_CONTACTS` is revoked at runtime after enablement.
- **Test gap (NEW):** no instrumented test binds the real `CallShieldScreeningService` and asserts a verdict within the 5s deadline; `onScreenCall → classify → respondToCall` cold-start latency is unmeasured on-device.

## Architecture Assessment

- The `IChecker` pipeline remains the correct extension point; new region/CNAP and rule-conflict logic should be checkers or checker-metadata, not service branches.
- **Notification-listener layer is CallShield's real moat for OTT/RCS.** `RcsNotificationListener` is hardcoded to `com.google.android.apps.messaging` and `com.samsung.android.messaging`. Generalize to a per-source, opt-in `NotificationScreeningService` taxonomy (Signal, WhatsApp, generic) reusing `SmsContentAnalyzer`. This is the highest-leverage new surface because E2EE RCS + platform-internal detection close every other content path.
- SMS architecture must be decided around API 37: either pursue the default-SMS-app role (large, product-gated — SMS Screening Provider mode is already tracked in `Roadmap_Blocked.md`) or adopt SMS User Consent/Retriever for OTP paths and clearly mark degraded mode. Do not defer the *decision*.
- Model-sync should expose a typed parse/health state (mirroring the enrichment-source diagnostics already shipped) instead of a silent regex fallback. Full move off regex parsing belongs to the Moshi→kotlinx.serialization tranche in `Roadmap_Blocked.md`.
- Baseline **and startup** profiles for the screening path protect the 5s deadline; roadmap 2.6.4 already covers Baseline Profile — add DEX-layout startup profile to it.
- Categories consciously covered: security, privacy, reliability, accessibility, i18n/l10n, observability, testing, docs, distribution, offline resilience, mobile/platform compatibility, migration, upgrade strategy. Multi-user/enterprise and executable plugins remain later/rejected tracks.

## Rejected Ideas

- **Cloud/off-device AI voice-deepfake detection** — breaks no-cloud-audio; only the on-device variant (roadmap B.M.4, under-consideration) is admissible, and only if a usable lightweight open model lands. (Source: Hiya/Truecaller 2026 paywalled features.)
- **Consuming Google's RCS Fake-Call handshake or in-call financial-scam protection** — platform-internal, no third-party API published. (Source: blog.google security posts, 2026.)
- **Becoming the default SMS app purely to keep priority-999 interception** — heavy UX/role cost; only justified if the API 37 OTP strategy independently requires the role. (Source: Android 16/17 behavior-change docs.)
- **ML Kit GenAI / Gemini Nano as the baseline SMS classifier** — AICore-gated to flagships, cold-start latency unfit for the 5s call path; admissible only as a capability-gated *second opinion* for async SMS triage. (Source: developer.android.com/ai/gemini-nano.)
- **Paid/monetary blocklist feeds, contact upload, accounts, ads** — unchanged philosophy conflicts.
- **AGP 9 / Room 3.0 / Compose 1.12 adoption this pass** — travels as one large tranche (already in `Roadmap_Blocked.md`); note that Compose 1.12 forces compileSdk 37 and apksigcopier breaks on Build Tools ≥35, colliding with F-Droid reproducibility. Not started here.

## Sources

OSS competitors:
- https://github.com/aj3423/SpamBlocker/releases
- https://github.com/aj3423/SpamBlocker/issues
- https://github.com/aj3423/SpamBlocker/issues/604
- https://github.com/aj3423/SpamBlocker/issues/596
- https://github.com/aj3423/SpamBlocker/issues/634
- https://f-droid.org/packages/dev.kerballone.spamblocker/
- https://github.com/FossifyOrg/Phone/releases
- https://gitlab.com/xynngh/YetAnotherCallBlocker

Commercial / community signal:
- https://www.hiya.com/
- https://developer.hiya.com/
- https://blog.hiya.com/emerging-text-based-threats-micro-scams-and-the-dawn-of-rcs-scams
- https://truecallerapk.cc/truecaller-premium-features/
- https://mobileecosystemforum.com/2026/04/23/inside-rcs-threats-how-ai-and-rich-content-fuel-fraud-patterns-and-what-we-can-do-about-it/

Google / Android platform:
- https://blog.google/security/android-fake-call-detection/
- https://blog.google/security/new-ai-powered-scam-detection-features/
- https://blog.google/security/android-expands-pilot-in-call-scam-protection-financial-apps/
- https://9to5google.com/2025/10/15/google-messages-key-verifier-launch/
- https://developer.android.com/about/versions/16/behavior-changes-16
- https://developer.android.com/about/versions/16/behavior-changes-all
- https://developer.android.com/about/versions/17/behavior-changes-all
- https://developer.android.com/google/play/requirements/target-sdk
- https://developer.android.com/reference/android/telecom/CallScreeningService

Regulatory / standards:
- https://www.mintz.com/insights-center/viewpoints/2776/2025-11-20-fcc-proposes-new-rules-call-branding-and-caller-id
- https://docs.fcc.gov/public/attachments/DOC-415059A1.pdf
- https://www.federalregister.gov/documents/2025/12/05/2025-22063/advanced-methods-to-target-and-eliminate-robocalls
- https://www.fcc.gov/robocall-mitigation-database

Dependencies / security / tooling:
- https://nvd.nist.gov/vuln/detail/cve-2024-7254
- https://github.com/advisories/GHSA-735f-pc8j-v9w8
- https://developer.android.com/build/releases/agp-9-0-0-release-notes
- https://android-developers.googleblog.com/2026/03/room-30-modernizing-room.html
- https://developer.android.com/topic/performance/baselineprofiles/overview
- https://developer.android.com/ai/gemini-nano
- https://github.com/obfusk/apksigcopier
- https://izzyondroid.org/about/security/ReproducibleBuilds/

## Open Questions

- **Default-SMS-app role vs. SMS User Consent/Retriever for API 37 OTP access** — this is a product-direction decision that gates the entire SMS-scanning feature set; it needs a user/product ruling, not more research. (Tracked as SMS Screening Provider mode in `Roadmap_Blocked.md`.)
- Everything else blocking prioritization (external-store publication, AGP9/Room3 tranche, community-report hashing, meeting mode) already has concrete blockers recorded in `Roadmap_Blocked.md`.
