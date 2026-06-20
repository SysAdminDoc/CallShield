# Research — CallShield

## Executive Summary

CallShield is a privacy-first Android spam call/SMS/RCS blocker with a mature 15-layer detection pipeline, on-device GBT ML scorer, STIR/SHAKEN integration, community reporting via Cloudflare Worker, and 620 JVM tests. The codebase is architecturally sound — Hilt DI, clean Room migrations, single-snapshot DataStore reads on the 5-second screening path, and comprehensive CI gates.

The highest-value direction is **trust hardening and platform compliance** before feature expansion: (1) AGP 9 + Kotlin 2.3 + Hilt 2.59 migration tranche before AGP 10 removes escape hatches; (2) RCS E2EE graceful degradation before MLS rolls out universally; (3) SMS Screening Provider mode to reduce permission surface; (4) hashed community reports for privacy; (5) Compose Testing v2 migration before next BOM bump; (6) IzzyOnDroid submission (fastest FOSS distribution path); (7) contacts-only blocking mode and active calendar-event blocking for high-demand user features.

Top 10 priorities (ordered):
1. AGP 9 + Kotlin 2.3 + Hilt 2.59 + Moshi→kotlinx.serialization migration
2. RCS E2EE graceful degradation in `RcsNotificationListener`
3. SMS Screening Provider mode (permission reduction)
4. Hashed community report submissions (privacy)
5. Compose Testing v2 migration
6. IzzyOnDroid submission
7. Contacts-only blocking mode
8. Active calendar-event blocking ("meeting mode")
9. STIR attestation trust documentation (13% over-attestation)
10. Android 17 SMS OTP delay compliance audit

## Product Map

- **Core workflows**: Screen incoming calls within Android's 5-second `CallScreeningService` deadline; block/silence by local rules, STIR status, hot lists, ML/heuristics; filter SMS/RCS spam; manage blocklists, trusted sources, backups, logs, and community reports.
- **User personas**: Privacy-focused Android users (GrapheneOS/F-Droid/de-Googled), sysadmin/power users wanting explainable rules, and non-expert users needing safe defaults with minimal setup.
- **Platforms**: Android minSdk 29 / targetSdk 36; GitHub Releases (Obtainium-compatible); Fastlane metadata exists; F-Droid/IzzyOnDroid/Accrescent remain distribution targets.
- **Key data flows**: GitHub raw hot feeds → WorkManager sync → Room; Cloudflare Worker community reports; URLhaus domain extraction; optional AbstractAPI enrichment in no-backup DataStore; CallerIdOverlayService multi-source lookup race.

## Competitive Landscape

**SpamBlocker (aj3423, 1.6K stars, MIT, v5.11)**: The only credible active FOSS competitor. Differentiators: user-scriptable workflow engine with HTTP API templates (Groq LLM SMS classification, Tellows, PhoneBlock), SMS Screening Provider mode (v5.9, no SMS permission needed), DB prefix auto-expansion, active calendar-event blocking, rule conflict detection indicator. Learn: workflow extensibility, SMS screening provider architecture, calendar integration. Avoid: breaking DB migrations (v4.0 cleared history), no API response size guard (v5.11 crash), Java/Kotlin mix.

**PhoneBlock (phoneblock.net, 315 stars, German-focused)**: Community model with hashed number submissions (SHA-256 of E.164, server never sees raw numbers), threshold-based blocking (user-configurable report count before auto-block), FRITZ!Box CardDAV sync. Learn: hashed privacy model for community reports, threshold-based reputation. Avoid: over-specialization for one region/platform.

**Fossify Phone (1.2K stars, Kotlin)**: Full dialer with basic `BlockedNumberContract` blocking. No spam detection, no ML. Useful reference for multi-SIM handling code.

**Google Phone (Pixel/Samsung)**: On-device Gemini Nano scam detection during calls, RCS-based fake call verification (June 2026), call reason/urgent flag display. These require controlling the dialer + messaging layer simultaneously — not replicable by third-party apps. The RCS verification concept could be adapted for a lightweight FOSS push-based contact verification if both parties use CallShield.

**Truecaller / Hiya / RoboKiller / YouMail**: Commercial products monetize AI voice deepfake detection (Hiya acquired Loccus.ai), answer bots (RoboKiller), visual voicemail, family protection (Truecaller remote hangup), and branded caller ID. Learn: confidence-based UX, recovery flows, business labeling as a paid-value surface. Avoid: contact upload, accounts, cloud audio, voicemail replacement, and partnership-only features.

**Carrier blocking (T-Mobile Scam Shield, AT&T ActiveArmor, Verizon Call Filter)**: Operate at network layer upstream of `CallScreeningService`. Verizon's "Neighborhood Filter" (silence calls from specific area codes) is a user-configurable concept replicable locally. Key insight: calls reaching CallShield have already passed carrier filtering, so they skew toward more evasive patterns.

**Abandoned FOSS (YetAnotherCallBlocker, Tranquille, Carrion, NoPhoneSpam)**: All dead or archived. Pattern: single maintainer, Java codebase, no detection differentiation. Carrion's three-tier confidence DB packaging (archive/high/full) is the right model for CallShield's planned bloom filter work.

## Security, Privacy, and Reliability

- **STIR A-level over-attestation** (Verified, TNS 2026): 13% of invalid-number calls receive A-level attestation via SIM box fraud. `StirShakenTrustChecker`'s trusted-allow path (default ON) carries false-negative risk. Attestation should be an additive ML feature input, not a hard allow gate. Source: TNS 2026 Robocall Report.
- **RCS E2EE dead end** (Verified, GSMA UP 3.0/4.0): MLS protocol encryption will make `RcsNotificationListener` content-based detection impossible once E2EE rolls out universally. Sender-metadata-only analysis (number, frequency, STIR) will be the surviving signal. Source: GSMA Universal Profile 3.0/4.0 specs.
- **Community report privacy gap** (Verified, code): `CommunityContributor.kt` transmits raw E.164 numbers to the Cloudflare Worker, which writes them to GitHub. The repo functions as a public phone number directory. PhoneBlock's HMAC-SHA256 hashed submission model prevents this. Source: PhoneBlock architecture, `CommunityContributor.kt`.
- **WildcardRule.kt:124** (Verified, code): `numberVariants()` uses `number.filter { it.isDigit() }` — the last remaining security-relevant `isDigit()` call in a matching path. All other data/service paths now use `filterAsciiDigits`. Source: grep of `app/src/main/java`.
- **Android 17 OTP SMS delay** (Verified, Android docs): `SMS_RECEIVED_ACTION` broadcasts are withheld for 3 hours on OTP-containing messages. CallShield's `SmsReceiver` listens on this action. Non-OTP spam SMS is unaffected. Audit needed for any OTP-adjacent filtering logic.
- **USE_FULL_SCREEN_INTENT** (Verified, API 36 docs): Apps targeting API 36 must declare this for full-screen notifications. `NotificationHelper` blocked-call notifications may be affected.
- **Compose Testing v1 deprecated** (Verified, BOM 2026.04.01): CallShield's 11 instrumented UI tests use v1 APIs. Must migrate to v2 before next BOM bump.
- **Moshi KSP2 compatibility** (Verified, community): Moshi 1.x codegen requires KSP1. KSP2 is now the default. The AGP 9 migration tranche must also swap Moshi for `kotlinx.serialization`.

## Architecture Assessment

- **AGP 9 migration gate**: AGP 8.10.1 → 9.x is the single largest dependency migration. It requires Hilt 2.59+ (drops AGP 8 support), Kotlin 2.3.x, and should bundle Moshi→kotlinx.serialization. R8 `-repackageclasses` default change must be validated against Room/Hilt/KSP codegen. AGP 10 removes all escape hatches mid-2026. Source: AGP 9 release notes, Dagger 2.59 release.
- **SMS Screening Provider**: SpamBlocker v5.9 implemented Android's SMS screening `ContentProvider` protocol, eliminating the need for `RECEIVE_SMS` permission. CallShield's `SmsReceiver` approach requires this permission. The screening-provider architecture is cleaner and more privacy-preserving. Source: SpamBlocker v5.9 release, Android SMS screening API.
- **RCS degradation path**: When `RcsNotificationListener` receives notifications with opaque/empty bodies (E2EE), fall back to sender-metadata-only analysis (number heuristics, STIR status, frequency patterns). No new architecture needed — just handle the `null`/empty body case gracefully.
- **Three-tier DB packaging**: For the bloom filter work (roadmap 3.3.1), ship a "core" tier (~5K high-confidence entries) in the APK, download "standard" (~32K) via WorkManager, offer "full" (~100K+) as opt-in. Source: Carrion's three-tier model.
- **Notification channels**: Split current notifications into 3 channels: `BLOCKING_EVENTS` (individual blocks), `DIGEST_SUMMARY` (daily/weekly), `SYSTEM_ALERTS` (permission issues, blocking paused). Source: Android notification best practices.
- **Test gaps**: Compose Testing v2 migration, process-death logging verification (durable logging exists but needs test), WildcardRule ASCII normalization, API 36 target behavior, distribution metadata lint.

## Rejected Ideas

- **Truecaller-style contact upload / account graph**: Violates on-device-first and no-accounts philosophy. Source: Truecaller commercial model.
- **Cloud audio deepfake detection / transcription**: Violates no-cloud-audio. On-device variants only (B.M.4). Source: Hiya AI Phone, RoboKiller.
- **Full voicemail / answer bot / IVR replacement**: Scope creep into dialer replacement; TCPA recording-consent legal risk. Source: YouMail, RoboKiller.
- **Carrier B2B verified-caller partnerships (near-term)**: Business-development dependency, weak fit for FOSS. Source: Hiya/Truecaller carrier integrations.
- **Plugin marketplace before core trust fixes**: Subscriptions and diagnostics fit; unbounded third-party code execution does not. Source: Pi-hole/rspamd patterns.
- **Mandatory Play Integrity gating**: Detection and F-Droid builds must work without GMS. Optional contribution hardening already tracked. Source: Android Play Integrity docs.
- **iOS/KMP near-term port**: Current value is Android telecom integration. Room driver work can prepare later. Source: Saracroche iOS architecture.
- **Full workflow scripting engine (SpamBlocker-style)**: High maintenance cost, attack surface for arbitrary HTTP in blocking path. External blocklist URL subscription (B.F.7) covers the data-ingest angle with lower risk. Source: SpamBlocker workflow engine.
- **Groq/LLM SMS classification (near-term)**: Privacy concern (SMS body sent to third-party API), rate limits, free-tier fragility. On-device GBT + content analyzer already covers SMS scoring. Source: SpamBlocker v5.2 Groq integration.
- **MobileBERT/transformer SMS scorer**: Latency vs. accuracy tradeoff inside 5-second deadline. GBT v3 runs in microseconds; transformer models run in tens-to-hundreds of milliseconds. Watch JingHu project for accuracy benchmarks before considering. Source: JingHu GitHub.
- **CardDAV blocklist export**: Niche use case (FRITZ!Box, PBX). Low community demand signal. Source: PhoneBlock.

## Sources

Direct OSS competitors:
- https://github.com/aj3423/SpamBlocker
- https://github.com/aj3423/SpamBlocker/releases
- https://gitlab.com/xynngh/YetAnotherCallBlocker
- https://github.com/FossifyOrg/Phone
- https://github.com/Divested-Mobile/Carrion
- https://phoneblock.net/
- https://codeberg.org/cbouvat/saracroche-android
- https://github.com/adamff-dev/spam-call-blocker-app
- https://f-droid.org/en/packages/spam.blocker/

Commercial / platform:
- https://www.truecaller.com/
- https://www.hiya.com/
- https://www.robokiller.com/
- https://www.youmail.com/
- https://developer.android.com/about/versions/16/behavior-changes-16
- https://developer.android.com/about/versions/17/behavior-changes-all
- https://blog.google/security/android-fake-call-detection/

Standards / regulatory:
- https://www.rfc-editor.org/rfc/rfc8225
- https://www.rfc-editor.org/rfc/rfc8588
- https://cfca.org/tns-2026-robocall-report-whats-next-going-further-than-stir-shaken/
- https://www.mintz.com/insights-center/viewpoints/2776/2025-11-20-fcc-proposes-new-rules-call-branding-and-caller-id
- https://www.gsma.com/newsroom/article/from-rich-text-to-video-rcs-universal-profile-4-0-has-arrived/
- https://docs.fcc.gov/public/attachments/DOC-410645A1.pdf

Dependencies / distribution:
- https://developer.android.com/build/releases/agp-9-0-0-release-notes
- https://github.com/google/dagger/releases/tag/dagger-2.59
- https://kotlinlang.org/docs/whatsnew23.html
- https://f-droid.org/en/docs/Inclusion_Policy/
- https://izzyondroid.org/docs/general/AppInclusionPolicy/
- https://accrescent.app/docs/guide/publish/requirements.html
- https://square.github.io/okhttp/changelogs/changelog/
- https://android-developers.googleblog.com/2026/03/room-30-modernizing-room.html

Academic / engineering:
- https://arxiv.org/pdf/2402.18085
- https://arxiv.org/pdf/2504.12423
- https://source.android.com/docs/security/bulletin/2026/2026-06-01

## Open Questions

None — all items are actionable with available information.
