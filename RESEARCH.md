# Research - CallShield

## Executive Summary

CallShield is a privacy-first Android call/SMS/RCS blocker with a mature local architecture: Kotlin/Compose, Hilt, Room, WorkManager, pinned OkHttp clients, on-device scoring, a priority-sorted checker pipeline, RCS notification filtering, URLhaus checks, community reporting, reproducible-build groundwork, and broad local verification. The highest-value direction is still trust-boundary hardening rather than a new product surface: finish UI number normalization, cap remote reads, make remote/source failures observable, keep STIR/SHAKEN copy precise, protect SMS content in logs and exports, and make every imported or queried data source reversible and privacy-aware. Top opportunities: P0 UI ASCII-only phone-number handling; P1 bounded enrichment and first-party feed reads; P1 SMS-body redaction by default; P1 URLhaus token/privacy mode; P1 STIR/PASSporT badge semantics; P1 per-permission degraded behavior; P1 answered/emergency/SMS-burst context rules; P2 temporary allow/block windows; P2 Android 16/17 compatibility smoke tests; P2 selective backup/export sections.

## Product Map

- Core workflows: screen incoming calls under the `CallScreeningService` deadline; block/silence via local rules, Android system block list, STIR/SHAKEN, heuristics, campaign detection, and ML; filter SMS/RCS; inspect logs and reasons; sync public spam data; submit community reports.
- User personas: privacy-focused Android and F-Droid users, power users who want explainable local rules, and non-experts who need safe defaults plus direct recovery actions.
- Platforms and distribution: Android minSdk 29 / targetSdk 36; GitHub Releases and direct APK install; Fastlane/F-Droid metadata and reproducible-build docs exist; F-Droid, IzzyOnDroid, and Accrescent publication remain blocked on external submission/review actions in `Roadmap_Blocked.md`.
- Key integrations and data flows: GitHub raw feeds to WorkManager/Room; Cloudflare Worker report submission; URLhaus SMS URL checks; optional AbstractAPI no-backup key; SkipCalls, PhoneBlock, WhoCalledMe, and OpenCNAM overlay enrichment; local backup/restore and CSV export.

## Competitive Landscape

- SpamBlocker: Closest OSS benchmark. It does optional-permission call/SMS blocking, SMS screening provider mode, rule priorities, answered/emergency context, API presets, conflict diagnostics, SMS reply workflows, and recent large-response crash fixes well. CallShield should borrow context-rule discipline, source diagnostics, and capped network reads, but avoid arbitrary workflow scripting in hot screening paths.
- Carrion and simpler FOSS blockers: Strong references for STIR/SHAKEN enforcement, public reputation DBs, prefix blocks, no-account use, and simple recovery. CallShield should learn the conservative STIR stance, but avoid under-tested legacy broadcast and migration assumptions.
- NekoSMS and other SMS-only blockers: Good model for local sender/content rules, wildcard/regex behavior, backup/restore, and no-internet/no-telemetry positioning. CallShield should copy the privacy clarity, not split into a separate SMS app.
- Google Phone/Messages and Android platform APIs: Platform direction favors verified-caller metadata, cautious scam warnings, tighter SMS/OTP access, and large-screen behavior enforcement. CallShield should keep compatibility tests current and avoid promising privileges only the system dialer or Messages app can provide.
- Hiya, Truecaller, RoboKiller, YouMail, and carrier blockers: Commercial products sell caller identity, automatic spam rejection, SMS scam protection, call screening, visual voicemail, synthetic-voice warnings, and business identity. CallShield should borrow risk routing, recovery, and source-health UX while rejecting accounts, contact upload, ads, cloud audio, and voicemail replacement.
- Pi-hole and URLhaus: The useful adjacent pattern is safe data ingestion: source attribution, fetch caps, dry-run diffs, rebuild/rollback, per-source disable, and privacy-aware URL submission before remote data affects live decisions.

## Security, Privacy, and Reliability

- Verified: `LookupScreen.kt`, `BlocklistScreen.kt`, `BlockedLogScreen.kt`, and `RecentCallsScreen.kt` still contain UI-created phone-number paths using `Char.isDigit()` or Unicode-aware digit filters, while `PhoneDigits.kt` documents ASCII-only behavior for security-sensitive paths.
- Verified: `ExternalLookup.kt`, `WebLookup.kt`, and `NumberTypeChecker.kt` use `response.body?.string()` and broad fallback states. Existing roadmap coverage should add byte caps and typed states so timeout, rate limit, parse failure, oversized body, and no-hit do not collapse into clean/null results.
- Verified: `GitHubDataSource.kt` reads GitHub raw spam/hot/domain feeds with `response.body?.string()` and no explicit per-feed byte/row/schema caps. This is separate from optional future subscriptions because these trusted feeds already feed Room and hot-path caches.
- Verified: `LogExporter.kt` writes a `SMSBody` column, `NumberDetailScreen.kt` and `BlockedLogScreen.kt` render stored SMS bodies, and `BlockedCall.smsBody`/`PendingBlockedCallLog.smsBody` persist raw message text. Default export/preview behavior should redact message bodies or sensitive indicators, with an explicit raw export action.
- Verified: `UrlSafetyChecker.kt` extracts candidate URLs from full SMS/RCS body text and submits URLs to URLhaus. Because phishing URLs can contain recipient tokens, CallShield needs local-domain-first checks plus a setting that strips fragments and optionally query strings before remote lookup.
- Verified: `settings_stir_trusted_allow_desc` warns about over-attestation, but `ROADMAP.md` item 2.3.2 still frames A-level attestation as a green badge. Android and STIR/SHAKEN sources support displaying carrier attestation status, not caller safety.
- Verified: `AndroidManifest.xml` declares call-log, contacts, SMS, overlay, notification-access, notification-posting, internet, boot, and call-screening capabilities. Dashboard and Protection Test cover several readiness states, but no single per-permission degraded-mode contract covers every revoked, denied, unsupported, or OEM-broken state.
- Verified: `BackupRestore.kt` validates backup payloads and supports merge/replace preview, but exports/restores sections as one combined payload. Selective rule/settings/log migration remains useful and distinct from diagnostics export.
- Likely: Recent robocall research and commercial blocker updates make campaign context, answer history, source health, and cautious confidence language more valuable than adding cloud audio or account-backed reputation.

## Architecture Assessment

- The checker pipeline remains the correct boundary for new trust rules. Add answered-caller, emergency-callback, temporary allow/block, and SMS-burst behavior as priority slots rather than service-layer branches.
- Remote code needs a shared capped-body reader plus typed source result model. It should be reused by enrichment APIs, URLhaus, GitHub feed fetches, and future subscription imports.
- SMS privacy should centralize around a redaction helper used by log export, log/detail UI, diagnostics, notifications, and tests. Store raw bodies only when needed for local detection/recovery, and make raw export explicit.
- URL safety should prefer local `spam_domains`/domain extraction before remote URLhaus checks, then submit only the minimum configured URL form.
- PASSporT parsing and attestation scoring should update `BlockReasoning.kt`, overlay UI, settings copy, and tests together. UI should say what was carrier-attested and keep explicit user/system blocks ahead of attestation.
- Permission and role readiness belong in a single shared model consumed by dashboard, onboarding, settings, Protection Test, and instrumentation tests. `CallShieldPermissions.kt` is the right starting point.
- Categories consciously covered: security, privacy, reliability, accessibility/readability, i18n/l10n, observability, testing, docs, distribution, offline resilience, mobile/platform compatibility, migration, and upgrade strategy. Plugin ecosystem work should start as reversible data subscriptions, not executable plugins. Multi-user/enterprise remains a later product track already represented in the roadmap/blocked list.

## Rejected Ideas

- SpamBlocker SMS Reply/meeting auto-reply as a near-term item: requires SEND_SMS-style behavior and pairs naturally with meeting mode, which is already blocked on a permission/product decision in `Roadmap_Blocked.md`.
- Full arbitrary workflow scripting from SpamBlocker: high maintenance and execution risk inside a 5-second local screening path.
- Treating A-level STIR/SHAKEN as a green safe badge: rejected because STIR/SHAKEN attests carrier/origination metadata, not caller intent or legality.
- Required Play Integrity, SMS Retriever, GMS-only checks, or account-backed reputation: incompatible with F-Droid/de-Googled users as a core requirement, though optional Play-build contribution hardening can remain under consideration.
- Contact upload, address-book graph matching, or always-on cloud reputation: incompatible with on-device-first privacy.
- Cloud audio, assistant bots, voicemail replacement, or automatic call transcription: incompatible with the no-cloud-audio direction and too large for CallShield's current Android call-screening advantage.
- KMP/iOS before telecom trust work: CallShield's strongest value is Android-native call/SMS/RCS integration; portability should wait behind Room/AGP migration and trust hardening.
- Plugin marketplace before bounded subscriptions and diagnostics: executable third-party logic is the wrong first extension point; reversible data feeds are safer.

## Sources

Direct OSS competitors:
- https://github.com/aj3423/SpamBlocker
- https://github.com/aj3423/SpamBlocker/releases
- https://github.com/aj3423/SpamBlocker/wiki/SMS-Screening-protocol
- https://f-droid.org/en/packages/spam.blocker/
- https://github.com/Divested-Mobile/Carrion
- https://github.com/apsun/NekoSMS
- https://github.com/adamff-dev/spam-call-blocker-app
- https://github.com/aj3423/SpamBlocker/issues/604

Commercial and community signal:
- https://play.google.com/store/apps/details?hl=en_US&id=com.webascender.callerid
- https://techcrunch.com/2024/03/18/truecaller-automatically-reject-all-spam-calls-android-update/
- https://www.youmail.com/features/spam-blocking/
- https://play.google.com/store/apps/details?hl=en_US&id=com.robokiller.app
- https://forum.f-droid.org/t/what-app-to-block-spam-and-spoofed-calls/32085

Standards and platform APIs:
- https://developer.android.com/reference/android/telecom/CallScreeningService.CallResponse.Builder
- https://developer.android.com/develop/connectivity/telecom/dialer-app/prevent-spoofing
- https://developer.android.com/about/versions/16/behavior-changes-16
- https://developer.android.com/about/versions/17/behavior-changes-17
- https://android-developers.googleblog.com/2026/02/prepare-your-app-for-resizability-and.html
- https://www.rfc-editor.org/rfc/rfc8225.html
- https://datatracker.ietf.org/doc/html/rfc8588
- https://www.fcc.gov/call-authentication
- https://www.fcc.gov/consumers/guides/stop-unwanted-robocalls-and-texts

Dependencies, security, adjacent systems, and research:
- https://developer.android.com/build/releases/agp-9-0-0-release-notes
- https://android-developers.googleblog.com/2026/03/room-30-modernizing-room.html
- https://github.com/google/dagger/releases
- https://square.github.io/okhttp/changelogs/changelog/
- https://urlhaus.abuse.ch/api/
- https://docs.pi-hole.net/main/pihole-command/
- https://nvd.nist.gov/vuln/detail/cve-2024-7254
- https://arxiv.org/html/2606.31790v1

## Open Questions

None that block prioritization. External store publication, hash-report migration, SMS screening provider mode, AGP/Room/Hilt migration, and meeting mode already have concrete blockers tracked in `Roadmap_Blocked.md`.
