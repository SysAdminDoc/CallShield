<p align="center">
  <img src="logo.png" width="128" alt="CallShield Logo">
</p>

<h1 align="center">CallShield</h1>

<p align="center">
  <strong>Open-source spam call and text blocker for Android</strong><br>
  11-layer detection engine | 32,933 spam numbers | Real-time multi-source lookup | No API keys
</p>

<p align="center">
  <a href="https://github.com/SysAdminDoc/CallShield/releases/latest"><img src="https://img.shields.io/github/v/release/SysAdminDoc/CallShield?style=flat-square&color=a6e3a1" alt="Release"></a>
  <img src="https://img.shields.io/badge/Spam%20Numbers-32%2C933-f38ba8?style=flat-square" alt="32,933 Numbers">
  <img src="https://img.shields.io/badge/Android-10%2B-89b4fa?style=flat-square" alt="Android 10+">
  <img src="https://img.shields.io/badge/License-MIT-cba6f7?style=flat-square" alt="MIT License">
  <img src="https://img.shields.io/badge/API%20Keys-None-fab387?style=flat-square" alt="No API Keys">
</p>

---

CallShield blocks spam calls and texts using an **11-layer on-device detection engine** powered by a 32,933-number spam database and real-time lookups against 3 external sources. Community-maintained, GitHub-hosted, no accounts, no tracking.

## How It Works

1. **32,933 confirmed spam numbers** — sourced from 1.75M FCC consumer complaints, filtered to 3+ independent reports each
2. **11-layer detection** — every call and SMS checked against database, heuristics, STIR/SHAKEN, keyword rules, and more
3. **Real-time overlay** — incoming calls trigger parallel lookups against SkipCalls, PhoneBlock, and WhoCalledMe with live-updating spam score
4. **Callback-aware** — won't block callbacks from numbers you recently called, or urgent repeated callers
5. **Community-driven** — one-tap anonymous contribution via Cloudflare Worker, daily merge into database

## Detection Layers

| # | Layer | How It Works |
|---|-------|-------------|
| 1 | **Manual Whitelist** | Numbers you've explicitly marked as always-allow |
| 2 | **Contact Whitelist** | Numbers in your phone's contacts always pass through |
| 3 | **Callback Detection** | Numbers you recently called (24h) are allowed — they're callbacks, not spam |
| 4 | **Repeated Urgent Caller** | If same number calls 2x in 5 minutes, allowed through — likely urgent |
| 5 | **User Blocklist** | Your personal block list with descriptions |
| 6 | **Database Match** | 32,933 confirmed spam numbers from FCC/FTC complaints and community reports |
| 7 | **Prefix Rules** | 19 rules — US premium rate (+1900), wangiri country codes (Sierra Leone, Jamaica, etc.) |
| 8 | **Wildcard / Regex** | Custom pattern rules like `+1832555*` or full regex |
| 9 | **SMS Keyword Rules** | Block texts containing specific words you define |
| 10 | **Quiet Hours** | Block all non-contact calls during configurable hours |
| 11 | **Frequency Auto-Block** | Numbers that call 3+ times get automatically blocked |
| 12 | **STIR/SHAKEN** | Blocks calls failing carrier caller ID authentication (Android 11+) |
| 13 | **Heuristic Engine** | VoIP spam ranges, international premium, neighbor spoofing, rapid-fire, toll-free abuse |
| 14 | **SMS Content Analysis** | 30+ regex patterns for phishing links, URL shorteners, scam keywords + custom keywords |

## Live Caller ID Overlay

When a call comes in, CallShield shows a real-time overlay that queries **3 spam databases simultaneously**:

```
┌──────────────────────────────────┐
│ LIKELY SPAM                      │
│ (212) 555-1234                   │
│ New York, NY                     │
│ Spam Score: 80% (17 reports)     │
│ ⚠ SkipCalls: Flagged            │
│ ⚠ PhoneBlock: 5 reports         │
│ ⚠ WhoCalledMe: 12 reports       │
│ All sources checked              │
│ [Search Google] [Block] [Dismiss]│
└──────────────────────────────────┘
```

- Shows instantly with area code, then updates live as each source responds
- Color-coded: green (safe) → yellow → orange → red (spam)
- **Search Google** opens browser with spam search
- **Block** blocks the number AND reports it to the community database

## Features

### Number Lookup
- Instant spam check through all detection layers with animated spam score gauge (0-100)
- Auto-paste from clipboard, area code lookup (330+ US/CA), haptic feedback
- Multi-source reverse lookup: SkipCalls + PhoneBlock + WhoCalledMe queried in parallel
- Per-source pass/fail indicators with report counts

### Recent Calls & Blocked Log
- Recent calls pulled from phone's call log with contact names, risk indicator dots (green/yellow/red), call type icons
- Blocked log with swipe-to-dismiss, log grouping, long-press copy, filter chips
- **Expandable action buttons** on every entry: Search Google, Check Databases, Copy, Detail
- Staggered entrance animations

### Scanners
- Call log scanner + SMS inbox scanner — scan existing history for known spam
- Results shown on dashboard with one-tap block buttons

### Rules Management (5 tabs)
- Blocklist, Wildcards, Keywords, Whitelist, Database
- Export/import blocklists as JSON, enable/disable toggles per rule

### Statistics
- Weekly bar chart, type breakdown, top offenders, area code heatmap, time-of-day heatmap

### Smart Features
- Smart suggestions — detects area code spam patterns, one-tap block entire area code
- Blocking profiles: Work / Personal / Sleep / Maximum / Off
- Callback detection + repeated urgent caller allow-through
- FTC Do Not Call complaint filing (one-tap deep link)

### Community
- **One-tap anonymous contribution** — Report Spam or Not Spam buttons, powered by [Cloudflare Worker](https://callshield-reports.snafumatthew.workers.dev)
- False positive reporting subtracts votes, numbers with 0 reports get removed
- Share spam warnings to any app
- GitHub Issues for detailed reports

### Data & System
- Full backup/restore, CSV log export, auto-cleanup (7/14/30/90 days)
- Auto-sync every 6 hours, daily digest notification
- Quick Settings tile, app shortcuts, home screen widget
- Notification grouping + rate limiting, deep link handling
- Protection test validates all layers and permissions
- Onboarding wizard, permission check banner, sync freshness indicator

## Data Sources

### Database Seeding (32,933 numbers)
| Source | Records | Method |
|--------|---------|--------|
| **FCC Consumer Complaints** | 1,753,601 processed → 32,933 with 3+ reports | Socrata API bulk download |
| **FTC Do Not Call** | ~50/day | `api.ftc.gov` (DEMO_KEY) |
| **Community Reports** | Growing | Anonymous via Cloudflare Worker |

### Real-Time Lookup (per call)
| Source | What It Returns | Auth |
|--------|----------------|------|
| **SkipCalls** | `is_spam: true/false`, 1M+ numbers | None |
| **PhoneBlock.net** | Votes, rating (A-E), blacklist status | None |
| **WhoCalledMe** | Report count, community notes | None (scrape) |

## Privacy

All detection runs on-device. No personal data is collected. Network requests:
- Syncing spam database from GitHub (public)
- Real-time lookups against free public APIs (user's number is queried, not stored by CallShield)
- Community reports sent to Cloudflare Worker (phone number only, no user identity)

No API keys. No accounts. No analytics. No ads.

## Requirements

- Android 10+ (API 29)
- STIR/SHAKEN requires Android 11+ (API 30)
- Caller ID overlay requires "Display over other apps" permission

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
| Settings | DataStore Preferences |
| Background | WorkManager |
| Community API | Cloudflare Workers |
| Min SDK | 29 (Android 10) |
| Target SDK | 35 |
| Lines of Code | ~6,600 |

## License

MIT
