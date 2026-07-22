<p align="center">
  <img src="logo.png" width="128" alt="CallShield Logo">
</p>

<h1 align="center">CallShield</h1>

<p align="center">
  <strong>Open-source spam call and text blocker for Android</strong><br>
  15+ layer detection + Gradient-Boosted Tree ML | 32,933 spam numbers | Real-time caller ID | RCS filter | No required API keys
</p>

<p align="center">
  <a href="https://github.com/SysAdminDoc/CallShield/releases/latest"><img src="https://img.shields.io/github/v/release/SysAdminDoc/CallShield?style=flat-square&color=a6e3a1" alt="Release"></a>
  <img src="https://img.shields.io/badge/Spam%20Numbers-32%2C933-f38ba8?style=flat-square" alt="32,933 Numbers">
  <img src="https://img.shields.io/badge/Tests-836-94e2d5?style=flat-square" alt="836 Tests">
  <img src="https://img.shields.io/badge/Android-10%2B-89b4fa?style=flat-square" alt="Android 10+">
  <img src="https://img.shields.io/badge/License-MIT-cba6f7?style=flat-square" alt="MIT License">
  <img src="https://img.shields.io/badge/API%20Keys-None-fab387?style=flat-square" alt="No required API keys">
</p>

---

CallShield blocks spam calls and texts using a **15+ layer on-device detection engine** with a gradient-boosted tree ML scorer, campaign burst detection, RCS notification filter, and real-time caller ID overlay. Powered by a 32,933-number database with scheduled hot-list updates. Community-maintained, no accounts, no tracking.

## v1.7.19 Highlights

- **Independent app language** — Settings now offers a persistent language
  picker backed by Android's per-app locale APIs (including the system App
  Languages surface on Android 13+); debug builds expose both Android
  pseudolocales for repeatable layout QA.
- **Contacts-mode safety check** — CallShield now detects when a contacts-based
  screening mode (contact-whitelist or contacts-only) is switched on but the
  Contacts permission has since been denied — a state that silently weakens
  protection — so the risk can be surfaced instead of failing quietly.
- **Visible ML health** — Protection Test labels the active GBT/logistic/default
  model and clearly flags a failed update or degraded fallback.
- **Accessibility regression gate** — Compose's Accessibility Test Framework
  now checks onboarding, dashboard, blocklist, and Settings for labels, touch
  targets, contrast, and traversal. Expandable details announce their state,
  and Android 16 receives native duration speech annotations.
- **Caller-name blocking** — bounded `*`/`?` patterns can now reject recurring
  carrier-presented spam names after stronger explicit and behavioral rules.
- **Native sync progress** — manual protection refreshes use Android 16's
  progress-centric notification style with a truthful fallback on older APIs.
- **Optional Android post-call review** — Android 11+ can open a platform
  post-call surface with block/report and save-contact actions; the existing
  lightweight rating notification remains the default.

## v1.7.17 Highlights

- **Protection self-heals** — the RCS notification filter now rebinds itself if
  Android unbinds it (low memory, app update, OEM kill), and app updates without
  a reboot re-assert all background workers and the listener. A corrupt local
  database is now detected and rebuilt (re-syncing from the cloud) instead of
  silently letting every call ring through.
- **Leaner daily digest** — the 24-hour blocked-activity summary is computed with
  aggregate database queries instead of loading the full window into memory.
- **OEM battery-kill awareness** — the app can now detect when an aggressive
  battery manager is likely to kill background protection, and when the spam
  database has gone stale, so those risks can be surfaced.
- **More tested internals** — Robolectric now exercises notification actions,
  SMS reassembly, real call-screening allow/silence/reject outcomes, and the
  SMS phishing-warning entrypoint (831 tests total).

## v1.7.16 Highlights

- **Safer imports** — blocklist import and backup restore now read the selected
  file through a bounded reader (32 MB cap) and cap applied rows, guarding
  against out-of-memory on a huge or malformed file.
- **Android 16 notifications** — blocked-call alerts group and summarize cleanly
  so a burst stays visible instead of being cooldown-muted.
- **ML transparency** — the on-device model reports a health state, so a corrupt
  model that quietly falls back to the simpler scorer is now logged instead of
  silently degrading detection.

## v1.7.15 Highlights

- **Fewer false blocks** — legitimate US/Canada callers from area codes like 678
  (Atlanta), 267 (Philadelphia), 224 (Chicago), 385 (Salt Lake City) and others
  are no longer misblocked as international "wangiri" scams, and international
  numbers that merely share digits with US premium lines (e.g. Mongolia +976)
  are no longer flagged.
- **Safer data** — backup restore and hot-list refresh are now fully atomic (no
  half-applied state on failure); CSV log exports are hardened against
  spreadsheet formula injection; the phishing-URL check caps its response size.

## v1.7.14 Highlights

- **Correctness fixes** — the after-call "Was this spam?" notification now
  dismisses when you tap its buttons; phishing-URL (URLhaus) warnings no longer
  require SMS blocking to be on; and a burst of legitimate SMS from one carrier
  prefix can no longer trip campaign blocking of real voice calls.
- **Caller-ID overlay dismisses when the call ends** instead of lingering for a
  fixed 20 seconds.
- **RTL-safe numbers** — phone numbers are bidi-isolated in notifications so they
  render correctly inside Arabic/Hebrew text.
- **Hardening** — malformed ML fallback weights are rejected instead of silently
  zeroed; GitHub default-branch lookups are cached to avoid rate-limit
  exhaustion; RCS encrypted-message placeholders are detected across more
  languages; CVE-2026-53914 build-cache posture documented.

## v1.7.13 Highlights

- **Fully free, zero API keys** — the optional AbstractAPI key field has been
  removed from Settings. CallShield no longer has any manual credential entry;
  every lookup and enrichment source it uses is free and needs no sign-up. Any
  key saved by an earlier version is purged from the device on first launch.
- **Dead code removed** — the unused carrier/line-type network checker
  (`NumberTypeChecker`) and its certificate pin are gone.

## v1.7.12 Highlights

- **Durable blocked-call logging** - blocked-call decisions now enqueue an
  idempotent pending Room row before responding to Android, then a Hilt-backed
  retry worker flushes the row into the final call log without duplicating
  notifications or widget refreshes.
- **Room v10 migration** - `call_log` now carries a nullable unique `logKey`,
  `pending_blocked_call_logs` persists retry state, and migration/DAO tests
  cover duplicate suppression and retry windows.
- **Community-report abuse backoff** - the Cloudflare Worker rate-limit and
  dedup helpers now have local tests, and the Android client surfaces HTTP 429
  with the server-provided retry delay.
- **ASCII-only digit utility** - security-sensitive phone-number extraction
  and UI entry/review paths now share named ASCII-only helpers instead of
  Unicode digit matching.
- **External blocklist subscription guardrails** - custom HTTP(S) CSV/TXT/JSON
  feeds validate byte and row limits, preview add/remove/source impact before
  commit, and can be disabled or removed with feed-owned rows rolled back.

- **Compose BOM 2026.05.00 refresh** — UI dependencies now resolve on the
  current Compose 1.11.1 release train, with Material 3 1.4.0 and refreshed
  lifecycle/core/savedstate transitive locks across debug, release, and unit
  test configurations.
- **Configuration-aware Compose copy** — snackbar, toast, semantic, validation,
  and search-result count text now use Compose resource APIs instead of stale
  `LocalContext.getString` / `resources` reads inside composables.
- **WorkManager 2.11.2 refresh** — background sync, hot-list refresh, and
  daily digest jobs now run on the current WorkManager line with Android 15+
  network-constraint fixes.
- **Worker schedule contract tests** — sync, manual refresh, hot-list, and
  digest WorkRequests now have JVM coverage for repeat intervals, network
  constraints, initial delay, and retry backoff.
- **DataStore privacy hardening** — settings now run on DataStore 1.2.1, and
  the optional AbstractAPI enrichment key is stored in a private no-backup
  DataStore instead of the backed-up public settings file.
- **Safer backup boundary** — database and public preferences remain
  restorable for device transfers, while optional local credentials migrate out
  of Android Auto Backup scope on first read or save.
- **Reproducible-build groundwork** — Gradle dependency locking is enabled,
  the resolved dependency graph is checked in, AGP VCS metadata is disabled for
  release APKs, and local release guards block wall-clock build metadata from
  being embedded into APK inputs.
- **Release hash sidecars** — release builds now produce SHA256 sidecars for
  APK artifact integrity, with Windows helpers for signed local releases and
  content-level APK rebuild comparisons.
- **Network hardening** — OkHttp is upgraded to 5.3.2 and all direct data,
  community-report, URL-safety, and caller-ID enrichment hosts are protected by
  centralized certificate pinning.
- **Modern Android build stack** — AGP is upgraded to 8.10.1, Kotlin/KSP are
  aligned on 2.2.21, and Room is upgraded to 2.8.4, keeping codegen and R8
  compatible with the current Kotlin metadata used by the networking stack.
- **Stats and scan feedback polish** — weekly activity labels now respect locale weekday names, Statistics detection-source labels are resource-backed, and scan permission/failure copy is consistent across call-log and SMS flows.
- **Settings credential polish** — the optional AbstractAPI key is masked by default, has explicit show/hide control, reports "Not configured", "Saved locally", and "Unsaved changes" states before saving, and is now kept out of backed-up public preferences.
- **Premium-polish UX refresh** — shared premium action, compact-action, icon-tile, and state-card treatments now unify dashboard, lookup, details, recent-call, blocklist, diagnostics, onboarding, and settings flows.
- **Clearer recovery states** — Blocked Log empty and filtered states now explain what happened and provide a direct "Show all activity" recovery action when filters hide records.
- **Trust-focused settings feedback** — the trusted push-alert source picker now shows installed-source coverage and skeleton loading while package labels resolve.
- **Hardening foundation from v1.7.2** — spoof-proof ASCII phone normalization, SMS size caps, regex ReDoS validation, LRU notice gates, separated PendingIntent IDs, and atomic crash-log writes.
- **STIR/SHAKEN Authenticated Allow** — carrier-authenticated caller ID can short-circuit weak heuristic / ML blocks while still yielding to every explicit user and system rule.
- **Answered-caller trust** — numbers answered repeatedly inside the configured lookback window can ring through lower-confidence heuristic/ML suspicion while explicit block rules still win first.
- **Emergency callback grace** — unknown callbacks can ring through after a local emergency call for a configurable window while explicit block rules still win first.
- **SMS burst protection** - repeated unknown SMS senders or same-prefix floods can be blocked as `sms_burst`, with blocked-SMS notification actions to mark safe or report.
- **836 total JVM unit tests** - the local unit suite covers detection, workers, utilities, repository contracts, and permission readiness before release.
- **Gradient-Boosted Tree ML model** — 20 features, pure Kotlin, no TFLite dependency.
- **Campaign burst detection** — NPA-NXX prefix clustering identifies coordinated spam waves.
- **Full accessibility** — content descriptions across Compose UI, 48dp minimum touch targets.

## How It Works

1. **32,933 confirmed spam numbers** — sourced from 1.75M FCC consumer complaints (2+ reports each), FTC Do Not Call, ToastedSpam, and community reports
2. **15+ layer detection + ML** — database, heuristics, campaign burst detection, on-device gradient-boosted tree, SMS content/burst analysis, RCS filter, STIR/SHAKEN, and more
3. **Real-time caller ID overlay** — parallel lookups against SkipCalls, PhoneBlock, WhoCalledMe + OpenCNAM caller name, with SIT tone anti-autodialer
4. **Scheduled hot list** — trending spam numbers and campaign ranges refresh through the repository data pipeline
5. **Callback-aware** — won't block callbacks from numbers you recently called, answered repeatedly, after a local emergency call, or urgent repeated callers
6. **Community-driven** — one-tap anonymous contribution via Cloudflare Worker, daily merge into database

## Detection Pipeline (v1.7.19)

All detection layers implement a shared `IChecker` interface and run in priority order via `CheckerPipeline.run` — first non-null result wins, every layer is testable in isolation. Priorities are stable numbers; the ladder below is the live order.

| Priority | Layer | Verdict | How It Works |
|---------:|-------|---------|-------------|
| 10000 | **Manual Whitelist** | Allow | Numbers you've explicitly marked as always-allow |
|  9000 | **Contact Whitelist** | Allow | Numbers in your phone's contacts always pass through |
|  8500 | **STIR/SHAKEN Failed** | Block | Carrier-authenticated caller ID failure gets blocked before heuristic layers |
|  7000 | **User Blocklist + Database** | Block | Personal blocklist + 32,933 confirmed spam numbers + scheduled hot-list data |
|  6900 | **System Block List** (A4) | Block | Read-only bridge to Android's `BlockedNumberContract` — respects stock Phone/Messages blocks |
|  6000 | **Prefix Rules** | Block | Wangiri country codes, US premium rate (+1900), international premium |
|  5500 | **Wildcard / Regex** | Block | Custom patterns like `+1832555*` or full regex, now with optional schedule |
|  5400 | **Range Patterns** (A5) | Block | Length-locked `#` patterns like `+33162######`, with schedule + coverage safety rail |
|  5300 | **STIR/SHAKEN Authenticated** | Allow | Carrier-authenticated caller ID can allow through lower-confidence heuristic/ML suspicion while explicit blocks still win first |
|  5000 | **Recently Dialed** | Allow | Numbers you called in the last 24h — they're probably calling back |
|  4980 | **Emergency Callback** | Allow | Unknown callbacks can ring through after a local emergency call during the configured grace window |
|  4950 | **Answered Caller** | Allow | Numbers answered repeatedly inside the configured lookback window |
|  4900 | **Repeated Urgent** | Allow | Same number calls 2x in 5 min → allowed through |
|  4700 | **Push-Alert Bridge** (A3) | Allow | Uber/DoorDash/Amazon/Gmail notification about an arriving call? Let it through |
|  4500 | *Campaign Recorder* | — | Side-effect only; feeds burst detection below |
|  4000 | **Quiet Hours** | Block | Block all non-contact calls during configurable hours |
|  3500 | **Frequency Auto-Block** | Block | Numbers that call 3+ times in 7 days get auto-blocked |
|  3000 | **Heuristic Engine** | Block | VoIP ranges, neighbor spoofing, rapid-fire detection, 30+ rules |
|  2500 | **Campaign Burst** | Block | NPA-NXX prefix clustering detects coordinated spam waves |
|  2250 | **Caller Name Rules** | Block | Carrier-presented names can match bounded, user-defined `*`/`?` patterns after every allow layer |
|  2000 | **ML Spam Scorer** | Block | 20-feature on-device gradient-boosted tree model |

SMS-specific layers (append after the shared chain): **SMS Context Trust** → **SMS Keyword Rules** (with schedule) → **SMS Burst Protection** → **SMS Content Analysis** (30+ regex patterns, URL shorteners, suspicious TLDs, spam domain blocklist).

### Additional Layers
- **Caller ID Overlay** — suspicious calls (heuristic score 30-59) trigger a live multi-source lookup overlay with SkipCalls, PhoneBlock, WhoCalledMe + OpenCNAM caller name
- **Region & caller-name rules** — opt-in offline blocking outside selected US/Canadian regions, plus bounded `*`/`?` trust and block patterns for carrier-presented caller names; explicit number/system/prefix/wildcard blocks and all allow layers keep priority
- **Opt-in message notification screening** — Google/Samsung Messages are enabled by default; AOSP Messages, SMS Organizer, Signal, WhatsApp, WhatsApp Business, Gmail, Outlook, and Thunderbird can be enabled individually. Private-messenger/email matches show a separate warning without removing the original notification.
- **URL Safety** — local spam-domain checks run before URLhaus (abuse.ch), with query-string stripping enabled by default for remote SMS/RCS URL checks
- **STIR/SHAKEN** — blocks calls failing carrier caller ID verification (Android 11+)
- **After-Call Feedback** — "Was this spam?" notification after suspicious calls, plus an optional Android 11+ post-call screen for block/report and save-contact actions

### Per-Rule Schedules (A7)
Any wildcard, range, or SMS keyword rule can be time-gated to specific days of the week and an hour window. The hour picker supports overnight wrap; `daysMask = 0` is the "no gating" sentinel so rules created before v1.6 behave identically.

## Live Caller ID Overlay

When a call comes in, CallShield shows a real-time overlay that queries **4 sources simultaneously**:

```
┌──────────────────────────────────┐
│ LIKELY SPAM                      │
│ (212) 555-1234                   │
│ New York, NY                     │
│ Spam Score: 80% (17 reports)     │
│ JOHN DOE (OpenCNAM)             │
│ ⚠ SkipCalls: Flagged            │
│ ⚠ PhoneBlock: 5 reports         │
│ ⚠ WhoCalledMe: 12 reports       │
│ All sources checked              │
│ [Search] [Block] [Dismiss]       │
│ 🔈 Play SIT Tone (anti-dialer)  │
└──────────────────────────────────┘
```

- Shows instantly with area code, then updates live as each source responds
- **OpenCNAM** caller name lookup (free, 60 req/hr)
- **SIT Tone** — ITU-T E.180 three-tone sequence tricks autodialers into removing your number
- Color-coded: green (safe) → yellow → orange → red (spam)

## ML Spam Scorer

On-device **20-feature gradient-boosted tree** model — pure Kotlin, no TFLite, no heavy ML libraries. Runs in microseconds.

| Feature | Description |
|---------|------------|
| toll_free | 800/888/877/etc. prefix |
| high_spam_npa | Area code in high FTC/FCC complaint set |
| voip_range | NPA-NXX in known VoIP spam carrier range |
| repeated_digits_ratio | Fraction of most-common digit |
| sequential_asc/desc_ratio | Sequential digit pairs |
| all_same_digit | All 10 digits identical |
| nxx_555 | Exchange is 555 (test numbers) |
| last4_zero | Subscriber is 0000 |
| invalid_nxx | NXX starts with 0 or 1 (NANP-invalid) |
| subscriber_all_same | Last 4 digits all same (9999) |
| alternating_pattern | Even/odd positions uniform (5050505050) |
| nxx_below_200 | Often unassigned ranges |
| low_digit_entropy | Fewer than 4 distinct digits |
| subscriber_sequential | Last 4 form ascending/descending run |
| + 6 additional | Campaign proximity, time-of-day, call frequency, area code density, prefix heat, neighbor spoof score |

Trained weekly from the CallShield database (50K positive + 50K negative samples). Threshold: 0.7 (conservative).

## Features

### Number Lookup
- Instant spam check through all 15+ detection layers with animated score gauge (0-100)
- Auto-paste from clipboard, area code lookup (330+ US/CA), haptic feedback
- Multi-source reverse lookup: SkipCalls + PhoneBlock + WhoCalledMe + OpenCNAM

### Recent Calls & Blocked Log
- Recent calls with contact names, risk indicators, call type icons, filter chips (All/Missed/Spam)
- Blocked log with swipe-to-dismiss + undo, grouping with severity-scaled accent bars, filter chips
- Staggered entrance animations, shimmer loading skeletons

### Rules Management (5 tabs)
- Blocklist, Wildcards, Keywords, Whitelist, Database
- Export/import blocklists as JSON, per-rule enable/disable toggles
- Regex validation before adding wildcard rules
- Inline priority-conflict warnings name the whitelist, emergency allow, or block rule that wins before an overlapping rule is saved

### Statistics
- Weekly bar chart with daily breakdown
- Detection source donut chart
- Monthly trend line
- Top offenders, area code heatmap, hourly heatmap

### Smart Features
- Smart suggestions — detects area code spam patterns, one-tap block entire area code
- Weekly trend indicator — shows if spam is increasing or decreasing vs last week
- Last blocked preview card on dashboard with tap-to-inspect
- Blocking profiles: Work / Personal / Sleep / Maximum / Off
- Callback detection + repeated urgent caller allow-through
- FTC Do Not Call complaint filing
- After-call "Was this spam?" feedback notification

### Home Screen Widget
- Today vs yesterday blocked count with trend indicator
- Last blocked number and time
- Quick-access to lookup and protection status

### Community
- **One-tap anonymous contribution** via [Cloudflare Worker](https://callshield-reports.snafumatthew.workers.dev)
- False positive reporting subtracts votes
- Share spam warnings to any app

### Data & System
- Selective backup/restore for rules, non-secret settings, and opt-in logs; CSV log export; auto-cleanup (7/14/30/90 days)
- Weekly full sync + scheduled hot list refresh, daily digest notification
- Quick Settings tile, app shortcuts, home screen widget
- Protection test validates all layers and permissions
- Onboarding wizard with permission requests

## Data Sources

### Database (32,933 numbers, locally maintained)
| Source | Method |
|--------|--------|
| **FCC Consumer Complaints** | Socrata API, 500K records, min 2 reports |
| **FTC Do Not Call** | `api.ftc.gov` (DEMO_KEY) |
| **ToastedSpam** | Community curated list |
| **Community Reports** | Anonymous via Cloudflare Worker |

### Hot List (scheduled refresh)
| File | Contents |
|------|----------|
| `hot_numbers.json` | Top 500 trending numbers (last 24h) |
| `hot_ranges.json` | NPA-NXX prefixes with 3+ active campaign numbers |
| `spam_domains.json` | Phishing/spam domains from community SMS reports |

### Real-Time Lookup (overlay only)
| Source | What It Returns | Auth |
|--------|----------------|------|
| **SkipCalls** | spam flag, 1M+ numbers | None |
| **PhoneBlock.net** | Votes, rating, blacklist | None |
| **WhoCalledMe** | Report count, notes | None |
| **OpenCNAM** | Caller name (CNAM) | None (60/hr) |

### URL Safety (post-decision)
| Source | What It Checks |
|--------|---------------|
| **URLhaus** (abuse.ch) | Phishing/malware URLs in SMS/RCS bodies after local spam-domain matching |

## Security

- **Network security config** — cleartext traffic disabled in production
- **Signing credentials** — stored in `local.properties`, not hardcoded in build files
- **Restricted FileProvider paths** — scoped to export directory only
- **Scoped backup** — database and public settings are backed up for transfer/restore; no credentials are stored anywhere

## Privacy

All detection runs on-device. No personal data is collected. Network requests:
- Syncing spam database from GitHub (public)
- Real-time lookups against free public APIs (number queried, not stored)
- Community reports to Cloudflare Worker (phone number only, no identity)
- URLhaus checks for SMS URL safety after local spam-domain matching; fragments and query strings are stripped by default

No API keys — none required, none optional, no credential entry anywhere in the app. No accounts. No analytics. No ads.

## Requirements

- Android 10+ (API 29)
- STIR/SHAKEN requires Android 11+ (API 30)
- Caller ID overlay requires "Display over other apps" permission
- RCS filter requires Notification Access permission

## Building

```bash
./gradlew verifyReproducibleBuildInputs verifyReleaseApkReproducibleMetadata
```

Requires JDK 17+. Signed APK at `app/build/outputs/apk/release/app-release.apk`.
Generate the release hash sidecar with:

```powershell
.\scripts\write-release-sha256.ps1
```

See `docs/reproducible-builds.md` for the dependency-lock and hash-comparison
runbook.

F-Droid submission prep lives in `fastlane/metadata/android/en-US/`,
`docs/fdroid/com.sysadmindoc.callshield.yml`, and
`docs/fdroid-submission.md`. The actual F-Droid merge request and signature-copy
verification still require an fdroiddata/GitLab environment.

**Signing:** Create `local.properties` in the project root with your keystore credentials:
```properties
RELEASE_STORE_FILE=path/to/keystore.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

## Testing

```bash
./gradlew testDebugUnitTest   # 836 tests
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.sysadmindoc.callshield.platform.TargetSdkBehaviorSmokeTest
```

Run tests, lint, release metadata checks, and artifact builds locally before publishing.

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin 2.2.21 |
| UI | Jetpack Compose BOM 2026.05.00 + Material 3 |
| Theme | Premium AMOLED black + Catppuccin Mocha |
| Database | Room 2.8.4 (SQLite) — 7 entities |
| Networking | OkHttp 5.3.2 + certificate pinning |
| JSON | Moshi |
| ML | Pure Kotlin gradient-boosted tree (20 features) |
| Settings | DataStore Preferences 1.2.1 |
| Background | WorkManager 2.11.2 |
| Community API | Cloudflare Workers |
| URL Safety | URLhaus (abuse.ch) |
| Verification | Local Gradle, lint, and release-artifact checks |
| Tests | 836 JVM unit tests (JUnit) |
| Strings | 1160 string resources and 29 plural groups (translation-ready) |
| Accessibility | 100+ content descriptions, 48dp touch targets |
| Min SDK | 29 (Android 10) |
| Target SDK | 36 |

For deep technical details, see [CLAUDE.md](CLAUDE.md).

## License

MIT
