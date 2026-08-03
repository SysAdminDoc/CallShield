# CallShield Research

Date: 2026-08-02
Scope: exhaustive product, competitor, source, platform, security, and dependency review. This replaces the previous research report.

## Executive summary

CallShield already has a strong local-first foundation: a Kotlin/Compose Android app, a priority-ordered multi-layer detector, Room-backed hot data, SMS/RCS handling, optional caller-ID overlay, a Python ingestion pipeline, and a Cloudflare community-report worker. The repository advertises 15+ detection layers and 51,463 bundled numbers. The next large accuracy gain is not to copy every available list into the APK. It is to make each signal attributable, time-bounded, license-safe, region-aware, and resistant to spoofed-number churn.

The highest-value additions are:

1. Preserve source, license, retrieval time, evidence type, and confidence for every imported row; apply decay and quarantine rather than treating a complaint as a permanent hard block.
2. Add permitted, incremental government feeds (FTC and FCC), privacy-preserving PhoneBlock lookups, and optional Nomorobo IRS feed support with explicit attribution. Keep commercial APIs and restricted regional services operator-gated.
3. Add number- and campaign-level features: E.164/line-type validation, unassigned/DNO ranges, STIR/SHAKEN/RCD evidence, prefix velocity, neighbor spoofing, number churn, and cross-source corroboration.
4. Treat SMS URLs, sender IDs, and brand impersonation as a separate verdict path. URLhaus, PhishTank, OpenPhish, Safe Browsing/Web Risk, Google’s on-device scam research, and regional sender-ID registries are useful signals but never single-source hard blocks.
5. Test the Android five-second screening contract under cold start, Room/provider failure, direct boot, OEM rebinding, and notification privacy changes in Android 15–17.

No code was implemented in this research pass. Existing uncommitted implementation changes in the D: working copy were preserved.

## Phase 0 — repository reconnaissance

### Product and architecture

- Single-module Android application (`app`) using Kotlin 2.2.21, Compose BOM 2026.05.00, Room 2.8.4, WorkManager 2.11.2, Hilt 2.58, OkHttp 5.4.0, Java 17, compile/target SDK 36, minimum SDK 29.
- `CallShieldScreeningService` is direct-boot aware and uses an explicit fail-open response plus a 4.5-second local budget. The service resolves providers, snapshots preferences, checks contacts, runs the detector, and records/alerts after the call.
- The manifest also exposes SMS receive, notification-listener/RCS, caller-ID overlay, boot, widget, tile, post-call, and WorkManager-related surfaces. These permissions and roles create separate privacy and OEM-survival test matrices.
- Detection is checker-based and already includes local rules, contacts/whitelist, temporary decisions, STIR/SHAKEN status, area/prefix/campaign heuristics, SMS content/URL checks, RCS notifications, SIT/robocall behavior, and on-device ML.
- `scripts/import_all_sources.py` currently handles FTC, FCC, optional PhoneBlock, Saracroche prefixes, optional Nomorobo IRS, opt-in HTTP ToastedSpam, and repository/community lists. The merged JSON is number-centric; source licenses, retrieval metadata, feed health, and evidence decay are not first-class fields.
- External blocklist subscriptions are user-controlled and parsed with size/format/redirect safeguards. This is a good extension point for signed or hashed feeds, not permission to scrape closed databases.
- The Cloudflare Worker has tests and a rate-limit design, but `worker/wrangler.toml` still contains `REPLACE_WITH_KV_NAMESPACE_ID`; deployment cannot be considered protected until an operator provisions and verifies the namespace.
- `CLAUDE.md` still describes v1.7.32/versionCode 60 while the dirty working copy contains v1.7.33/versionCode 61 changes. `PROJECT_CONTEXT.md` is older and non-authoritative; the F-Droid draft deliberately carries an older release metadata line plus a current-source line. Version consistency needs a single release gate.
- No production TODO/FIXME/HACK/XXX or obvious stub was found. The last 200 commits are dominated by community-report churn (120/200), followed by reliability/security fixes; this supports investing in report quality and campaign aggregation rather than merely increasing row count.

### Current strengths

- Offline operation and no mandatory account/cloud audio path.
- Explicit local allow/deny precedence, contact protection, direct-boot handling, provider failure fail-open behavior, and bounded untrusted text/URL processing.
- Shared Kotlin/Python/Worker number-normalization fixtures and pipeline gates.
- Existing URLhaus integration, report deduplication, source-specific importer guards, atomic pipeline writes, and release metadata checks.

### Material gaps

- A `reports` integer conflates independent complaints, source confidence, and freshness. FTC/FCC complaints are explicitly unverified; crowd databases can contain spoofed or stale numbers; curated feeds are not equivalent to a user report.
- There is no durable source registry with license, geographic coverage, update cadence, parser version, last-success/last-failure, and permitted redistribution mode.
- Importers do not yet provide a common k-anonymous lookup, signed-manifest, source-health, or TTL/decay contract.
- Existing campaign work is mainly local/in-memory and NPA-NXX oriented; large-scale number churn, neighbor spoofing, callback-number reuse, and cross-region campaigns deserve persistent evidence.
- RCS/SMS interception depends on Android notification and default-handler policies. Android 15 redacts OTP content from untrusted notification listeners and Android 17 delays SMS OTP access for non-default handlers; the classifier must degrade safely and explain missing evidence.
- The baseline full JVM run in this working copy still has a Direct Boot failure in `DirectBootScreeningStoreTest`; it is a pre-existing verification issue, not a reason to mask new work.

## Product and competitor map

| Surface | What the ecosystem demonstrates | CallShield implication |
|---|---|---|
| Offline rules | CallFilter, Hush, SpamBlocker, YetAnotherCallBlocker, and PhoneBlock support regex/prefix/wildcard rules and local operation. | Keep deterministic rules fast; expose rule provenance and an explainable preview. |
| Crowdsourced reputation | PhoneBlock, tellows, Should I Answer, 800Notes, whocall.me, and community reports combine votes/comments with changing caller behavior. | Store independent evidence and age it; never equate a popular label with verified identity. |
| Carrier-scale reputation | Hiya, Nomorobo, First Orion, TNS, RoboKiller, Truecaller, YouMail, and carrier products combine honeypots, reputation, STIR/SHAKEN, and behavioral analytics. | Optional remote adapters can enrich a local decision, but keys, privacy, terms, and outage behavior must remain operator-controlled. |
| Screening interaction | RoboKiller/YouMail use challenge, voicemail, transcription, or answer-bot flows. | Treat audio screening as an opt-in, future, on-device feature; do not introduce cloud audio as a hidden dependency. |
| SMS scam defense | Google Scam Detection, Singapore ScamShield, Scamwatch, URLhaus, PhishTank, OpenPhish, and Safe Browsing emphasize links, sender identity, urgency, impersonation, and conversation context. | Build a separate SMS/link verdict with redaction and hard-negative tests. |
| Verified identity | STIR/SHAKEN, PASSporT, Rich Call Data, Free Caller Registry, ACMA sender IDs, and regional numbering authorities provide positive provenance as well as abuse signals. | A trusted identity should reduce false positives, not grant unconditional allow. |

## Source inventory and integration assessment

### Production-safe or near-safe public sources

- FTC Do Not Call complaint API (`https://www.ftc.gov/developer/api/v0/endpoints/do-not-call-dnc-reported-calls-data-api`) and weekday dataset (`https://www.ftc.gov/policy-notices/open-government/data-sets/do-not-call-data`): paginated, rate-limited, unverified complaints with robocall/topic/date fields. Use as weighted evidence, preserve attribution, and back off on 429/403.
- FCC unwanted-call Socrata dataset (`https://opendata.fcc.gov/Consumer/Consumer-Complaints-Data-Unwanted-Calls/vakf-fz8e`): caller and advertiser fields, issue/date/category metadata, public export, and unverified reports. Deduplicate both phone fields and retain the role.
- FCC complaint guidance (`https://consumercomplaints.fcc.gov/hc/en-us/articles/115002234203-Unwanted-Calls-Texts-Phone`) and Robocall Mitigation Database (`https://www.fcc.gov/robocall-mitigation-database`, `https://docs.fcc.gov/public/attachments/DA-24-73A1_Rcd.pdf`): provenance and provider/mitigation context, not direct caller blocklists.
- Nomorobo IRS feed (`https://www.nomorobo.com/irs`): active IRS callback-scam CSV, updated about every 20 minutes, CC BY 4.0, but access requires an approved carrier/request. Keep the current optional adapter and attribution; do not scrape the paid full list.
- PhoneBlock API (`https://phoneblock.net/phoneblock/api`) and site (`https://phoneblock.net/phoneblock/`): hash lookup, prefix k-anonymity, incremental blocklist, and report endpoints. Code is GPL-3.0 while site/data carries CC BY-NC-SA 4.0; confirm redistribution compatibility before bundling rows.
- Saracroche (`https://github.com/cbouvat/saracroche`, `https://f-droid.org/en/packages/com.cbouvat.android.saracroche/`): French ARCEP nuisance prefixes; useful as a country-scoped range signal, with attribution/license and stale-prefix handling.
- Google libphonenumber (`https://libphonenumber.org/`): E.164 parsing, country, carrier, and line-type metadata. Normalize before every merge and retain original country/line-type evidence.
- Ofcom numbering data (`https://www.ofcom.org.uk/phones-and-broadband/phone-numbers/numbering-data?language=en`): UK allocated/available ranges. Use to identify unassigned or implausible presentation numbers, not as a spam list.
- FCC/FTC material is generally government-public-domain or government-work data with attribution obligations; record the exact dataset date and query window in generated snapshots.

### Optional, authenticated, or licensing-gated feeds

- Nomorobo API v2.1 (`https://assets.nomorobo.com/enterprise/Nomorobo%20Enterprise%20API%20Documentation%20v2.1.pdf`, `https://www.nomorobo.com/api/`): risk score, STIR/SHAKEN grade, reported category, DNO/FTC violations, transcription metadata, and decaying downloads; requires `X-API-Key` and a commercial agreement.
- Hiya Protect (`https://developer.hiya.com/docs/getting-started/introduction`, `https://developer.hiya.com/docs/protect/business-partner-api/endpoints/get-reputation-for-phones`): E.164 reputation and engagement metrics; manual partner access and per-number fees.
- First Orion (`https://developer.firstorion.com/`, `https://developer.firstorion.com/firstorion-public/docs/protect-plus`) and TNS Call Guardian (`https://communications.tnsi.com/tns-call-guardian`): carrier-scale risk/identity signals; partnership only.
- Tellows API (`https://www.tellows.com/s/about-en/tellows-api-partnership-program`, `https://shop.tellows.de/en/tellows-api-key.html`): score 1–9, caller type/name, country coverage, daily Scorelist/API; licensing and key required.
- Truecaller developer API (`https://www.truecaller.com/blog/news/truecaller-releases-api`), YouMail (`https://www.youmail.com/home/`, `https://support.youmail.com/en/articles/10848465/permissions-required-by-youmail`), RoboKiller (`https://support.robokiller.com/hc/en-us/articles/17677056875796-How-does-Robokiller-know-which-calls-to-block`), and Free Caller Registry (`https://freecallerregistry.com/fcr/public/html/home.html?t=1668614400022`): useful benchmark/positive identity signals, not unrestricted feeds.
- IPQualityScore (`https://www.ipqualityscore.com/documentation/phone-number-validation-api/overview`) and Twilio Lookup (`https://www.twilio.com/docs/lookup`): line type, VOIP/prepaid, reassignment, SIM-swap, forwarding, DNC, and SMS-pumping risk. Use only as optional enrichment; never ship keys in the APK.
- Global Signal Exchange (`https://www.globalsignalexchange.org/docs/signal-format`, `https://www.globalsignalexchange.org/docs/receiving-signals`): normalized phone/URL signals with abuse type, confidence, date, source, and predictive context; API key/secret and plan-gated.
- PenipuMY (`https://penipu.my/api/v1/docs`): Malaysian phone/bank/social/URL reports, business verification and compromised-business fields; key-gated and rate-limited.
- Singapore ScamShield (`https://reports.open.gov.sg/scamshield/overview`, `https://reports.open.gov.sg/scamshield/updates`, `https://www.scamshield.gov.sg/terms-of-use/`): reviewed numbers plus on-device SMS model and campaign clustering; terms prohibit scraping/reproduction, so pursue a partnership only.

### SMS and URL threat feeds

- URLhaus (`https://urlhaus.abuse.ch/api/`): active malware-distribution URLs, periodic dumps, authenticated access, and explicit fetch cadence. Keep URL verdicts separate from phone-number blocks.
- PhishTank (`https://dev.phishtank.com/api_info.php`): Cisco Talos community-verified phishing feed; use local snapshots and respect rate limits.
- OpenPhish (`https://openphish.com/phishing_feeds.html`): community and paid phishing feeds with license restrictions; do not assume an unrestricted live API.
- Google Safe Browsing v5 (`https://developers.google.com/safe-browsing/reference/rest`) is non-commercial; commercial use belongs on Web Risk. Canonicalize/redact URLs and make network lookup opt-in.
- Google’s on-device scam detection/security material (`https://blog.google/security/new-ai-powered-scam-detection-features/`, `https://blog.google/security/whats-new-in-android-security-privacy-2025/`, `https://services.google.com/fh/files/misc/android_2025_text_based_scams_report.pdf`) provides current categories and hard negatives, not a third-party bulk list.
- USPS smishing guidance (`https://www.uspis.gov/news/scam-article/smishing-package-tracking-text-scams`), Scamwatch SMS patterns/statistics (`https://www.scamwatch.gov.au/types-of-scams/text-or-sms-scams`, `https://www.scamwatch.gov.au/research-and-resources/scam-statistics`), FBI IC3 report (`https://www.fbi.gov/file-repository/2025_ic3report.pdf/view`), and UK ICO guidance (`https://ico.org.uk/make-a-complaint/nuisance-calls-and-messages/spam-texts-and-nuisance-calls/`) are taxonomy/training/evaluation sources, not live blocklists.

### Regional and standards signals

- ACMA SMS Sender ID Register (`https://www.acma.gov.au/sms-sender-id-register`) and telco guide (`https://www.acma.gov.au/sites/default/files/2025-10/sms_sender_id_register_user_guide_for_telcos.pdf`): registered brands are positive provenance; `Unverified` should raise risk in Australia without automatic blocking.
- Ofcom nuisance calls/CLI (`https://www.ofcom.org.uk/phones-and-broadband/unwanted-calls-and-messages/tackling-nuisance-calls-and-messages`, `https://www.ofcom.org.uk/phones-and-broadband/phone-numbers/calling-line-identification`), France ARCEP (`https://en.arcep.fr/news/press-releases/view/n/numbering-plan-021225.html`), Germany Bundesnetzagentur (`https://www.bundesnetzagentur.de/SharedDocs/Pressemitteilungen/EN/2025/20250115_Rufmani.html`), and France Bloctel (`https://www.bloctel.gouv.fr/donnees-essentielles`) inform regional numbering/complaint priors. Bloctel is a consumer do-not-call registry, not a blocklist.
- ATIS SHAKEN (`https://sti-ga.atis.org/atis-standards-and-technical-reports/`, `https://atis.org/resources/signature-based-handling-of-asserted-information-using-tokens-shaken-atis-1000074-e/`), RFC 8224 (`https://datatracker.ietf.org/doc/rfc8224/`), PASSporT RFC 8225 (`https://www.rfc-editor.org/rfc/rfc8225.html`), SHAKEN RFC 8588 (`https://www.ietf.org/rfc/rfc8588.html`), and Rich Call Data RFC 9795 (`https://www.rfc-editor.org/rfc/rfc9795.html`) define caller authentication, attestation, signed names/logos/reasons, and future parsing contracts.
- IoC handling RFC 9424 (`https://www.rfc-editor.org/rfc/rfc9424.html`) supports context, confidence, source, and time-bounded indicators; this is the correct model for phone numbers and domains.
- Canada CRTC traceback decision (`https://www.crtc.gc.ca/eng/archive/2026/2026-52.htm`) and the UK/Australia/France regulator material show that carrier-origin metadata is becoming more useful, but public number-level feeds remain uncommon.
- NANPA NPA/CO-code assignments (`https://www.nanpa.com/index.php/reports/npa-reports`, `https://www.nanpa.com/reports/co-code-reports/cocodes_assign`) and the FCC Reassigned Numbers Database (`https://www.reassigned.us/`, `https://docs.fcc.gov/public/attachments/DA-20-105A2.pdf`) can reject unassigned/available ranges and reduce false positives after a number is recycled; both are validation/paid-query signals, not spam lists.
- India TRAI DND/UCC guidance (`https://www.trai.gov.in/faqcategory/unsolicited-commercial-communicationsucc`, `https://trai.gov.in/sites/default/files/2026-02/PR_No.21of2026.pdf`) and DoT Chakshu (`https://www.sancharsaathi.gov.in/SancharSaathiDocuments/ImportantDocuments/Press%20Release-DoT%20takes%20action%20against%20Electricity%20KYC%20Update%20Scam.pdf`) provide localized report categories and impersonation patterns; no public bulk number feed is offered.
- Brazil Anatel rules (`https://www.gov.br/anatel/pt-br/consumidor/chamadas-abusivas/medidas-cautelares`, `https://www.gov.br/anatel/pt-br/assuntos/noticias/anatel-define-regras-para-implementacao-da-autenticacao-de-chamadas`, `https://www.gov.br/anatel/pt-br/assuntos/noticias/anatel-amplia-0303-no-combate-as-chamadas-indesejadas`) provide 0303 commercial-call labeling, invalid-range and high-volume behavior signals, and authentication requirements.
- Spain’s 400 commercial prefix and anti-spoofing rules (`https://www.boe.es/buscar/act.php?id=BOE-A-2026-8409`, `https://www.boe.es/buscar/doc.php?id=BOE-A-2025-2870`, `https://www.boe.es/buscar/doc.php?id=BOE-A-2026-12045`) support locale-specific presentation validation; unknown commercial callers should be treated as suspicious context, not an automatic block.
- Ireland ComReg sender-ID registry (`https://www.comreg.ie/media/2025/06/SMS-Sender-ID-Registry-Rules-Of-Registration.pdf`, `https://www.comreg.ie/media/2025/06/SMS-Sender-ID-Registry-Public-Guide-ver-1-2.pdf`, `https://www.comreg.ie/media/2025/06/Sender-ID-Press-Release-040625.pdf`) labels unregistered IDs and shows how a regional allow/provenance registry can work without a global blacklist.
- Poland CERT Polska (`https://www.gov.pl/web/baza-wiedzy/dostales-niepokojacy-sms-lub-email-zglos-go-do-cert-polska-csirt-nask`) and Sweden PTS (`https://pts.se/en/internet-and-telephony/sakerhet-och-skydd-av-uppgifter/telephone-scams-spoofing/`) / Belgium BIPT (`https://www.bipt.be/operatoren/telecom/consumentenbescherming/koninklijk-besluit-betreffende-spoofing`) document regional report and spoofed-CLI rules; use for locale policy and training, not scraping.
- UK 7726/NCSC guidance (`https://www.ofcom.org.uk/phones-and-broadband/scam-calls-and-messages/what-to-do-about-a-scam-call-text-or-message`, `https://www.ncsc.gov.uk/collection/phishing-scams/report-scam-text-message`) supports an opt-in report-forwarding workflow. The raw carrier feed is not open.
- ACCC/National Anti-Scam Centre (`https://www.accc.gov.au/system/files/targeting-scams-report-2025.pdf`, `https://www.accc.gov.au/system/files/nasc-job-scam-fusion-cell-final-report-2025.pdf`) confirms that ephemeral SMS numbers favor campaign/content/domain clustering over permanent exact-number blocks.
- Mavenir carrier descriptions (`https://www.mavenir.com/portfolio/mavapps/fraud-security/spamshield-messaging-fraud/`, `https://www.mavenir.com/portfolio/mavapps/fraud-security/callshield-voice-fraud-and-revenue-protection/`) are useful feature benchmarks: message fingerprints, callback reputation, answer/decline/voicemail behavior, neighbor/mirror spoofing, and invalid/unallocated ranges. They are carrier-gated products, not public feeds.

### OSS competitors and community signals

- CallFilter (`https://callfilter.pedrorau.dev/`), Stefan Ilchev CallBlocker (`https://github.com/StefanIlchev/CallBlocker`), SpamBlocker (`https://github.com/aj3423/SpamBlocker`, `https://f-droid.org/en/packages/spam.blocker/`), YetAnotherCallBlocker (`https://gitlab.com/xynngh/YetAnotherCallBlocker`), Hush (`https://github.com/nouradeen/hush`), and calls-blocker (`https://github.com/ryosoftware/calls-blocker`) demonstrate local regex, wildcard, country-prefix, and official CallScreeningService UX.
- PhoneBlock upstream (`https://github.com/haumacher/phoneblock`), JingHu (`https://qclb.com/en/`), SpamBlocker Extended (`https://f-droid.org/en/packages/dev.kerballone.spamblocker/`), and minimal Call Screener (`https://sites.google.com/view/callscreener`) demonstrate offline databases, on-device models, rule testing, and notification/role constraints.
- Should I Answer methodology (`https://www.shouldianswer.com/post/does-the-should-i-answer-app-block-all-spam-calls`), 800Notes (`https://800notes.com/faq`), whocall.me (`https://whocall.me/`), SkipCalls (`https://skipcalls.com/tools/spam-check-api`), SpravPortal WhoCalls API (`https://github.com/spravportal/whocallsapi`), and the small GitHub seed list (`https://gist.github.com/jsadeli/6e2bf66bd02c9c444acdc3c8f605b50e`) are discovery/benchmark sources only; terms, provenance, and noise prevent blind scraping or bundling.
- Dynamic/static spam-blocker studies (`https://cse3000-research-project.github.io/static/dc9aa83becc038e76b259b7c315ea0ad/poster.pdf`, `https://cse3000-research-project.github.io/static/ad0294aeb01805a5f88b039518bd32ca/poster.pdf`) show common permissions, CallScreeningService use, and app-level implementation patterns.

### Academic and evaluation sources

- Malicious-call detection (`https://arxiv.org/abs/1804.02566`), virtual-assistant spam screening (`https://arxiv.org/abs/2008.03554`), multiple-vantage robocall analysis (`https://arxiv.org/abs/2410.17361`), worldwide campaign analysis (`https://arxiv.org/abs/2606.31790`), disposable-number ecosystems (`https://arxiv.org/abs/2306.14497`), cross-app phone-number attacks (`https://arxiv.org/abs/1512.07330`), voice-phishing detection (`https://wsp-lab.github.io/papers/kim-hearmeout-mobisys22.pdf`), and local differential-privacy blacklists (`https://arxiv.org/abs/2006.09287`) support campaign graphs, churn/velocity features, privacy-preserving aggregation, and calibrated confidence.
- SMS/scam datasets: user-report study (`https://arxiv.org/abs/2508.05276`), SmishTank (`https://arxiv.org/abs/2402.18430`), and 153,551-message benchmark (`https://arxiv.org/abs/2210.10451`). Check each dataset license before training or redistribution.
- Google’s CallShield-adjacent audio authentication work (`https://arxiv.org/abs/2601.09327`) is research, not a drop-in dependency; keep audio features future/opt-in.

## Security, privacy, and reliability assessment

1. **Telecom deadline:** Android requires a screening response within five seconds (`https://developer.android.com/reference/android/telecom/CallScreeningService`). Keep the 4.5-second guard, fail open on provider/Room/ML errors, and add fault-injection tests for cold start, direct boot, cancellation, and OEM rebind. Never add a network call to the synchronous screening path.
2. **Source trust:** Complaint, crowd, and scraped data are unverified. Store source/evidence/first-seen/last-seen/license and a confidence tier; use corroboration and decay. A single complaint or a single phishing feed hit should produce a warning/review signal, not an irreversible hard block.
3. **Privacy:** Keep contacts, call logs, SMS bodies, notification text, and URLs local by default. Hash/k-anonymous PhoneBlock lookups and URL-host-only checks are preferable to uploading raw identifiers. Redact OTPs and message bodies from logs/exports.
4. **Android policy drift:** Android 15 notification-listener OTP redaction and Android 17 delayed SMS OTP access can remove evidence. The RCS/SMS path must surface a degraded state, avoid re-request loops, and never ask users to become a default SMS app solely to bypass platform protections.
5. **Identity:** STIR/SHAKEN A/B/C status, PASSporT, RCD, DNO, line type, and sender registration are confidence inputs. Verified identity is not proof of good intent; spoofing and compromised business numbers remain possible.
6. **Supply chain:** Current dependencies are recent but need release/advisory monitoring. Review OkHttp, Room, WorkManager, Kotlin/KSP, Hilt, Compose, and Glance release/security notes before upgrades. Keep Gradle lockfiles and dependency provenance in the release gate.
7. **Deployment:** `REPLACE_WITH_KV_NAMESPACE_ID` means the Worker’s production rate-limit/dedup guarantee is not verified. Provisioning, secret configuration, deployment, and a live probe are operator-gated and belong in `Roadmap_Blocked.md` if credentials are unavailable.
8. **Signing/data:** A local `callshield-release.jks` exists but is ignored by Git; verify it never enters artifacts, backups, or logs. Do not introduce signing certificates or API keys into source, APK assets, or client-side configuration.

## Architecture direction

Adopt a source adapter registry with these fields: stable source ID, geography, signal type (number/prefix/URL/sender ID/identity), license/attribution, access mode (bundled/public/API/key/operator), parser version, retrieval timestamp, source timestamp, TTL, confidence tier, and health status. Normalize all numbers through libphonenumber-compatible logic; canonicalize URLs; retain raw evidence only in a bounded, local/import-time quarantine.

Use a two-stage decision: (1) local deterministic screening must complete offline and fail open within the deadline; (2) optional background enrichment updates reputation/campaign evidence for future calls. Separate `number reputation`, `identity verification`, `campaign behavior`, `SMS content`, and `URL threat` verdicts so users can understand why a decision changed.

Build source snapshots reproducibly with a manifest containing URL, query window, retrieval time, checksum, license, row count, accepted/rejected counts, and parser version. Publish only permitted data; for restricted sources retain a runtime adapter or hashed lookup, never a scraped copy.

## Rejected or gated approaches

- Scraping Truecaller, Hiya, Nomorobo full lists, ScamShield, tellows, Should I Answer, whocall.me, or other closed sites; access, terms, rate limits, and redistribution rights are not present.
- Bundling commercial API keys or paid proprietary rows in the APK.
- Treating FTC/FCC complaints, crowd reports, DNC registrations, or unverified sender IDs as automatic hard blocks.
- Replacing the default SMS role or requesting broad notification access only to defeat Android 15/17 OTP protections.
- Cloud audio recording/transcription or always-on remote reputation gating; conflicts with local-first/privacy goals and the five-second screening contract.
- Importing Bloctel or DNC registration data as caller blocklists; those are consumer preference registries and can create false positives.
- Adding model training data without checking dataset license, consent, redaction, and reproducibility.

## Open questions and operator gates

- Which launch geographies and languages should receive regional source adapters first (US/Canada, UK, France, Australia, Singapore, Malaysia, or global)?
- Which paid/API partnerships and monthly request budget are acceptable?
- Can an operator provision the Cloudflare KV namespace, secrets, live Worker deployment, and authorized Nomorobo/PhoneBlock credentials?
- Should SMS URL reputation be opt-in per source, and is a small on-device model acceptable for message-body classification?
- What false-positive rate and review/appeal workflow are required before any source can influence automatic blocking?

## Sources

This report cites 70+ distinct sources above, grouped by use and access status. The most actionable source families are FTC/FCC public data, PhoneBlock/Saracroche/Nomorobo IRS with license review, URLhaus/PhishTank/OpenPhish/Safe Browsing for SMS links, ACMA/Ofcom/ARCEP/ATIS/IETF identity signals, and authenticated Hiya/Nomorobo/Tellows/First Orion/TNS/IPQS/Twilio/GSE adapters.
