# Research — CallShield
Date: 2026-08-10 — replaces all prior research.

## Executive Summary

CallShield is a privacy-first, offline-capable Android 10+ call, SMS, and RCS screening app: a 15+ layer on-device checker pipeline, Room-backed local data, sharded GitHub feeds, optional notification screening, and an anonymous report Worker. Its strongest shape is the safety boundary around the five-second `CallScreeningService` path, fail-open behavior, local ML/rules, provenance-aware feed ingestion, and portable encrypted backups. The highest-value direction is now trust and scale at the data/release boundaries, not another detector: the Android unit suite passes on 2026-08-10, but the pipeline gate is red, UI data access still materializes unbounded tables, and feed metadata is self-consistent rather than authenticated or rollback-resistant.

Priority opportunities:

1. **Now / P0:** repair the release-drift fixture and add the current versionCode-62 Fastlane changelog so the release gate is green from a clean checkout.
2. **Now / P1:** page the spam database and blocked log and move statistics to bounded Room aggregates; the current UI reads entire tables into `List` state.
3. **Now / P1:** reject replayed or downgraded sharded-feed manifests before the transactional Room replacement.
4. **Existing / P1:** land the first real locale and convert the remaining `BlockReasoning`/SMS-burst explanation templates to resource-backed structured reasons; this belongs to roadmap 4.7 and B.O.1, not a duplicate addition.
5. **Next / P2:** automate Gradle dependency-graph and advisory review in CI; the repository has a manual advisory manifest but no `.github/workflows` gate.
6. **Under consideration:** cryptographically authenticate the feed manifest with an operator-held trust root. TUF/Sigstore make the design feasible, but key custody and publication are operator-gated in `Roadmap_Blocked.md`.

## Product Map

### Core workflows

- Setup requests the minimum Android roles/special access, checks protection health, and schedules local feed/hot-list work.
- Incoming calls enter `CallShieldScreeningService`, take contact/emergency/allow floors and the ordered checker pipeline, then return block/allow/silence within the platform deadline; unknown allowed calls may show the local caller-ID overlay and after-call feedback.
- SMS is screened through `SmsReceiver`; RCS and other supported notification sources go through `RcsNotificationListener`; content, sender, URL, burst, and local-rule evidence remain on device.
- GitHub raw data, source snapshots, hot feeds, and bundled assets are validated before Room persistence; remote failure retains usable local data and can fall back to the bundled database.
- Users manage exact/prefix/wildcard/keyword/whitelist rules, inspect activity and explanations, export/redress logs, and create encrypted portable backups without an account.

### Personas

- Privacy-first Android/F-Droid users who reject accounts, telemetry, ads, subscriptions, and cloud audio.
- Power users who need regional prefixes, custom rules, schedules, temporary decisions, and an auditable reason for every verdict.
- People who need low-latency call protection and SMS/RCS screening without replacing their dialer or SMS app.
- Maintainers and data contributors who regenerate licensed feeds and need deterministic, attributable releases.

### Platforms and distribution

- Android API 29 minimum, target/compile SDK 36, Kotlin/JVM 17; telephony is optional and no GMS-only path is required. The project uses Compose Material 3, Room, DataStore, WorkManager, OkHttp, Moshi, and Hilt (`app/build.gradle.kts`, `gradle/libs.versions.toml`).
- Distribution is prepared around GitHub releases, Fastlane metadata, reproducible-build documentation, and a future F-Droid submission. F-Droid publication, signer verification, and other store registrations remain operator-blocked in `Roadmap_Blocked.md`.
- There is no iOS, Wear OS, carrier SDK, plugin SDK, or multi-user product surface; those are either long-horizon roadmap entries or rejected for the current scope.

### Integrations and data flows

- Android Telecom calls `CallShieldScreeningService`; Android SMS broadcasts feed `SmsReceiver`; notification access feeds `RcsNotificationListener`; contacts, call log, overlay special access, and optional notification permission are capability inputs.
- Python importers consume public/licensed FTC, FCC, PhoneBlock, Saracroche, Nomorobo-IRS, URL, and community inputs into manifests and generated JSON; the Android app consumes GitHub raw monolithic or content-addressed shard URLs.
- `SyncRepository` validates JSON schema, SHA-256 bytes, row counts, shard IDs, and then performs one Room transaction. DataStore records the last remote SHA and shard hashes; no feed signature or trusted public key is present.
- The Cloudflare Worker accepts rate-limited anonymous reports and diagnostics; credentials are not required by the client and raw SMS/audio is not uploaded.

## Competitive Landscape

- **SpamBlocker** — a mature Android 10+ FOSS blocker with contacts, regex, STIR/SHAKEN, repeated-call and dialed-number rules, instant queries, reporting, offline APKs, and a reproducible signing fingerprint. Learn from its narrow permission story, offline release path, and explicit SMS limitation; avoid making online queries or a database service part of the screening decision. [Repository](https://github.com/aj3423/SpamBlocker)
- **SpamBlocker Extended** — adds RCS/Signal/WhatsApp/email notification screening, per-app rules, schedules, calendar/geolocation/carrier context, import/export, and broader call actions. Learn from per-source configuration and notification explanations; avoid its large special-access/permission surface because CallShield’s privacy promise is stronger. [Repository](https://github.com/KerballOne/SpamBlocker-Extended)
- **Silence** — intentionally simple local unknown-caller blocking with contacts, groups, repeated calls, message-derived numbers, regex, per-SIM support, and many translations. Learn from an explicit default policy, regional/per-SIM affordances, and translation breadth; avoid replacing CallShield’s evidence-aware pipeline with a single unknown-caller policy. [Repository](https://github.com/x13a/Silence)
- **Yet Another Call Blocker** — offline crowdsourced data, local wildcards, caller notifications, advanced pre-ring mode, and incremental daily updates. Learn from delta-oriented update delivery and visible caller context; treat its reports of dead external services and country-code false positives as warnings against unowned dependencies and weak normalization. [Repository](https://gitlab.com/xynngh/YetAnotherCallBlocker)
- **Saracroche** — a regional, local-database Android/iOS blocker with reporting, regular updates, and an explicit no-call-data-collection stance. Learn from region-specific curation and attribution; avoid assuming a France-only corpus generalizes to CallShield’s broader target. [Project](https://codeberg.org/cbouvat/saracroche-android)
- **Junkboy** — an Android SMS filter combining on-device TensorFlow Lite classification, keywords/regex, sender allowlists, OTP/transaction categories, conversation views, notification controls, testing, and export. Learn from separating transaction/verification traffic from junk and from a built-in filter test; avoid becoming a default SMS client or foreground-service-heavy inbox because Android role semantics and the project’s scope do not support that trade. [Repository](https://github.com/ovehbe/junkboy)
- **Truecaller, Hiya, RoboKiller, and Google Phone/Messages** — commercial/platform products paywall or cloud-enable caller identity, assistant screening, answer bots, real-time scam warnings, AI summaries, and community reputation. Learn from prominent safety labels, reporting/redress, and contextual explanations; avoid cloud audio/content processing, ads/subscriptions, and identity databases that require uploading contacts or credentials. [Truecaller](https://www.truecaller.com/spam-blocking) · [Hiya](https://www.hiya.com/products/apps/hiya-ai-phone) · [RoboKiller](https://support.robokiller.com/hc/en-us/articles/17475620993300-What-is-Freemium) · [Google](https://blog.google/security/new-ai-powered-scam-detection-features/)
- **uBlock Origin, Pi-hole, and Rspamd** — adjacent filtering systems expose the exact source/rule that matched, query history, allow/deny overrides, fuzzy/campaign evidence, and controlled learning. Learn from source-attributed logs and quarantine/review flows; avoid importing their server/admin/plugin complexity into a five-second, on-device call path. [uBlock logger](https://github.com/gorhill/uBlock/wiki/The-logger) · [Pi-hole query database](https://docs.pi-hole.net/database/query-database/) · [Rspamd](https://docs.rspamd.com/)

## Security, Privacy, and Reliability

- **Verified — release gate is currently red.** `./gradlew :app:testDebugUnitTest` passes, but `./gradlew verifyPipelineTests` fails in `scripts/test_release_drift.py::test_checkout_is_synchronized`: the test supplies `now=2026-08-10T12:00:00Z`, the ignored source snapshot is `2026-08-10T13:43:08Z`, and `fastlane/metadata/android/en-US/changelogs/62.txt` is absent while `app/build.gradle.kts` declares versionCode 62/versionName 1.7.34. This is a deterministic repository defect, separate from the operator-blocked live release/count drift.
- **Verified — unbounded local materialization.** `SpamDao.getAllSpamNumbers()` returns every row ordered by reports, `MainViewModel.allSpamNumbers` exposes that `List`, and `DatabaseTabContent` passes it to `LazyColumn` (`SpamDao.kt:36-37`, `MainViewModel.kt:141-144`, `BlocklistScreen.kt:1198-1215`). The blocked log has the same shape (`SpamDao.kt:209-216`, `BlockedLogScreen.kt:69-108`), while `StatsScreen.kt:59-153` repeatedly groups and scans the entire unbounded log. The database already contains 51,463 spam numbers according to `README.md`, and auto-cleanup defaults to off (`SettingsRepository.kt:146`).
- **Verified — feed integrity is not feed authenticity.** `scripts/spam_shards.py` publishes SHA-256 descriptors and `SyncRepository.kt:287-350` validates every fetched body before one transaction, which is good corruption/partial-update protection. The checked code has no detached signature, trusted public key, expiry, or comparison against the stored `databaseVersion`; a compromised writable feed could therefore publish a self-consistent manifest, and a stale valid manifest can be accepted if the remote SHA changes. [TUF’s metadata model](https://theupdateframework.github.io/specification/v1.0.28/) is the relevant design reference.
- **Verified — dependency advisory posture is manual.** `scripts/release_advisories.json` contains three reviewed advisories, including Kotlin `GHSA-r937-wjx7-w2jp`/CVE-2026-53914, and `gradle.properties` documents the no-shared-build-cache mitigation. The repository has no `.github/workflows` and no automated dependency submission or scheduled advisory review. GitHub supports Gradle lockfile/dependency submission, so the gap is operational rather than a missing runtime capability. [Advisory](https://github.com/advisories/GHSA-r937-wjx7-w2jp) · [Gradle lockfile support](https://github.blog/changelog/2025-06-24-dependabot-support-for-gradle-lockfiles-is-now-generally-available/)
- **Likely / needs live validation — platform capability drift.** Android documents a five-second call-screening response window, default-SMS-only delivery semantics for some broadcasts, notification-listener special-access limitations, and Android 17 SMS delays for unregistered apps. CallShield already has a fail-open call deadline and a blocked Android 17 SMS strategy item; it must not describe advisory `RECEIVE_SMS`/notification observation as guaranteed suppression. [Call screening](https://developer.android.com/reference/android/telecom/CallScreeningService) · [SMS intents](https://developer.android.com/reference/android/provider/Telephony.Sms.Intents) · [Android 17 changes](https://developer.android.com/about/versions/17/behavior-changes-all) · [Notification listener](https://developer.android.com/reference/android/service/notification/NotificationListenerService.html)
- **Existing safeguards to preserve:** no account/telemetry requirement, local ML and rules, explicit response gating, contact/emergency/fail-open floors, encrypted portable backups, feed quarantine/empty-feed protection, redacted SMS logging, source provenance, and atomic Room replacement. These are consistent with Android’s offline/local privacy model and the project rules in `CLAUDE.md`.
- **Recovery needs:** keep the last known-good Room dataset on any manifest/signature/version failure; expose a warning and retry state without blocking calls; retain the bundled snapshot as a first-install/offline fallback; make export reads explicit and streamable so history remains recoverable even after UI pagination; test Room migration/rollback for every new DataStore or entity field.

## Architecture Assessment

### Boundaries and refactors

- Preserve the current `Compose → MainViewModel → use case → repository facade → Room/DataStore` boundary. Add a `FeedManifestVerifier`/`FeedVersionPolicy` seam under `SyncRepository` before adding cryptographic verification, so rollback tests do not depend on OkHttp or GitHub.
- Replace screen-facing `Flow<List<...>>` methods with Room `PagingSource`/`Pager` or an equivalent keyset query. Android’s supported architecture is repository `PagingSource` → ViewModel `Pager` → Compose `collectAsLazyPagingItems()`. [Paging overview](https://developer.android.com/topic/libraries/architecture/paging/v3-overview)
- Add DAO aggregate queries for the statistics windows already displayed (day/week/month, media type, reason code, hour, and top offenders). Keep full-log reads only behind explicit export/redress actions.
- Keep `BlockReasoning` as a domain explanation model, but return reason keys and typed arguments instead of English sentences; resolve them in the UI/resources layer. This is already identified by `docs/hardcoded-string-audit.md` and should land with existing B.O.1 explainability and 4.7 localization work.
- Refactor candidates are `SettingsScreen.kt` (~2,100 lines), `BlocklistScreen.kt` (~1,826), `MainViewModel.kt` (~1,065), `BackupRestore.kt` (~1,364), `CallerIdOverlayService.kt` (~1,040), and the long `SyncRepository.syncFromGitHub()` path. Extract only around tested seams; the five-second service and direct-boot code should not be cosmetically reorganized without device coverage.

### Test and documentation gaps

- Add deterministic release fixtures for timestamp, Fastlane changelog, source count, and advisory dispositions; the current hard-coded clock makes a fresh generated snapshot fail for the wrong reason.
- Add large-fixture Room tests for page boundaries, invalidation after sync, filters/grouping, and SQL aggregate correctness; include an explicit export test proving pagination does not truncate redress data.
- Add sync tests for lower manifest versions, older `updated` values, same-version digest changes, malformed descriptors, and remote failure after a good local database.
- Add CI coverage for `verifyPipelineTests`, Gradle lock/dependency submission, release metadata, and advisory disposition. Pin third-party action commits and keep the Android runtime network-free.
- The README test badge/count and historical roadmap counts are not generated from the current test report; that mismatch is already tracked as an operator/documentation issue in `Roadmap_Blocked.md` and should not be duplicated here.

### Category decisions

- **Security:** feed replay/authenticity and dependency review are the new actionable gaps; release signing, provider credentials, and Google verification remain blocked.
- **Accessibility:** Compose semantics, headings, custom actions, and 48dp controls are substantially covered. Dynamic type, contrast, predictive back, and device TalkBack validation remain in roadmap 4.6; no duplicate addition is warranted. Android recommends 48dp targets. [Compose accessibility](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)
- **i18n/l10n:** English-only resources and remaining hardcoded decision templates are real gaps; existing roadmap 4.7/B.O.1 owns them. Use Android resources and pseudolocale checks before adding more locales. [Localization](https://developer.android.com/guide/topics/resources/localization) · [Pseudolocales](https://developer.android.com/guide/topics/resources/pseudolocales)
- **Observability:** local diagnostics, source health, decision reason codes, and Worker diagnostics exist; live Worker deployment/verification is operator-blocked. Keep new failure states visible but never send raw call/SMS content.
- **Testing:** release, paging, aggregate, and rollback tests are actionable; Android 16/17 OEM behavior, STIR carrier data, and F-Droid artifact verification require the blocked device/operator work.
- **Docs/distribution:** release metadata is the immediate local defect; F-Droid, IzzyOnDroid, Accrescent, and stable signing are already blocked items.
- **Plugin ecosystem:** intentionally excluded. A plugin API would expand permissions, update trust, and five-second latency risk without a demonstrated in-repo extension boundary; source adapters should remain maintained, typed pipeline modules.
- **Mobile/multi-user:** iOS/Wear/carrier integration and Work Profile separation are existing long-horizon or blocked entries, not current Android-core gaps.
- **Offline/resilience:** strong fallback exists; replay protection is the missing layer. No cloud gate or cloud audio is recommended.
- **Migration/upgrade:** all new Room fields/entities need explicit migrations; the AGP/Kotlin/Hilt/Moshi upgrade and Compose Testing v2 are already blocked dependency tranches. WorkManager/Room/Compose are otherwise current enough for this plan. [WorkManager releases](https://developer.android.com/jetpack/androidx/releases/work) · [Room releases](https://developer.android.com/jetpack/androidx/releases/room)

### Prioritization rationale

| Recommendation | Tier | Impact | Effort | Risk/dependencies | Novelty |
|---|---|---:|---|---|---|
| Repair release gate and current metadata | Now / P0 | 5 | S | Low; no external dependency | Parity/release hygiene |
| Page tables and aggregate statistics | Now / P1 | 5 | L | Medium; Room/UI/export contract tests | Parity with Android large-list practice |
| Reject feed replay/rollback | Now / P1 | 4 | M | Medium; define legitimate rollback policy | Parity with update-security practice |
| Automate dependency/advisory CI | Next / P2 | 3 | M | Medium; pinned action maintenance | Parity |
| Signed feed trust root | Under consideration | 5 | L | High; operator key custody/publication | Leapfrog trust, but operationally blocked |

## Rejected Ideas

- **Cloud answer bots, call transcription, or voice cloning:** Truecaller/Hiya/RoboKiller monetize or cloud-enable these features, but they contradict `CLAUDE.md`’s no-cloud-audio rule, the offline-first contract, and the five-second response budget. [Truecaller](https://www.truecaller.com/call-screening) · [Hiya](https://www.hiya.com/products/apps/hiya-ai-phone) · [RoboKiller](https://apps.apple.com/us/app/robokiller-spam-call-blocker/id1022831885)
- **Make CallShield the default SMS client or delete junk from the system inbox:** Android’s `SMS_DELIVER`/provider semantics reserve full interception and deletion for the default SMS role; community and Stack Overflow reports show that a non-default receiver cannot reliably prevent the default app’s inbox/notification behavior. This would contradict the current non-replacement product promise. [Android SMS intents](https://developer.android.com/reference/android/provider/Telephony.Sms.Intents) · [SMS interception constraints](https://stackoverflow.com/questions/56358448/how-practical-is-it-to-intercept-certain-sms-messages-in-android-but-allow-others-to-be-handled-by-the-built-in-messaging-app)
- **A generic plugin marketplace or user-installed checker code:** uBlock’s filter ecosystem is valuable for its own domain, but arbitrary CallShield plugins would need permission isolation, deterministic ordering, update provenance, and strict latency controls that the current Android module does not provide. Keep source adapters and checkers reviewed in-tree. [uBlock filter lists](https://github.com/gorhill/uBlock/wiki/Dashboard%3A-Filter-lists)
- **Federated learning or an LLM as the next detector:** SpamDam and SpaLLM-Guard show research value, but they add collection, model/update governance, adversarial drift, and resource/privacy costs that conflict with CallShield’s no-account, no-cloud, deterministic local path. Existing roadmap 5.2 already records federated learning as a long-horizon experiment. [SpamDam](https://arxiv.org/abs/2404.09481) · [SpaLLM-Guard](https://arxiv.org/abs/2501.04985)
- **Full signed/TUF feed deployment in the current autonomous tranche:** TUF/Sigstore are technically appropriate, but the production trust root, signer custody, release process, and compatibility decision are external operator inputs already represented by the stable-signing block. Implement rollback checks now; keep signature deployment under consideration until that authority exists. [TUF](https://theupdateframework.github.io/specification/v1.0.28/) · [Sigstore verification](https://docs.sigstore.dev/cosign/verifying/verify/)
- **Near-term iOS/Wear/carrier SDK or paid tier:** The existing roadmap already marks these as XL/partner/product-policy work, and none improves the current Android offline safety boundary without platform contracts or business decisions. See the existing Phase 4/5 entries in `ROADMAP.md`.

## Sources

### OSS competitors and lists

https://github.com/aj3423/SpamBlocker

https://github.com/KerballOne/SpamBlocker-Extended

https://github.com/x13a/Silence

https://gitlab.com/xynngh/YetAnotherCallBlocker

https://codeberg.org/cbouvat/saracroche-android

https://github.com/StefanIlchev/CallBlocker

https://github.com/ovehbe/junkboy

https://github.com/MASantos/NoPhoneSpam

https://fdroid.gitlab.io/jekyll-fdroid/categories/phone-sms/

### Commercial and platform products

https://www.truecaller.com/spam-blocking

https://www.truecaller.com/call-screening

https://www.hiya.com/products/apps/hiya-ai-phone

https://apphelp.hiya.com/en/articles/43-make-call-screener-screen-calls-the-way-you-want

https://support.robokiller.com/hc/en-us/articles/17475620993300-What-is-Freemium

https://www.nomorobo.com/nomorobo-pricing/

https://blog.google/security/new-ai-powered-scam-detection-features/

https://www.ftc.gov/developer/api/v0/endpoints/do-not-call-dnc-reported-calls-data-api

### Community signal

https://www.reddit.com/r/fossdroid/comments/1tc8jsr/spamblocker_call_sms_configuration/

https://www.reddit.com/r/fossdroid/comments/1ftqqsq/whats_the_best_spam_sms_call_blocker/

https://www.reddit.com/r/privacytoolsIO/comments/io6tju/what_options_exist_for_blocking_spam_calls_that_also_respect_your_privacy/

https://forum.f-droid.org/t/best-call-blocker/25033

https://news.ycombinator.com/item?id=45015354

https://stackoverflow.com/questions/56358448/how-practical-is-it-to-intercept-certain-sms-messages-in-android-but-allow-others-to-be-handled-by-the-built-in-messaging-app

### Android, standards, and supply chain

https://developer.android.com/reference/android/telecom/CallScreeningService

https://developer.android.com/reference/android/provider/Telephony.Sms.Intents

https://developer.android.com/about/versions/17/behavior-changes-all

https://developer.android.com/reference/android/service/notification/NotificationListenerService.html

https://developer.android.com/topic/libraries/architecture/paging/v3-overview

https://developer.android.com/guide/topics/resources/localization

https://developer.android.com/guide/topics/resources/pseudolocales

https://developer.android.com/develop/ui/compose/accessibility/api-defaults

https://www.w3.org/TR/WCAG22/

https://www.rfc-editor.org/rfc/rfc8588.html

https://www.rfc-editor.org/rfc/rfc9795.html

https://www.rfc-editor.org/rfc/rfc9796.html

https://theupdateframework.github.io/specification/v1.0.28/

https://docs.sigstore.dev/cosign/verifying/verify/

https://f-droid.org/en/docs/Reproducible_Builds/

### Adjacent systems and architecture

https://docs.rspamd.com/

https://github.com/gorhill/uBlock/wiki/The-logger

https://github.com/gorhill/uBlock/wiki/Dashboard%3A-Filter-lists

https://docs.pi-hole.net/database/query-database/

https://github.com/lissy93/awesome-privacy/

https://github.com/awesome-selfhosted/awesome-selfhosted

### Academic and engineering research

https://research.google/pubs/crowd-sourced-call-identification-and-suppression/

https://arxiv.org/abs/2410.17361

https://arxiv.org/abs/2508.05276

https://arxiv.org/abs/2404.09481

https://arxiv.org/abs/2501.04985

https://github.com/cvl01/spam-call-analysis

### Dependencies and security

https://developer.android.com/jetpack/androidx/releases/work

https://developer.android.com/jetpack/androidx/releases/room

https://developer.android.com/blog/posts/whats-new-in-the-jetpack-compose-april-26-release?authuser=2&hl=en

https://developer.android.com/build/releases/agp-8-13-0-release-notes?hl=en

https://kotlinlang.org/docs/releases.html

https://square.github.io/okhttp/changelogs/changelog/

https://github.com/advisories/GHSA-r937-wjx7-w2jp

https://nvd.nist.gov/vuln/detail/CVE-2026-53914

https://github.blog/changelog/2025-06-24-dependabot-support-for-gradle-lockfiles-is-now-generally-available/

https://github.com/gradle/actions/blob/main/docs/setup-gradle.md

## Open Questions

None for the four active additions. Production feed signing, release-key ownership, store publication, carrier/STIR validation, authenticated providers, and Android 17 SMS policy remain external-input blockers already recorded in `Roadmap_Blocked.md`.
