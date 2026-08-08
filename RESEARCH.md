# Research — CallShield

Date: 2026-08-04 — replaces all prior research.
Anchored to: `master` @ `2834d0b`, versionName 1.7.33 / versionCode 61.
Verification run this pass: `./gradlew testDebugUnitTest` → **991 tests, 1 failed** (`DirectBootScreeningStoreTest:27`). Gradle/SDK/JDK notes in *Verification environment* below.

## Executive Summary

CallShield is a mature, single-module Kotlin/Compose Android call and SMS blocker: 168 main Kotlin files (~40k LOC), a 27-checker priority pipeline behind a 4.5 s budget under Android's screening deadline, an on-device GBT scorer, Room v13 with explicit migrations from v5, Hilt DI, a Python ingestion pipeline, a Cloudflare report Worker, and an unusually strong local gate set (ktlint, detekt, lint, Kover, `verifyReleaseMetadata`, `verifyPipelineTests`, `verifyReleaseSigningPolicyTests`). The detection engineering is ahead of the field. **What is broken is the delivery and data plane around it, and three of those breaks are silent.** The published release APK is unsigned and therefore uninstallable; the three feeds the app re-syncs every 30 minutes have been empty since 2026-07-30 and an empty-but-successful fetch destructively wipes the corresponding tiers on device; and the SMS receiver's ordering guarantee was removed by Android 16. The highest-value direction is not more detection layers — it is **making delivery, feed integrity, and decision auditability provable**, which is also the only axis where a no-account on-device app can beat Hiya/Truecaller/Nomorobo, since it can never beat them on fresh crowd reputation.

Top opportunities, in priority order:

1. Sign every published release artifact with one stable key — today's GitHub release APK cannot be installed at all, and blocks F-Droid, IzzyOnDroid and Accrescent simultaneously.
2. Treat a successful-but-empty remote feed as *no data*, not as *delete everything* — three checker tiers are currently inert fleet-wide.
3. Upgrade the Gradle wrapper off 8.11.1 (two High-severity CVEs with a published fix).
4. Stop depending on `android:priority="999"` for SMS ordering; Android 16 confined ordered-broadcast priority to the declaring process.
5. Add never-block floors for OTP/verification SMS and emergency numbers, plus an FCC-§64.1200(k)-shaped redress surface.
6. Persist enumerated block-reason codes and the matching rule ID (Pi-hole/NextDNS model) so every decision is filterable, exportable and reversible from the log row.
7. Surface `BackgroundExecutionStatus` — the OEM background-kill classifier ships with zero production consumers, and OEM kill is a top documented uninstall cause.
8. Close the zero-locale gap: full i18n machinery exists (1,103 strings, 30 plurals, language picker, `check_translations.py`) and not one translation ships.
9. Move the 11.1 MB monolithic `spam_numbers.json` to a sharded/delta distribution — it is bundled in the APK, re-downloaded whole on every change, and rewritten wholesale in git.
10. Extend the automated accessibility harness from 4 screens to all of them and raise its severity threshold.

## Product Map

**Core workflows**
- Live call screening: `CallShieldScreeningService` (direct-boot aware) → one DataStore snapshot → `CheckerPipeline.run()` over 27 checkers, first non-null wins, aborts on budget exhaustion → `buildBlockResponse()` (silent-voicemail / auto-mute / reject) → pending Room row flushed by `PendingBlockedCallLogWorker`.
- SMS/RCS filtering: `SmsReceiver` (BroadcastReceiver) and `RcsNotificationListener` (NotificationListenerService, 10+ selectable source apps) → shared chain plus SMS extensions (keyword 5400, context trust 4700, burst 4650, content 1900).
- Data refresh: `SyncWorker` 6 h (SHA-gated full DB), `HotListSyncWorker` 30 min (hot numbers/ranges/spam domains), `DigestWorker` 24 h, `ProtectionHealthWorker`.
- Explainable lookup: `LookupScreen` runs `CheckerPipeline.traceAll()` (non-short-circuit) → `BlockReasoning.explain()`; `NumberDetailScreen` per-number history.
- Rule authoring and recovery: exact/wildcard/regex/hash rules with per-rule day+hour schedules, temporary allow at 5350, swipe-to-remove with undo, backup/restore with passphrase, CSV/JSON export.

**Personas** — (1) the US consumer drowning in robocalls who wants it to just work; (2) the sysadmin/power user who wants rules, logs and proof; (3) the FOSS/privacy user who chose this precisely because there is no account and no telemetry. Persona 1 is the one currently served worst: five "Ready" rows on the home screen and no outcome data.

**Platforms and distribution** — Android 10+ (minSdk 29 / compile+target 36), phone only; no landscape, `sw600dp`, or foldable resources. Distribution is GitHub Releases today; `docs/fdroid/com.sysadmindoc.callshield.yml` is drafted with `Binaries:` + `AllowedAPKSigningKeys`, and IzzyOnDroid/Accrescent are queued in `Roadmap_Blocked.md`.

**Integrations and data flows** — GitHub raw (spam DB, hot feeds, model weights) with SHA pre-check and 6 h branch-resolution cache; Cloudflare Worker → GitHub Contents API commits for anonymous reports; optional enrichment (SkipCalls, PhoneBlock, WhoCalledMe, OpenCNAM) and URLhaus, all default-off and behind `HttpClient.pinnedEndpointPins` (2–3 SPKI pins per host); user-subscribed external blocklist URLs.

## Competitive Landscape

**SpamBlocker (aj3423)** — 1,761★, MIT, 15 releases in 2026, 1 open issue. Best-in-class at *rule ergonomics*: rule-priority conflict detection with a visual warning (v5.10), per-record replay/Test on history rows (v5.5), a published SMS-screening bound-service protocol so any SMS app can query it (v5.9), Recent-Apps / Meeting-Mode / calendar context rules, boolean rating expressions (v5.12), 7726 carrier reporting (v5.13). **Learn:** conflict detection and per-call replay attack the #1 documented uninstall cause directly. **Avoid:** its user-editable JSON workflow engine — the F-Droid forum's recurring complaint about the category is "too complex, unable to understand anything", and complexity is what makes users bounce.

**PhoneBlock (haumacher)** — 339★, GPL-3.0, server + client + SIP answerbot. **Learn:** the k-anonymous lookup contract (`SHA1(+E164)` plus `prefix10`/`prefix100` hashes of the number minus its last 1–2 digits) — the server answers useful queries without ever seeing a number, and range spam is still caught; and its 7-way rating taxonomy (`A_LEGITIMATE … G_FRAUD`) which drives per-category actions rather than a binary verdict. **Avoid:** the SIP answerbot — engaging callers is TCPA/recording-consent exposure and is already reject-leaning in this roadmap.

**Saracroche** — 189★, GPL-3.0, France. **Learn:** blocking by *regulator-assigned allocation range* (ARCEP telemarketing prefixes) rather than by observed history — it is the only approach that works before a number has any reputation, and it is exactly what CallShield's US-centric 51k-number list cannot do outside North America. **Avoid:** hard-coding one country's ranges into the app; this belongs in the source registry as region-scoped data.

**Nomorobo / Call Control / RoboKiller (commercial)** — Nomorobo Max ($59.99/yr) paywalls personalized allow/block lists and neighbor-spoof blocking; Call Control Premium ($29.99/yr) paywalls *wildcard blocking, unlimited lists, quiet hours, call history and settings backup*. **Learn:** every one of those is pure local logic that CallShield already ships free — say so. The genuinely new commercial signal is **tolerance controls** (RoboKiller's blocking-aggressiveness slider) and **contextual caller ID** (Truecaller moving from "spam/unknown" to *why* and *what kind*). **Avoid:** account-bound features — Truecaller Family Protection, cloud transcription, per-device licensing.

**Pi-hole / NextDNS / uBlock Origin logger (adjacent)** — the auditability reference. Pi-hole stores a per-query enumerated `status` (19 codes: gravity, regex denylist, exact denylist, CNAME-inspection…) plus `regex_id` and `additional_info`; NextDNS exposes `status`/`reasons`/`matched_name` in log, API and CSV export; uBlock's logger shows the *responsible filter* in its own column with one-click drill-down. **Learn:** a stable enumerated reason code plus the exact matching rule ID, persisted and exportable — not a free-text `matchReason` string. **Avoid:** their density; a phone log row gets one plain sentence, with the rule ID one tap deeper.

**Gmail spam banner / Windows Defender Protection History (adjacent)** — Gmail differentiates severity with chrome (grey nuisance vs red dangerous) and always gives a causal sentence, never a score; Defender shows action-taken with an explicit per-item "allow on device" escape. **Learn:** quarantine-not-delete semantics and one-tap reversal *from the log entry itself*; CallShield's Lookup screen currently leads with a "100 SPAM" gauge and "CallShield is 100% confident", which is the score-first pattern both of these deliberately avoid. **Avoid:** exposing model confidence as a headline number — it is unfalsifiable to the user and reads as arrogance when wrong.

## Security, Privacy, and Reliability

### Verified defects

1. **Published release APKs are unsigned and cannot be installed.** `CallShield-v1.7.29.apk` at the repo root is byte-identical to the GitHub release asset (sha256 `f61d865c…3e01`, 8,460,144 bytes, confirmed against `gh release view`). `apksigner verify --print-certs` returns `DOES NOT VERIFY / ERROR: Missing META-INF/MANIFEST.MF`; a byte scan finds no `APK Sig Block 42` magic, so there is no v2/v3 block either. Android's PackageManager rejects unsigned APKs outright. Separately this makes `docs/fdroid/com.sysadmindoc.callshield.yml`'s `AllowedAPKSigningKeys: d179d0da…` + `Binaries:` verification structurally impossible, and fails IzzyOnDroid's "release-key signed" and Accrescent's "v2/v3 required, debug certs rejected" rules. `app/build.gradle.kts:88-93` sets `signingConfig = null` whenever the four `RELEASE_*` properties are absent — the release build silently degrades to unsigned instead of failing. Confidence: **Verified**.

2. **A successful-but-empty remote feed destroys the hot tier on every device, every 30 minutes.** `data/hot_numbers.json`, `data/hot_ranges.json` and `data/spam_domains.json` all carry `count: 0` with `generated: 2026-07-30T17:40`. In `service/HotDataSync.kt`, `loadHotList`/`loadHotRanges`/`loadSpamDomains` return `resolved = true` on any HTTP success including an empty array; `resolved` then triggers `repo.replaceHotList(...)` → `SpamDao.replaceBySource` (`deleteBySource` then insert-if-non-empty, `SpamDao.kt:100-106`), `SpamHeuristics.updateHotRanges(empty)` and `SmsContentAnalyzer.updateSpamDomains(empty)`. The v1.7.28 fix guarded against *fetch failure*; it does not guard against *successful emptiness*. `stageBundledAssets` (`app/build.gradle.kts:29-40`) also bakes the empty files into the APK, and `primeBundled` only fills a store that is already empty, so the bundled snapshot cannot repair it either. Net effect: the `hot_list` database tier, hot campaign ranges, and the SMS spam-domain list are all inert. Confidence: **Verified** (code + data read; on-device confirmation not run).

3. **Gradle wrapper 8.11.1 carries two High-severity CVEs.** `gradle/wrapper/gradle-wrapper.properties` pins `gradle-8.11.1-bin.zip`. CVE-2026-22865 (GHSA-mqwm-5m85-gmcv) and CVE-2026-22816 (GHSA-w78c-w6vf-rw82), both CVSS4 8.6, affect `< 8.14.4`; fixed in 8.14.4 and 9.3.0. The wrapper also has no `distributionSha256Sum`, so the distribution download is unverified. Confidence: **Verified**.

4. **The JVM gate is red at HEAD.** `DirectBootScreeningStoreTest > device protected mirror preserves active explicit blocks and preferences` fails with `AssertionError at DirectBootScreeningStoreTest.kt:27` (991 tests, 1 failed, 3m43s). This was already noted in the prior research pass; it is still failing a day later, which means every green-gate claim in `CLAUDE.md` since then is unearned. Confidence: **Verified**.

5. **Android 16 removed the SMS receiver's ordering guarantee.** `AndroidManifest.xml:101` declares `<intent-filter android:priority="999">` on `SMS_RECEIVED`. On Android 16, for *all apps regardless of targetSdk*, ordered-broadcast `android:priority` is honoured only among receivers within the same process. CallShield can no longer assume it runs before the default SMS app. Confidence: **Verified** (documented behavior change; device confirmation not run).

6. **No never-block floor for OTP/verification SMS.** Grepping `SmsContentAnalyzer.kt` and `SmsReceiver.kt` for `otp` / `verification code` / `one-time` returns nothing. A 2FA code from an unfamiliar shortcode can be blocked by content keywords, and repeated codes can trip `sms_burst` (4650). US carriers already filter unregistered A2P traffic; an app-level second filter compounds a well-documented harm class. Confidence: **Verified** (absence of code).

7. **`permissions/BackgroundExecutionStatus.kt` has zero production consumers.** It is referenced only by `BackgroundExecutionStatusTest.kt`. The classifier (`classify()`, `isAtRisk()`, battery-exemption intent, MIUI autostart probe) exists and is tested, but no screen or notification reads it, while OEM background kill (Xiaomi Autostart reset after OTA, Samsung "Sleeping apps" after 3 days) is one of the best-documented causes of a silently dead blocker. Confidence: **Verified**.

8. **Worker: unguarded `JSON.parse` on KV state.** `worker/community-reports-worker.js:252` parses stored rate-limit state without a try/catch; a corrupt value throws into the outer handler and returns a misleading `400 Bad request` for what is a server-side state fault. Rate limiting is also keyed on `cf-connecting-ip || "unknown"`, which collapses every header-less caller into one shared bucket and behaves poorly under mobile CGNAT. Confidence: **Verified** (source read).

9. **Distribution/data drift.** The community-report queue holds 35 unprocessed files in `data/reports/`, ~29 of them fictional `+1555…` test numbers, and the hot feeds have not regenerated since 2026-07-30. `data/README.md` documents 1 of the 6 files in `data/`. The README test badge says 952 against an actual 991. `CLAUDE.md` header says v1.7.32/60 against a build of 1.7.33/61. Confidence: **Verified**.

### Missing guardrails

- No pipeline gate fails when a generated feed collapses to zero or shrinks past a floor — `scripts/generate_hot_list.py` and `extract_spam_domains.py` emit empty output silently, and `merge_community_reports.py` deletes `data/reports/*.json` that the generators need, so running them out of order produces exactly the current state.
- No hard floor preventing any rule from blocking emergency/PSAP numbers; `EMERGENCY_NUMBERS` in `CallbackDetector.kt:328` exists only to recognise *outgoing* emergency calls for the callback grace window.
- Checker errors and budget exhaustion are invisible: `CheckerPipeline.run()` aborts when `ctx.timeLeftMillis() <= 0` and nothing in the log tells the user a decision was made on partial evidence.
- Rule conflicts are detected only at creation time (`RuleConflictAnalyzer` is called from four `BlocklistScreen` add/edit paths); an existing rule set is never re-audited after a sync adds prefixes.
- `scripts/import_blocklists.py` is orphaned but still runnable and writes `spam_numbers.json` while bypassing the newer plausibility and source-registry gates added in `import_all_sources.py`.

### Recovery and rollback

Backup/restore covers rules, settings and logs with a passphrase and a `RestoreJournal` entity; Room self-heals on corruption. What is missing is *decision* rollback at the user level: a blocked call can be temporarily allowed from the log, but there is no per-source "this feed was wrong, drop its contribution" action, and no export shaped like the FCC's redress requirement (47 CFR §64.1200(k): on request, a list of blocked calls with date, time and calling number, free, within three business days). `LogExporter` produces CSV but is not framed or documented as that artefact.

## Architecture Assessment

**Boundaries that need work**
- Distribution is not a boundary at all: `verifyReleaseSigningPolicyTests` and `scripts/verify-release-signing.ps1` exist and *would* have caught the unsigned APK, but nothing forces them to run before `gh release create`. The signing policy needs to be a build-graph dependency of the release artifact, not a script somebody remembers.
- The feed contract has no "no data vs zero data" distinction. `FeedLoadResult.resolved` conflates *the fetch worked* with *the fetch returned something usable*. Every consumer of `resolved` inherits the bug.
- `matchReason` is a free-text `String` threaded from checkers through Room, `BlockReasoning`, `MatchReasonLabels`, exports and the widget. It should be a stable enumerated code plus an optional rule ID; the current shape is why localization, filtering and export of "why" have each been reworked separately.

**Refactor candidates (measured)**
- `ui/screens/settings/SettingsScreen.kt` (2,077 lines), `ui/screens/main/BlocklistScreen.kt` (1,826), `ui/screens/main/DashboardScreen.kt` (1,612), `data/BackupRestore.kt` (1,364), `ui/MainViewModel.kt` (1,065), `service/CallerIdOverlayService.kt` (1,040), `data/checker/Checkers.kt` (950), `data/SpamRepository.kt` (936). The four functions pinning detekt's thresholds at CCM 45 / LongMethod 250 are already measured in `Roadmap_Blocked.md`; that item is correct and stays blocked on device verification.
- `data/spam_numbers.json` is 11.1 MB (11,630,940 bytes / 413,876 lines / 51,463 numbers + 431 prefixes). It is bundled into every APK by `stageBundledAssets`, re-downloaded in full whenever its SHA moves (`SyncRepository.kt:57` gates on SHA, so the check is cheap but the transfer is not), and rewritten wholesale in git on each pipeline run — it changed 7 times in the last 200 commits, each time as a full-file rewrite. The bloom filter in 3.3.1 addresses lookup cost, not transfer cost; nothing currently addresses transfer cost.
- `ui/MainActivity.kt:347-352` routes by an integer `when(tab)` inside a `SaveableStateHolder` with `NumberDetailScreen` and onboarding as sibling root branches. There is no Navigation-Compose. This is why intent handling, tab restoration and back behaviour have each needed bespoke fixes (`v1.7.27`, `v1.7.28`), and it caps what predictive back can do.

**Test and documentation gaps**
- No tests at all for: `CallShieldWidget`, `CallShieldTileService`, `CallerIdOverlayService` lifecycle (1,040 lines, one privacy unit test), `CallLogScanner`, `SmsInboxScanner`, `MainViewModel` (1,065 lines), and all five `domain/usecase` classes.
- Automated accessibility checks (`enableAccessibilityChecks()` + `tryPerformAccessibilityChecks()`, which *does* fail the test) run on only 4 screens — Dashboard, Blocklist, Settings, Onboarding — at default severity, so the contrast, touch-target and traversal-order checks that report at WARNING never fail. Lookup, NumberDetail, Stats, Activity, BlockedLog, RecentCalls, More, Changelog, ProtectionTest and the four settings sheets are unchecked.
- No pseudolocale or RTL instrumentation despite `isPseudoLocalesEnabled = true` on debug and an orphaned `scripts/capture-pseudolocale-screens.ps1`.
- `PROJECT_CONTEXT.md` (root, gitignored, dated 2026-06-27) is stale in every material respect — 100 Kotlin files vs 168, Room v10 vs v13, v1.7.12 vs v1.7.33 — and `AGENTS.md` explicitly forbids the file. It should be deleted.

**UI/UX findings** (from `fastlane/metadata/android/en-US/images/phoneScreenshots/`, v1.7.30+ light-first system)
- **Home leads with plumbing, not outcomes.** The hero is `3/3 Core setup · 8 Engines · Ready Database`; below it a five-row Setup Checklist that stays fully expanded and reads "Setup complete / Ready ×5" forever. A blocker's home screen should lead with "N calls blocked this week". The completed checklist should collapse to one line.
- **Home and Settings render the same content twice.** The Home "Setup Checklist" and the Settings "Permissions & access" group list the same four capabilities with the same "Ready" labels. Settings additionally shows "Access checks ready: 2/2" above four rows — the count and the list disagree with no explanation.
- **Icon colour is decorative, not semantic.** Home shows seven accent hues (green shield, green shield-outline, blue check, blue shield, green check, purple phone, teal layers, blue bell) with no mapping to state; Settings is all-green except a blue globe. Verdict must never be signalled by colour alone (Android accessibility principles; WCAG 1.4.1), and inconsistent hue spends the signal.
- **"Open app settings" in Settings is a bare section header with no row beneath it** — an action styled as a group label, followed by a divider and the next group. It reads as an empty section.
- **Activity → Blocked has ~150 dp of unexplained dead space** between the tab strip and the filter chips, and two unlabelled icon-only actions sit at the end of the chip row, one of which is a red destructive control (bulk delete) adjacent to filters.
- **Lookup leads with a "100 / SPAM" gauge and "CallShield is 100% confident this number should be blocked."** This is the score-first pattern Gmail and Defender deliberately avoid; the causal sentence ("Detection: Manual block") is below the fold. A user's own manual block being reported back as 100% model confidence is actively misleading.
- **`Spam numbers loaded: 32624`** on Home is an unformatted integer — no locale grouping separator.
- No landscape, `sw600dp` or foldable resources exist; dynamic type is handled by five ad-hoc `fontScale >= 1.5f` branches rather than reflowing layout.

## Platform, Dependency, and Distribution Signal

**Android 16/17 (beyond the two defects above)** — no behavior change to `CallScreeningService` is documented for either release; `getCallerNumberVerificationStatus()` (API 30) remains the entire STIR/SHAKEN surface, and `ACTION_POST_CALL` remains the only platform spam-report hook. Android 16 tightened JobScheduler quotas by standby bucket and now counts jobs started while TOP or under a foreground service against quota, with a new `STOP_REASON_TIMEOUT_ABANDONED` — a plausible new cause of missed 30-minute and 6-hour syncs that the app currently cannot distinguish from "never scheduled". Android 16 QPR2 added Quick Settings tile categories; `CallShieldTileService` declares no `TILE_CATEGORY` meta-data (`AndroidManifest.xml:106-110`) and therefore lands in the generic "From apps you installed" bucket. 16 KB page size is Play-only and native-code-only — no action for a pure Kotlin app. targetSdk 37 additionally makes `ACCESS_LOCAL_NETWORK` a mandatory runtime permission, which would newly implicate the deliberately-supported LAN-hosted external blocklist case, removes the resizability opt-out for `sw >= 600dp`, and turns on Certificate Transparency by default (relevant to the pinned endpoints). None of that is worth taking in 2026 — see *Rejected Ideas*.

**Dependency currency** (latest stable as of 2026-08-04, sources under *Sources → Dependencies*) — already current and needing no action: Room 2.8.4, WorkManager 2.11.2, DataStore 1.2.1, OkHttp 5.4.0, Moshi 1.15.2, appcompat 1.7.1, ktlint 1.8.0 / gradle-plugin 14.2.0, and every `androidx.test` artifact. Stale but reachable **without** the blocked AGP-9 tranche, because AGP maintains a current 8.x line and KSP decoupled from the Kotlin version at 2.3.0: AGP 8.13.2, Kotlin 2.4.10, KSP 2.3.11, core-ktx 1.19.0, lifecycle 2.11.0, activity-compose 1.13.0, navigation 2.9.8, Hilt 2.60.1, androidx.hilt 1.4.0, Robolectric 4.16.1, Kover 0.9.9, kotlinx-serialization-json 1.11.0 (debug/test scopes only here), Compose BOM 2026.06.01 (patch-level — identical ui/foundation 1.11.4 and material3 1.4.0). Compose 1.12 is the first release requiring compileSdk 37 + AGP 9, so the Testing-v2 migration already tracked in `Roadmap_Blocked.md` is the real gate on that BOM line, not the BOM itself. Robolectric 4.16.1 covers SDK 36; SDK 37 needs the 4.17 beta.

**Distribution and update channel** — GitHub Releases is currently the only channel, and it notifies nobody: no `releases/latest` consumer exists anywhere in `app/src/main/java` (`GitHubDataSource.checkForUpdate` at line 231 is the spam-database SHA check, not an app-version check). F-Droid, IzzyOnDroid and Obtainium each notify their own users, so the gap is specific to direct downloaders — and `api.github.com` is already a pinned host, so closing it adds no new network surface. Note IzzyOnDroid forbids self-updating without opt-in, so this must stay a notification, never an installer. Separately, Accrescent is phasing in domain-ownership verification for the application ID's domain on new submissions; `com.sysadmindoc.callshield` maps to no domain the project controls, and an application ID cannot be changed after users install — see *Open Questions*. Google's Developer Verification enforcement begins 2026-09-30 in Brazil, Indonesia, Singapore and Thailand for participating stores only; unregistered apps stay installable there via ADB or the "advanced flow", with friction. F-Droid's reproducible-build path uses apksigcopier to re-apply the *developer's* signature, so a single stable release key serves GitHub, F-Droid, IzzyOnDroid and Accrescent simultaneously — which is what makes the unsigned-artifact defect a four-channel blocker rather than a cosmetic one.

**On-device ML, the viable path** — ML Kit GenAI and Gemini Nano are AICore-bound and blocked from background use, so they cannot classify an arriving SMS; MediaPipe's LLM Inference API is maintenance-only. LiteRT's `CompiledModel` API is documented as operating independently of Google Play services with GPU/NPU delegates across Qualcomm, MediaTek, Tensor and Samsung, which makes a **bundled** text classifier the only GMS-free option. Size evidence is favourable — published quantized smishing classifiers land around 127 KB with no accuracy loss, and BiLSTM SMS-spam TFLite models sit under 1 MB. Evasion is an active research area, so such a model belongs below every deterministic layer and must never hard-block alone.

**Telephony-specific accessibility** — two requirements a generic audit will miss: RTT (Android 9+, replaces TTY, shares the voice number, supports 911) must not be disrupted by screening or the overlay, and Live Caption does not caption live phone calls, so no screening UI may assume system captions exist. WCAG 2.2's new 2.5.7 Dragging Movements directly implicates swipe-to-unblock and swipe-to-delete; 2.5.8 Target Size (Min) implicates the paired block/allow controls in a log row. W3C COGA's plain-language guidance argues against surfacing jargon such as a bare "attestation C" in a user-facing reason.

**Consciously excluded from this pass** — *plugin ecosystem*: the app has no plugin surface, and the nearest real analogue (implementing SpamBlocker's SMS-screening bound-service protocol as a provider) is an Open Question, not a roadmap item, until the SMS strategy is settled. *Multi-user / accounts*: excluded by the locked philosophy; Android Work Profile awareness remains tracked as B.E.2. *iOS/KMP*: unchanged since the last pass and still Phase 4.1.

## Rejected Ideas

| Idea | Source | Why rejected |
|---|---|---|
| ML Kit GenAI / Gemini Nano for SMS classification | SpamBlocker issue #642; developer.android.com/ai/gemini-nano | Built on AICore (GMS), ~30 flagship devices, and **foreground-use-only** (`ErrorCode.BACKGROUND_USE_BLOCKED`) — structurally unusable for SMS-arrival classification, and breaks the FOSS/no-GMS constraint. |
| MediaPipe LLM Inference API | ai.google.dev/edge/mediapipe | Maintenance-only; Google directs migration to LiteRT-LM. Do not build on it. |
| SpamBlocker-style user-editable JSON workflow engine | SpamBlocker wiki "Regex Workflow Templates" | It is SpamBlocker's moat *and* its adoption tax; "too complex, unable to understand anything" is the recurring F-Droid-forum complaint about the category. Contradicts nothing in the philosophy but contradicts the persona. |
| PhoneBlock SIP answerbot / any engage-the-caller bot | github.com/haumacher/phoneblock | TCPA and state recording-consent exposure; already reject-leaning as B.?.1. |
| Moving to targetSdk 37 in 2026 | developer.android.com/about/versions/17/behavior-changes-17 | Buys nothing (not on Play; Play's own deadline is Aug 2027) and costs *all* standard-format OTP SMS behind a 3-hour withholding wall. Stay at 36. |
| Glance widget rewrite now (existing B.U.9) | developer.android.com/jetpack/androidx/releases/glance | Glance stable is still **1.1.1**; 1.2.0 has sat at `rc01` since 2025-12-03 with no promotion. Widget previews and adaptive sizing all require the RC. Defer, don't rc-pin. |
| Upgrading Kotlin to close CVE-2026-53914 | GHSA-r937-wjx7-w2jp; kotlinlang.org/docs/releases.html | The fix lands in Kotlin **2.4.20, which is still Beta2** (GA slated Sept 2026). No stable upgrade closes it today. The `Roadmap_Blocked.md` AGP-9 tranche's "Kotlin must reach ≥ 2.4.20" precondition is currently unsatisfiable — the documented remote/shared-build-cache mitigation remains the only answer, and it is sound because the attack requires poisoned cache metadata as untrusted input. |
| Blocking private/loopback hosts in `ExternalBlocklistParser` | prior pass, retained | Still correct: the URL is user-entered and unreflected; blocking RFC1918 breaks the legitimate LAN-hosted-blocklist case. |
| Adding a spam-tolerance "aggressiveness" slider as a new top-level control | RoboKiller, Call Control feature pages | The mechanism already exists as `BlockingProfiles` + per-category call actions + configurable thresholds. This is a naming/IA problem, not a feature gap; fold it into the Settings IA work rather than adding a fourth overlapping control. |

## Sources

**OSS competitors**
- https://github.com/aj3423/SpamBlocker · /releases/tag/v5.5 · v5.7 · v5.9 · v5.10 · v5.12 · v5.13 · v5.14 · /wiki/SMS-Screening-Protocol · /wiki/Regex-Workflow-Templates · /issues/362 · /issues/587 · /issues/642
- https://github.com/haumacher/phoneblock · /blob/master/INTEGRATIONS.md
- https://codeberg.org/cbouvat/saracroche-android
- https://github.com/KerballOne/SpamBlocker-Extended · https://f-droid.org/en/packages/dev.kerballone.spamblocker/
- https://f-droid.org/packages/me.lucky.silence/ · https://gitlab.com/xynngh/YetAnotherCallBlocker
- https://github.com/FossifyOrg/Messages · https://github.com/FossifyOrg/Phone

**Android platform**
- https://developer.android.com/about/versions/16/behavior-changes-all · /16/behavior-changes-16
- https://developer.android.com/about/versions/17/behavior-changes-17 · /17/behavior-changes-all · /17/release-notes
- https://developer.android.com/develop/connectivity/telecom/dialer-app/screen-calls · /prevent-spoofing
- https://developer.android.com/develop/ui/compose/accessibility/testing · https://developer.android.com/guide/topics/ui/accessibility/principles
- https://developer.android.com/develop/ui/views/quicksettings-tiles
- https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile · /measure-baselineprofile
- https://developer.android.com/guide/practices/page-sizes

**Dependencies and advisories**
- https://github.com/gradle/gradle/security/advisories/GHSA-mqwm-5m85-gmcv · /GHSA-w78c-w6vf-rw82 · https://services.gradle.org/versions/current
- https://github.com/advisories/GHSA-r937-wjx7-w2jp · https://nvd.nist.gov/vuln/detail/CVE-2026-53914
- https://developer.android.com/build/releases/gradle-plugin · https://kotlinlang.org/docs/releases.html · https://github.com/google/ksp/releases/tag/2.3.11
- https://developer.android.com/develop/ui/compose/bom/bom-mapping · https://developer.android.com/jetpack/androidx/releases/compose · /glance · /window · /benchmark · /work · /lifecycle · /activity · /navigation · /core
- https://github.com/google/dagger/releases/tag/dagger-2.60 · https://github.com/robolectric/robolectric/releases · https://github.com/Kotlin/kotlinx-kover/releases/tag/v0.9.9
- https://github.com/google/libphonenumber/releases/tag/v9.0.36 · https://repo1.maven.org/maven2/io/michaelrocks/libphonenumber-android/maven-metadata.xml
- https://developers.google.com/edge/litert · https://developers.google.com/ml-kit/genai

**Distribution**
- https://f-droid.org/en/docs/Inclusion_Policy/ · /Inclusion_How-To/ · /Reproducible_Builds/
- https://izzyondroid.org/docs/general/AppInclusionPolicy/
- https://accrescent.app/docs/guide/publish/requirements.html · https://blog.accrescent.app/posts/android-developer-verification/
- https://developer.android.com/developer-verification · https://f-droid.org/2026/02/24/open-letter-opposing-developer-verification.html

**Regulatory, harm and accessibility**
- https://www.law.cornell.edu/cfr/text/47/64.1200 (§64.1200(k) blocking redress)
- https://www.acma.gov.au/combating-phone-scams · https://www.bandwidth.com/support/en/articles/12823054-what-can-i-do-if-my-calls-are-improperly-labeled-as-spam-or-scam
- https://www.w3.org/TR/WCAG22/ · https://www.w3.org/TR/coga-usable/introduction.html · https://source.android.com/docs/core/connect/rtt
- https://dontkillmyapp.com/xiaomi · https://forum.f-droid.org/t/best-call-blocker/25033

**Explainability patterns**
- https://docs.pi-hole.net/database/query-database/ · https://github.com/gorhill/uBlock/wiki/The-logger · https://help.nextdns.io/t/q6hmvc6/i-found-a-domain-blocked-by-error · https://support.microsoft.com/en-au/windows/protection-history-f1e5fd95-09b4-46d1-b8c7-1059a1e09708

**Commercial**
- https://blog.google/security/staying-one-step-ahead-strengthening-androids-lead-in-scam-protection/ · https://techcrunch.com/2026/03/12/truecallers-now-lets-you-hang-up-on-scammers-on-behalf-of-your-family/ · vendor pricing pages for Nomorobo, Call Control, RoboKiller, YouMail, Hiya

## Open Questions

1. **Which release key signs public artifacts from now on?** `callshield-release.jks` exists locally and is gitignored; `docs/fdroid/…yml` already pins `AllowedAPKSigningKeys: d179d0da…`; `CLAUDE.md` records that the phone's installed v1.7.25 has "an unavailable signing key". Whether `d179d0da…` is the fingerprint of the extant keystore determines whether existing installs can upgrade in place or whether every user must uninstall. Nothing in the repo answers this. Blocks the P0 signing item.
2. **SMS strategy: default-SMS role, screening-provider protocol, or accept degradation?** Android 17 withholds OTP-format SMS for 3 hours from non-default handlers; Android 16 already removed the receiver ordering guarantee; and the "SMS Screening Provider mode" item in `Roadmap_Blocked.md` describes it as *"Android's SMS screening ContentProvider protocol"* — that is wrong. No such platform API exists; it is SpamBlocker's own bound-service/Messenger contract (`sms.screening.provider.PublicSMSScreeningService`), implemented by SpamBlocker and QUIK SMS. That correction changes the item's cost and its ceiling (it only helps users of participating SMS apps) and should be settled before either path is staffed.
3. **Does the project accept a domain-bound application ID?** Accrescent is phasing in domain-ownership verification for the app ID's domain on new submissions; `com.sysadmindoc.callshield` maps to no domain the project controls. Renaming the application ID after users have installed is unrecoverable, so this must be decided before, not after, the first store submission.
4. **What is the acceptable false-positive budget, and who adjudicates it?** Several proposed items (region prefix ranges, campaign velocity, an SMS text classifier) trade recall for precision, and there is currently no stated target to hold them to.
5. **Is `+15559876543`-class synthetic traffic a test fixture or live pollution?** 29 of 35 queued reports are fictional 555 numbers. If a device or script is still emitting them, draining the queue fixes the symptom and not the source.

## Verification environment

The Gradle build could not run as checked out: `local.properties` pointed at `D:\tools\android-sdk`, which no longer exists (that machine was retired), and `JAVA_HOME` pointed at a JDK 21.0.11 path superseded by 21.0.12. Both were corrected locally (`sdk.dir=C:/Users/--/AppData/Local/Android/Sdk`, `JAVA_HOME=…/jdk-21.0.12.8-hotspot`) to produce the test run cited above. `local.properties` is gitignored and machine-local; note the escaping trap — a Java `.properties` file eats single backslashes, so a Windows path must use forward slashes or doubled backslashes or Gradle fails with `IOException: The filename, directory name, or volume label syntax is incorrect` before any task resolves.
