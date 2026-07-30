# Research — CallShield
Date: 2026-07-21 — replaces all prior research. Anchored to **v1.7.18 (versionCode 46)**. The reliability + platform-risk backlog this file used to headline is now **shipped** (v1.7.14–v1.7.18); this pass repositions around distribution survival, detection-quality reality-checks, and testability.

> **Staleness note (2026-07-30, v1.7.28 / versionCode 56).** This document has not
> been re-run since v1.7.18. Ten releases have shipped since, including two full
> audit passes (v1.7.24, v1.7.26–v1.7.28) that drained ~150 findings. Treat the
> *strategic* sections (distribution survival, the STIR/SHAKEN feasibility
> ceiling, the competitive landscape) as current, and the *codebase* claims —
> test counts, "not yet wired", "already tracked" — as needing verification
> against `ROADMAP.md` and `CHANGELOG.md` before you act on them. Known drift:
> the JVM suite is 948 tests, not 789; `scripts/evaluate_model.py` now exists and
> gates the model; the shipped GBT was retrained in v1.7.28 after a time-of-day
> poisoning bug. A stranded 2026-07-22 draft of this file was discarded on
> 2026-07-30 because every P0 it raised (outgoing-call direction, raw reports in
> APK assets, Room in cloud backup, DataStore corruption handlers) had already
> shipped.

## Executive Summary

CallShield is a privacy-first, fully on-device Android call/SMS/RCS spam blocker with an unusually mature, heavily-audited codebase (priority-sorted `IChecker` pipeline, pure-Kotlin GBT scorer with an observable `ModelHealth` signal, Hilt, Room 2.8.4, WorkManager, pinned OkHttp 5.4, reproducible-build groundwork, a Robolectric harness, **789 JVM tests**). Between 2026-07-14 and 2026-07-21, v1.7.14–v1.7.18 drained essentially the entire correctness **and** background-reliability backlog: after-call/phishing/campaign fixes, the wangiri/premium false-positive fix, atomic backup restore, bounded imports, Android-16 notification grouping, observable model health, **RCS listener `requestRebind` self-heal**, **corrupt-DB recovery (ends silent fail-open)**, **BootReceiver `MY_PACKAGE_REPLACED`**, **bounded DigestWorker**, **OEM background-kill detection**, **sync-staleness predicate**, and **contacts-mode degradation detection**. Correctness and background-execution survival are both in strong shape.

The highest-value direction has shifted to **distribution survival** and **detection-quality reality-checks**. Distribution: Google's Developer Verification begins **2026-09-30 in Brazil/Indonesia/Singapore/Thailand** and expands globally through 2027; it blocks sideloaded APKs on certified devices from unverified developers, and **F-Droid has said it cannot comply**. The concrete escape hatch is **Accrescent**, which has *already self-registered* in the verification program and is a GrapheneOS-default store — but it rejects **debug-signed** APKs and wants a **bundletool `.apks` split set**, while CallShield's current release artifacts are debug-key-signed. Detection: the roadmap's STIR/SHAKEN **PASSporT-depth** items (2.3.1–2.3.5) are **likely infeasible** — a third-party `CallScreeningService` receives only the binary verdict via `getCallerNumberVerificationStatus()`, never the raw SIP `Identity`/PASSporT token, attestation A/B/C, or `origid`. The behavioral-ML direction (2.2.2 call-frequency, 2.2.3 ring-duration) is validated by 2025–2026 research but gated on a **local** retrain pipeline (the README's "weekly CI retrain" contradicts the repo's no-GitHub-Actions rule).

Top opportunities, priority order: (P1) Accrescent packaging readiness — the actionable half of Dev-Verification survival; (P2) release-signing hygiene gate (reject debug/multi-cert in release); (P2) local model retrain+eval reproducibility to unblock behavioral features; (P3) reconsider/close the infeasible STIR PASSporT-depth items; (P3) evaluate `ACTION_POST_CALL` as a complement to the custom after-call notification. Deeper bets already tracked (behavioral-ML retrain 2.2.x, persistent call-graph 2.4, Baseline Profile 2.6.4, injectable-appScope e2e) remain valid.

## Product Map

- **Core workflows:** screen incoming calls under the `CallScreeningService` 5 s deadline via the priority-sorted checker ladder; filter SMS (`SmsReceiver`, priority 999) and RCS (`RcsNotificationListener`, Google/Samsung Messages); inspect logs with per-decision reasoning + after-call feedback; sync public spam data via WorkManager; submit anonymous Cloudflare community reports; subscribe to external blocklists; area-code + multi-source caller-ID overlay.
- **User personas:** privacy-focused / de-Googled Android users; power users wanting explainable local rules and per-rule schedules; non-experts needing safe defaults + one-tap recovery.
- **Platforms & distribution:** Android minSdk 29 / targetSdk 36 / compileSdk 36; AGP 8.10.1, Kotlin 2.2.21. GitHub Releases + Obtainium today; F-Droid/IzzyOnDroid/Accrescent tracked. No GMS-only paths. **Distribution model is directly threatened by Developer Verification.**
- **Key integrations & data flows:** GitHub raw feeds → WorkManager → Room; Cloudflare Worker for reports; URLhaus for URL safety; SkipCalls/PhoneBlock/WhoCalledMe/OpenCNAM overlay enrichment; local selective backup/restore + CSV export. Zero manual API keys by design.

## Competitive Landscape

- **SpamBlocker (aj3423)** — OSS benchmark, very actively maintained (v5.12, 2026-06-28). New in 2026: a **"workflow" system** binding triggers (RepeatedCall / Contacts / RegexRules) to actions (Ringtone, **auto SMS-reply when a call is blocked**), plus API queries weighting **positive/negative community rating counts**. *Learn:* the trigger→action workflow model is a clean generalization of scattered toggles; rating-count weighting is a lightweight reputation signal. *Avoid:* auto-SMS-reply needs `SEND_SMS` — a **philosophy conflict** with CallShield's minimal-permission, no-manual-credential stance. The still-open **block-by-caller-ID-name** ask (#120) is already tracked (CallerNameChecker) and remains valid.
- **SpamBlocker Extended (dev.kerballone.spamblocker)** — notification-layer OTT screening (RCS/Signal/WhatsApp/email) is still the novel FOSS capability and the practical answer to E2EE RCS. Already tracked (P2 OTT item); *avoid* screening private-messenger notifications without an explicit opt-in UX.
- **Fossify / Silence / YetAnotherCallBlocker** — no new detection capability in 2026; CallShield's on-device-ML + free community-DB + no-cloud + reproducible-build niche is uncontested.
- **Google Phone / Messages** — Gemini-Nano scam detection, in-call financial-scam protection (Android 16 "Advanced Protection"), and the fake-call E2EE-RCS handshake are all platform-internal, no third-party API. *Learn:* Android 16 blocks risky device changes (sideloading, banking apps) during active calls — a trust-UX bar. CallShield's honest niche stays unknown/non-contact spam with no Google apps.
- **Hiya / Truecaller / RoboKiller** — deepfake/synthetic-voice detection remains paywalled and cloud-tied; only an on-device variant would be admissible here.
- **Accrescent (store, not a competitor)** — the strategic distribution model to emulate: developer submits their own signed `.apks`; the store is verification-registered and GrapheneOS-default. This is the survival path F-Droid's build-and-sign model cannot offer.

## Security, Privacy, and Reliability

- **Release artifacts are debug-key-signed.** The release build makes signing conditional on `RELEASE_STORE_*` in `local.properties` (`app/build.gradle.kts:41-69`); when absent, `signingConfig = null` yields an *unsigned* APK that is then debug-signed for local install. Debug-signed releases (a) are rejected by Accrescent, (b) break key continuity / can't co-install with a real-key build, and (c) fail the "single stable release key" prerequisite of Dev-Verification registration. Add a release-time guard that fails if the packaged cert is a debug cert or multiple certs are present. **Verified** (build config + this session's `CallShield-v1.7.18.apk` is debug-signed).
- **`ModelHealth` / degraded states are computed but not surfaced.** `SpamMLScorer.modelHealth()`, enrichment health, and the new `CallShieldPermissions.isContactsModeDegraded()` predicate all exist but have **no diagnostics UI**. Silent-degradation detection with no user-visible signal is only half the guardrail. UI wiring is owned by a concurrent agent; tracked (P3 diagnostics UI). **Verified.**
- **Model retrain reproducibility gap.** README/roadmap reference a "weekly CI retrain" of `spam_model_weights.json`, but GitHub Actions are prohibited by repo policy — so weights cannot auto-retrain. The retrain+eval must be a documented **local** flow (`scripts/train_spam_model.py` + a missing `scripts/evaluate_model.py`, roadmap 2.6.3). Until then the shipped model is effectively static. **Verified** (no workflows; doc/reality mismatch).
- **No new stack CVEs on 2026-07-21.** OkHttp 5.4.0, Room 2.8.4, WorkManager 2.11.2, Moshi 1.15.2 clean at pinned versions. CVE-2026-53914 (Kotlin < 2.4.20) mitigation (no remote build cache) is documented; the Kotlin bump rides the AGP 9 tranche in `Roadmap_Blocked.md`. Kotlin's new security-advisory policy applies to 2.4+. **Verified.**

## Architecture Assessment

- **Testability of the 5 s hot path.** `CallShieldScreeningService.onScreenCall` launches on the process-wide `CallShieldApp.appScope` with an explicit `Dispatchers.IO`, so a Robolectric test cannot deterministically await the verdict. The unblocker is an injectable scope+dispatcher seam (tracked P3, "Robolectric onScreenCall e2e"). The Robolectric harness itself now exists (`SpamActionReceiverRobolectricTest`, `CheckerPipelineRunTest`). **Verified.**
- **STIR/SHAKEN depth ceiling.** `data/StirShakenSemantics.kt` implements the binary trusted-allow / FAIL-block layer over `getCallerNumberVerificationStatus()`. There is no PASSporT parser and cannot usefully be one on the screening path (see Rejected). `StirShakenTrustChecker` is the correct ceiling for a third-party app. **Verified.**
- **Behavioral ML features are partially in.** `SpamMLScorer.extractFeatures()` already carries `time_of_day_sin/cos` (16/17) and `geographic_distance` (18); the function is number-only, so **call-frequency-window (2.2.2)** and **ring-duration (2.2.3)** features are not yet wired and both need per-call context + a model retrain (2.2.5) to take effect. **Verified.**
- **Test/doc gaps:** `scripts/evaluate_model.py` (precision/recall/F1) is referenced but absent; the Baseline Profile module (`app/baselineprofile/`) is absent; there is no on-device Macrobenchmark for screener cold-start. All already tracked (2.6.2/2.6.3/2.6.4).

## Rejected Ideas

- **STIR/SHAKEN PASSporT-depth parsing (roadmap 2.3.1–2.3.5): full PASSporT parse, attest A/B/C, `origid`, RCD.** Likely **infeasible** for a third-party `CallScreeningService`: the public API surfaces only `getCallerNumberVerificationStatus()` (PASSED/FAILED/NOT_VERIFIED). The raw SIP `Identity` header / PASSporT token, attestation level, and `origid` are delivered to the terminating carrier and, at most, the default dialer via `Connection`/`InCallService` on some OEMs — never a screening app. Keep `StirShakenTrustChecker` as the ceiling. *Source: developer.android.com/develop/connectivity/telecom/dialer-app/prevent-spoofing; AOSP `CallScreeningService.java`.* **(Needs live validation on a STIR-capable device before formally closing 2.3.x.)**
- **Rich Call Data (RCD) branded-caller-ID display in the overlay.** The FCC's 2025–2026 FNPRM pushes RCD (verified name/logo over A-attestation), but RCD is delivered carrier→handset via the dialer/RCS stack, not exposed to third-party screening/overlay apps. Revisit only if Android adds a public RCD API. *Source: FCC Call Branding FNPRM (DOC-415059A1).*
- **Auto SMS-reply when a call is blocked (SpamBlocker v5.12).** Requires `SEND_SMS`; conflicts with CallShield's minimal-permission philosophy. *Source: SpamBlocker v5.12 release notes.*
- **Always-on cloud reputation gating / server reputation API (roadmap 3.1/3.3.2).** Breaks on-device-first; already flagged rejected in the roadmap. A **local, offline** report-count confidence field carried in the GitHub feed is the admissible variant (Under Consideration, not scheduled).
- **`ACTION_POST_CALL` as a *replacement* for the custom after-call notification.** `NotificationHelper.notifyAfterCall` gives more control (spam/not-spam actions, contact re-check) and is less intrusive than launching a full post-call Activity after every call. A *complementary* experiment is worth a spike (Open Questions), not a replacement. *Source: developer.android.com/develop/connectivity/telecom/dialer-app/screen-calls.*
- **Sensor/wakelock "keep-alive" OEM-survival tricks** — battery/policy risk, irrelevant to an event-driven screener; the sanctioned mechanisms already shipped (`requestRebind`, boot/update receivers, battery-optimization exemption prompt) suffice.

## Sources

Distribution / verification:
- https://f-droid.org/2026/02/24/open-letter-opposing-developer-verification.html
- https://thehackernews.com/2026/06/google-sets-sept-30-deadline-for.html
- https://www.androidauthority.com/android-sideloading-changes-timeline-3679204/
- https://blog.accrescent.app/posts/android-developer-verification/
- https://accrescent.app/docs/guide/publish/requirements.html
- https://accrescent.app/faq

Competitors:
- https://github.com/aj3423/SpamBlocker/releases/tag/v5.12
- https://github.com/aj3423/SpamBlocker/wiki/SMS-Screening-protocol
- https://f-droid.org/packages/dev.kerballone.spamblocker/

Platform / telecom APIs:
- https://developer.android.com/develop/connectivity/telecom/dialer-app/prevent-spoofing
- https://developer.android.com/develop/connectivity/telecom/dialer-app/screen-calls
- https://github.com/aosp-mirror/platform_frameworks_base/blob/master/telecomm/java/android/telecom/CallScreeningService.java
- https://developer.android.com/reference/android/telecom/CallScreeningService.CallResponse

Standards / regulatory:
- https://docs.fcc.gov/public/attachments/DOC-415059A1.pdf
- https://www.bhfs.com/insight/fcc-proposes-major-changes-to-robocall-rules/
- https://www.acainternational.org/news/stir-shaken-in-february-2026-robocall-volume-rises-as-network-coverage-stalls/

Detection research / dependencies:
- https://ijrpr.com/uploads/V6ISSUE5/IJRPR46167.pdf
- https://link.springer.com/chapter/10.1007/978-3-032-18480-1_1
- https://blog.jetbrains.com/kotlin/2026/05/security-support-policy-for-the-kotlin-standard-library/
- https://square.github.io/okhttp/security/security/

## Open Questions

- Does any targeted OEM (Samsung One UI, Pixel) expose STIR attestation level (A/B/C) or the SIP `Identity` header to a `CallScreeningService` in 2026? If yes on a shipping device, 2.3.5 (attestation-as-ML-feature) becomes feasible; if no (expected), formally close 2.3.1–2.3.5. **Needs live validation on a STIR-capable device.**
- Will CallShield's operator register a stable identity for Developer Verification / Accrescent, or accept certified-device install friction? This gates whether Accrescent packaging (P1) leads to an actual listing. **Operator decision.**
- Are the release keystore (`callshield-release.jks`) credentials available to the release pipeline, or only debug-signing? This gates the non-debug release artifacts required for Accrescent and Dev-Verification. **Operator decision / secret availability.**
