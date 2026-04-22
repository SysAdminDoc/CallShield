<p align="center">
  <img src="logo.png" width="128" alt="CallShield Logo">
</p>

<h1 align="center">CallShield</h1>

<p align="center">
  <strong>Open-source spam call and text blocker for Android</strong><br>
  15+ layer detection + Gradient-Boosted Tree ML | 32,933 spam numbers | Real-time caller ID | RCS filter | No API keys
</p>

<p align="center">
  <a href="https://github.com/SysAdminDoc/CallShield/releases/latest"><img src="https://img.shields.io/github/v/release/SysAdminDoc/CallShield?style=flat-square&color=a6e3a1" alt="Release"></a>
  <img src="https://img.shields.io/badge/Spam%20Numbers-32%2C933-f38ba8?style=flat-square" alt="32,933 Numbers">
  <img src="https://img.shields.io/badge/Tests-210-94e2d5?style=flat-square" alt="210 Tests">
  <img src="https://img.shields.io/badge/Android-10%2B-89b4fa?style=flat-square" alt="Android 10+">
  <img src="https://img.shields.io/badge/License-MIT-cba6f7?style=flat-square" alt="MIT License">
  <img src="https://img.shields.io/badge/API%20Keys-None-fab387?style=flat-square" alt="No API Keys">
</p>

---

CallShield blocks spam calls and texts using a **15+ layer on-device detection engine** with a gradient-boosted tree ML scorer, campaign burst detection, RCS notification filter, and real-time caller ID overlay. Powered by a 32,933-number database with 30-minute hot list updates. Community-maintained, no accounts, no tracking.

## v1.2.8 Highlights

- **Gradient-Boosted Tree ML model** — 20 features, pure Kotlin, no TFLite dependency
- **Campaign burst detection** — NPA-NXX prefix clustering identifies coordinated spam waves
- **After-call feedback** — "Was this spam?" notification for post-call community reporting
- **544+ localized string resources** — fully externalized, ready for translation
- **210 unit tests + GitHub Actions CI** — automated test pipeline on every push
- **Full accessibility** — 100+ content descriptions, 48dp minimum touch targets
- **Enhanced statistics** — weekly bar chart, source donut chart, monthly trend line
- **Improved home screen widget** — today vs yesterday trend, last blocked time display

## How It Works

1. **32,933 confirmed spam numbers** — sourced from 1.75M FCC consumer complaints (2+ reports each), FTC Do Not Call, ToastedSpam, and community reports
2. **15+ layer detection + ML** — database, heuristics, campaign burst detection, on-device gradient-boosted tree, SMS content analysis, RCS filter, STIR/SHAKEN, and more
3. **Real-time caller ID overlay** — parallel lookups against SkipCalls, PhoneBlock, WhoCalledMe + OpenCNAM caller name, with SIT tone anti-autodialer
4. **30-minute hot list** — trending spam numbers and campaign ranges refresh every 30 minutes via GitHub Actions
5. **Callback-aware** — won't block callbacks from numbers you recently called, or urgent repeated callers
6. **Community-driven** — one-tap anonymous contribution via Cloudflare Worker, daily merge into database

## Detection Pipeline (v1.6.0)

All detection layers implement a shared `IChecker` interface and run in priority order via `CheckerPipeline.run` — first non-null result wins, every layer is testable in isolation. Priorities are stable numbers; the ladder below is the live order.

| Priority | Layer | Verdict | How It Works |
|---------:|-------|---------|-------------|
| 10000 | **Manual Whitelist** | Allow | Numbers you've explicitly marked as always-allow |
|  9000 | **Contact Whitelist** | Allow | Numbers in your phone's contacts always pass through |
|  7000 | **User Blocklist + Database** | Block | Personal blocklist + 32,933 confirmed spam numbers + hot list (refreshed every 30 min) |
|  6900 | **System Block List** (A4) | Block | Read-only bridge to Android's `BlockedNumberContract` — respects stock Phone/Messages blocks |
|  6000 | **Prefix Rules** | Block | Wangiri country codes, US premium rate (+1900), international premium |
|  5500 | **Wildcard / Regex** | Block | Custom patterns like `+1832555*` or full regex, now with optional schedule |
|  5400 | **Range Patterns** (A5) | Block | Length-locked `#` patterns like `+33162######`, with schedule + coverage safety rail |
|  5000 | **Recently Dialed** | Allow | Numbers you called in the last 24h — they're probably calling back |
|  4900 | **Repeated Urgent** | Allow | Same number calls 2x in 5 min → allowed through |
|  4700 | **Push-Alert Bridge** (A3) | Allow | Uber/DoorDash/Amazon/Gmail notification about an arriving call? Let it through |
|  4500 | *Campaign Recorder* | — | Side-effect only; feeds burst detection below |
|  4000 | **Quiet Hours** | Block | Block all non-contact calls during configurable hours |
|  3500 | **Frequency Auto-Block** | Block | Numbers that call 3+ times in 7 days get auto-blocked |
|  3000 | **Heuristic Engine** | Block | VoIP ranges, neighbor spoofing, rapid-fire detection, 30+ rules |
|  2500 | **Campaign Burst** | Block | NPA-NXX prefix clustering detects coordinated spam waves |
|  2000 | **ML Spam Scorer** | Block | 20-feature on-device gradient-boosted tree model |

SMS-specific layers (append after the shared chain): **SMS Context Trust** → **SMS Keyword Rules** (with schedule) → **SMS Content Analysis** (30+ regex patterns, URL shorteners, suspicious TLDs, spam domain blocklist).

### Additional Layers
- **Caller ID Overlay** — suspicious calls (heuristic score 30-59) trigger a live multi-source lookup overlay with SkipCalls, PhoneBlock, WhoCalledMe + OpenCNAM caller name
- **RCS Filter** — NotificationListenerService monitors Google/Samsung Messages for RCS spam
- **URL Safety** — URLhaus (abuse.ch) checks for phishing/malware URLs in SMS/RCS (post-decision, notification only)
- **STIR/SHAKEN** — blocks calls failing carrier caller ID verification (Android 11+)
- **After-Call Feedback** — "Was this spam?" notification after suspicious calls for community reporting

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
- Full backup/restore, CSV log export, auto-cleanup (7/14/30/90 days)
- Weekly full sync + 30-minute hot list refresh, daily digest notification
- Quick Settings tile, app shortcuts, home screen widget
- Protection test validates all layers and permissions
- Onboarding wizard with permission requests

## Data Sources

### Database (32,933 numbers, weekly CI)
| Source | Method |
|--------|--------|
| **FCC Consumer Complaints** | Socrata API, 500K records, min 2 reports |
| **FTC Do Not Call** | `api.ftc.gov` (DEMO_KEY) |
| **ToastedSpam** | Community curated list |
| **Community Reports** | Anonymous via Cloudflare Worker |

### Hot List (30-minute refresh)
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
| **AbstractAPI** | Carrier, line type | Optional key |

### URL Safety (post-decision)
| Source | What It Checks |
|--------|---------------|
| **URLhaus** (abuse.ch) | Phishing/malware URLs in SMS/RCS bodies |

## Security

- **Network security config** — cleartext traffic disabled in production
- **Signing credentials** — stored in `local.properties`, not hardcoded in build files
- **Restricted FileProvider paths** — scoped to export directory only
- **Database-only backup** — `android:fullBackupContent` excludes preferences containing API keys

## Privacy

All detection runs on-device. No personal data is collected. Network requests:
- Syncing spam database from GitHub (public)
- Real-time lookups against free public APIs (number queried, not stored)
- Community reports to Cloudflare Worker (phone number only, no identity)
- URLhaus checks for SMS URL safety (URL only)

No API keys required. No accounts. No analytics. No ads.

## Requirements

- Android 10+ (API 29)
- STIR/SHAKEN requires Android 11+ (API 30)
- Caller ID overlay requires "Display over other apps" permission
- RCS filter requires Notification Access permission

## Building

```bash
./gradlew assembleRelease
```

Requires JDK 17+. Signed APK at `app/build/outputs/apk/release/app-release.apk`.

**Signing:** Create `local.properties` in the project root with your keystore credentials:
```properties
RELEASE_STORE_FILE=path/to/keystore.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

## Testing

```bash
./gradlew testDebugUnitTest   # 210 tests
```

CI runs automatically via GitHub Actions on every push and pull request.

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Theme | Premium AMOLED black + Catppuccin Mocha |
| Database | Room (SQLite) — 6 entities |
| Networking | OkHttp |
| JSON | Moshi |
| ML | Pure Kotlin gradient-boosted tree (20 features) |
| Settings | DataStore Preferences |
| Background | WorkManager |
| Community API | Cloudflare Workers |
| URL Safety | URLhaus (abuse.ch) |
| CI | GitHub Actions |
| Tests | 210 unit tests (JUnit) |
| Strings | 544+ resources (translation-ready) |
| Accessibility | 100+ content descriptions, 48dp touch targets |
| Min SDK | 29 (Android 10) |
| Target SDK | 35 |

For deep technical details, see [CLAUDE.md](CLAUDE.md).

## License

MIT
