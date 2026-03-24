<p align="center">
  <img src="logo.png" width="128" alt="CallShield Logo">
</p>

<h1 align="center">CallShield</h1>

<p align="center">
  <strong>Open-source spam call and text blocker for Android</strong><br>
  15-layer detection + ML scorer | 32,933 spam numbers | Real-time caller ID | RCS filter | No API keys
</p>

<p align="center">
  <a href="https://github.com/SysAdminDoc/CallShield/releases/latest"><img src="https://img.shields.io/github/v/release/SysAdminDoc/CallShield?style=flat-square&color=a6e3a1" alt="Release"></a>
  <img src="https://img.shields.io/badge/Spam%20Numbers-32%2C933-f38ba8?style=flat-square" alt="32,933 Numbers">
  <img src="https://img.shields.io/badge/Android-10%2B-89b4fa?style=flat-square" alt="Android 10+">
  <img src="https://img.shields.io/badge/License-MIT-cba6f7?style=flat-square" alt="MIT License">
  <img src="https://img.shields.io/badge/API%20Keys-None-fab387?style=flat-square" alt="No API Keys">
</p>

---

CallShield blocks spam calls and texts using a **15-layer on-device detection engine** with an ML spam scorer, RCS notification filter, and real-time caller ID overlay. Powered by a 32,933-number database with 30-minute hot list updates. Community-maintained, no accounts, no tracking.

## How It Works

1. **32,933 confirmed spam numbers** — sourced from 1.75M FCC consumer complaints (2+ reports each), FTC Do Not Call, ToastedSpam, and community reports
2. **15-layer detection + ML** — database, heuristics, on-device logistic regression, SMS content analysis, RCS filter, STIR/SHAKEN, and more
3. **Real-time caller ID overlay** — parallel lookups against SkipCalls, PhoneBlock, WhoCalledMe + OpenCNAM caller name, with SIT tone anti-autodialer
4. **30-minute hot list** — trending spam numbers and campaign ranges refresh every 30 minutes via GitHub Actions
5. **Callback-aware** — won't block callbacks from numbers you recently called, or urgent repeated callers
6. **Community-driven** — one-tap anonymous contribution via Cloudflare Worker, daily merge into database

## Detection Layers

| # | Layer | How It Works |
|---|-------|-------------|
| 1 | **Manual Whitelist** | Numbers you've explicitly marked as always-allow |
| 2 | **Contact Whitelist** | Numbers in your phone's contacts always pass through |
| 3 | **Callback Detection** | Numbers you recently called (24h) are allowed — they're callbacks |
| 4 | **Repeated Urgent Caller** | Same number calls 2x in 5 minutes → allowed through |
| 5 | **User Blocklist** | Your personal block list with descriptions |
| 6 | **Database Match** | 32,933 confirmed spam numbers + hot list (refreshed every 30 min) |
| 7 | **Prefix Rules** | Wangiri country codes, US premium rate (+1900), international premium |
| 8 | **Wildcard / Regex** | Custom pattern rules like `+1832555*` or full regex |
| 9 | **Quiet Hours** | Block all non-contact calls during configurable hours |
| 10 | **Frequency Auto-Block** | Numbers that call 3+ times get automatically blocked |
| 11 | **Heuristic Engine** | VoIP ranges, neighbor spoofing, rapid-fire, hot campaign range detection |
| 12 | **Caller ID Overlay** | Suspicious calls (score 30-59) get live multi-source lookup |
| 13 | **SMS Keyword Rules** | Block texts containing specific words you define |
| 14 | **SMS Content Analysis** | 30+ regex patterns, URL shorteners, suspicious TLDs, spam domain blocklist |
| 15 | **ML Spam Scorer** | 15-feature on-device logistic regression model (weekly retrained) |

### Additional Layers
- **SMS Context Trust** — messages from numbers you've texted or received from on 2+ days are allowed
- **RCS Filter** — NotificationListenerService monitors Google/Samsung Messages for RCS spam
- **URL Safety** — URLhaus (abuse.ch) checks for phishing/malware URLs in SMS/RCS (post-decision, notification only)
- **STIR/SHAKEN** — blocks calls failing carrier caller ID verification (Android 11+)

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

On-device 15-feature logistic regression model — no TFLite, no heavy ML libraries. Pure math, runs in microseconds.

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

Trained weekly from the CallShield database (50K positive + 50K negative samples). Threshold: 0.7 (conservative).

## Features

### Number Lookup
- Instant spam check through all 15 detection layers with animated score gauge (0-100)
- Auto-paste from clipboard, area code lookup (330+ US/CA), haptic feedback
- Multi-source reverse lookup: SkipCalls + PhoneBlock + WhoCalledMe + OpenCNAM

### Recent Calls & Blocked Log
- Recent calls with contact names, risk indicators, call type icons
- Blocked log with swipe-to-dismiss, grouping, filter chips, expandable action buttons
- Staggered entrance animations

### Rules Management (5 tabs)
- Blocklist, Wildcards, Keywords, Whitelist, Database
- Export/import blocklists as JSON, per-rule enable/disable toggles
- Regex validation before adding wildcard rules

### Statistics
- Weekly bar chart, detection method breakdown, top offenders, area code heatmap, hourly heatmap

### Smart Features
- Smart suggestions — detects area code spam patterns, one-tap block entire area code
- Blocking profiles: Work / Personal / Sleep / Maximum / Off
- Callback detection + repeated urgent caller allow-through
- FTC Do Not Call complaint filing

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

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Theme | AMOLED black + Catppuccin Mocha |
| Database | Room (SQLite) — 6 entities |
| Networking | OkHttp |
| JSON | Moshi |
| ML | Pure Kotlin logistic regression (15 features) |
| Settings | DataStore Preferences |
| Background | WorkManager |
| Community API | Cloudflare Workers |
| URL Safety | URLhaus (abuse.ch) |
| Min SDK | 29 (Android 10) |
| Target SDK | 35 |
| Files | 56 Kotlin + 5 Python scripts |

## License

MIT
