# Research - CallShield

## Executive Summary

CallShield is a privacy-first Android call/SMS/RCS spam blocker with a strong current shape: Hilt-wired Kotlin/Compose architecture, a priority-sorted checker pipeline, Room migrations through v10, durable pending blocked-call logging, pinned network clients, on-device GBT scoring, RCS notification filtering, URLhaus checks, Cloudflare Worker community reporting, and 645 JVM tests. The highest-value direction is to harden trust and platform behavior before broad expansion: finish ASCII-only phone handling in UI entry paths; add bounded response guards to enrichment lookups; add answered-caller, emergency-callback, and SMS-burst context rules; keep Android 16/17 behavior smoke coverage current; move STIR/SHAKEN from binary trust toward PASSporT/attestation scoring; and continue distribution/privacy work already tracked in `Roadmap_Blocked.md`.

## Product Map

- Core workflows: screen incoming calls under the 5-second `CallScreeningService` deadline; block/silence by local allow/block data, Android system block list, STIR/SHAKEN, rules, heuristics, campaign burst, and ML; filter SMS/RCS; inspect logs and rule traces; sync public spam data; contribute community reports.
- User personas: privacy-focused Android/F-Droid users, power users who want explainable local rules, and non-experts who need safe defaults with clear recovery.
- Platforms and distribution: Android minSdk 29 / targetSdk 36; GitHub Releases and Obtainium-compatible APKs; Fastlane/F-Droid metadata exists; F-Droid, IzzyOnDroid, and Accrescent publication are tracked external steps.
- Key integrations and data flows: GitHub raw feeds -> WorkManager/Room; Cloudflare Worker -> `data/reports`; URLhaus SMS URL checks; optional AbstractAPI no-backup key; caller-ID overlay lookups through SkipCalls, PhoneBlock, WhoCalledMe, and OpenCNAM; local backup/restore and CSV export.

## Competitive Landscape

- SpamBlocker: Active FOSS leader with 1.6k stars, workflow/API-query presets, SMS screening protocol support, rule priority conflict detection, contact-prefix allow, database-prefix expansion, emergency semantics, and bounded regex/API fixes. Learn from its context rules, priority diagnostics, and provider protocol. Avoid arbitrary remote workflow execution in the blocking path and migration churn.
- PhoneBlock: Community reputation service with privacy-oriented number handling and PhoneBlock API presets used by competitors. Learn from hashed submission and threshold reputation models. Avoid region/platform lock-in.
- Fossify Phone: Privacy dialer reference for Android block-list and multi-SIM/dialer ergonomics. Learn system integration patterns. Avoid becoming a replacement dialer unless the telecom API requires it.
- Carrion / YetAnotherCallBlocker / NoPhoneSpam: Useful historical FOSS references but mostly inactive. Learn tiered database packaging and simple UX. Avoid single-maintainer stagnation and untested Java-era broadcast assumptions.
- Google Phone and Messages: Moving into Gemini Nano fake-call detection, RCS verification, and dialer/messaging-integrated scam detection. Learn from confidence and metadata UX. Avoid promises that third-party apps cannot meet without dialer/Messages privileges.
- Truecaller, Hiya, RoboKiller, YouMail: Commercial products emphasize AI voice detection, call assistants, visual voicemail, family protection, business caller identity, and account-backed reputation. Learn trust UX and recovery flows. Avoid contact upload, accounts, ads, cloud audio, and voicemail replacement.
- Carrier blockers: T-Mobile Scam Shield, AT&T ActiveArmor, and Verizon Call Filter operate upstream of Android screening. Learn local replicas such as neighborhood/area filters and branded-call display. Avoid partnership-dependent features as near-term roadmap work.

## Security, Privacy, and Reliability

- Verified: `app/src/main/java/.../ui/screens/lookup/LookupScreen.kt`, `BlocklistScreen.kt`, `BlockedLogScreen.kt`, and `RecentCallsScreen.kt` still use `Char.isDigit()` / `filter { it.isDigit() }` in user-entry or review paths while `util/PhoneDigits.kt` exists specifically to prevent Unicode digit spoofing. Security-sensitive data paths are mostly fixed, but UI-created rules can still normalize differently than the checker path.
- Verified: `CommunityContributor.kt` sends raw E.164 numbers and `worker/community-reports-worker.js` writes raw-number report files and commit messages to GitHub. This is already tracked as a blocked hash-report migration, but it remains the largest privacy gap.
- Verified: `ExternalLookup.kt`, `WebLookup.kt`, and `NumberTypeChecker.kt` parse remote response bodies on overlay/enrichment paths. SpamBlocker v5.11 fixed a crash from oversized API responses, so CallShield should add explicit response-size caps and parser contract tests for every enrichment host.
- Verified: `AndroidManifest.xml` still declares `READ_SMS` and `RECEIVE_SMS`; SpamBlocker v5.9 shows an SMS-app-query provider protocol can reduce permission surface when the SMS app supports it. CallShield's receiver remains required for broad compatibility until that blocked item lands.
- Verified: `RcsNotificationListener.kt` already degrades gracefully when encrypted/empty bodies arrive, but GSMA Universal Profile 4.0 and Google RCS verification work mean sender metadata, frequency, and contact-verification signals will matter more than content rules.
- Verified: targetSdk 36 behavior is covered by an instrumented smoke test, but Android 17 OTP broadcast delay and Android 16 `SDK_INT_FULL`/notification behavior are not yet a standing compatibility matrix.
- Verified: STIR/SHAKEN is integrated, but RFC 8225/8588 PASSporT parsing and attestation scoring remain open. TNS/CFCA signal says binary A-level trust is not enough against SIM-box and over-attestation fraud.

## Architecture Assessment

- The checker pipeline is a good boundary for new context rules. Add answered-caller, emergency-callback, and SMS-burst checkers as small priority slots rather than branching in `CallShieldScreeningService`.
- `CallbackDetector.kt` already has indexed CallLog query helpers for outgoing and repeated urgent calls; extend that seam instead of adding new raw CallLog scans.
- `CommunityContributor.kt` and the Worker need a coordinated schema migration before hashed reports can land; keep it in `Roadmap_Blocked.md` until client, Worker, scripts, and old raw report cleanup can move together.
- Build/dependency work is intentionally blocked into a dedicated AGP 9 + Kotlin 2.3 + Hilt 2.59 + Moshi migration tranche. Do not scatter partial lockfile churn across feature work.
- Existing roadmap coverage is strong for accessibility, i18n/l10n, distribution, docs, testing, offline resilience, and plugin/subscription concepts. The remaining gaps are not lack of ideas; they are trust boundaries, compatibility validation, and bounded remote parsing.

## Rejected Ideas

- Full SpamBlocker-style arbitrary workflow scripting: powerful but too much remote-execution and maintenance risk for CallShield's 5-second local blocking philosophy. Source: SpamBlocker releases/wiki.
- Cloud audio/deepfake/transcription pipeline: violates no-cloud-audio; only on-device, opt-in audio work should remain under consideration. Source: Hiya, RoboKiller, YouMail.
- Truecaller-style contact upload or account graph: violates on-device-first and no-accounts philosophy. Source: Truecaller.
- Required Play Integrity gating: incompatible with F-Droid/de-Googled users. Optional contribution hardening can remain separate. Source: Play Integrity docs.
- Near-term carrier branded-caller partnerships: valuable but business-development dependent. Source: Hiya/Truecaller/carrier products.
- iOS/KMP port before telecom trust work: current advantage is Android call-screening integration. Source: Saracroche and Android telecom docs.
- Plugin marketplace before bounded subscriptions and diagnostics: plugin ecosystems are useful later, but third-party executable logic is the wrong first step. Source: Pi-hole/rspamd/SpamBlocker patterns.

## Sources

Direct OSS competitors:
- https://github.com/aj3423/SpamBlocker
- https://github.com/aj3423/SpamBlocker/releases
- https://gitlab.com/xynngh/YetAnotherCallBlocker
- https://github.com/FossifyOrg/Phone
- https://github.com/Divested-Mobile/Carrion
- https://phoneblock.net/
- https://codeberg.org/cbouvat/saracroche-android
- https://f-droid.org/en/packages/spam.blocker/

Commercial / carrier / platform:
- https://www.truecaller.com/
- https://www.hiya.com/
- https://www.robokiller.com/
- https://www.youmail.com/
- https://blog.google/security/android-fake-call-detection/
- https://developer.android.com/reference/android/telecom/CallScreeningService.CallResponse.Builder
- https://developer.android.com/about/versions/16/behavior-changes-16
- https://developer.android.com/about/versions/17/behavior-changes-all

Standards / regulatory:
- https://www.rfc-editor.org/rfc/rfc8225
- https://www.rfc-editor.org/rfc/rfc8588
- https://cfca.org/tns-2026-robocall-report-whats-next-going-further-than-stir-shaken/
- https://www.gsma.com/newsroom/article/from-rich-text-to-video-rcs-universal-profile-4-0-has-arrived/
- https://www.fcc.gov/consumers/guides/stop-unwanted-robocalls-and-texts

Dependencies / distribution / security:
- https://developer.android.com/build/releases/agp-9-0-0-release-notes
- https://github.com/google/dagger/releases/tag/dagger-2.59
- https://kotlinlang.org/docs/whatsnew23.html
- https://android-developers.googleblog.com/2026/03/room-30-modernizing-room.html
- https://square.github.io/okhttp/changelogs/changelog/
- https://f-droid.org/en/docs/Inclusion_Policy/
- https://izzyondroid.org/docs/general/AppInclusionPolicy/
- https://accrescent.app/docs/guide/publish/requirements.html
- https://source.android.com/docs/security/bulletin/2026/2026-06-01

## Open Questions

None that block prioritization. External publication, AGP migration, and hashed-report rollout need dedicated execution sessions, not more research.
