# CallShield - Project Notes

## Overview
Open-source Android spam call/text blocker. Spam database hosted on GitHub, synced to device via raw URL fetch. 8-layer detection engine. No API keys required.

## Tech Stack
- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **Theme**: AMOLED black + Catppuccin Mocha accents
- **Database**: Room (SQLite) for local cache, version 2
- **Networking**: OkHttp for GitHub raw URL fetches
- **JSON**: Moshi for parsing
- **Settings**: DataStore Preferences
- **Background Sync**: WorkManager (every 6 hours)
- **Min SDK**: 29 (Android 10) — required for CallScreeningService role
- **Target SDK**: 35

## Architecture
- `data/spam_numbers.json` — GitHub-hosted spam database (26 FTC numbers + 19 prefix rules)
- `CallScreeningService` — Android system service, screens incoming calls
- `SmsReceiver` — BroadcastReceiver for SMS filtering with content analysis
- `SyncWorker` — WorkManager periodic sync from GitHub
- `SpamRepository` — single source of truth, Room + DataStore + remote fetch + heuristics
- `SpamHeuristics` — on-device heuristic engine (contact whitelist, VoIP ranges, wangiri, neighbor spoof, rapid-fire)
- `SmsContentAnalyzer` — regex-based SMS content scanner (phishing links, spam keywords, URL shorteners)
- `MainViewModel` — shared ViewModel across all tabs

## Key Files
- `app/src/main/.../service/CallShieldScreeningService.kt` — call blocking
- `app/src/main/.../data/SpamRepository.kt` — core logic, detection orchestration
- `app/src/main/.../data/SpamHeuristics.kt` — on-device heuristic engine
- `app/src/main/.../data/SmsContentAnalyzer.kt` — SMS content analysis
- `app/src/main/.../data/remote/GitHubDataSource.kt` — GitHub fetch
- `data/spam_numbers.json` — the spam database + prefix rules
- `scripts/update_ftc.py` — FTC API data importer (uses api.ftc.gov, DEMO_KEY)

## Detection Layers (8)
1. **Contact whitelist** — never block contacts (safety net)
2. **User blocklist** — manual blocks
3. **GitHub database match** — exact number lookup
4. **Prefix match** — 19 rules (premium rate, wangiri country codes, Jamaica scam)
5. **STIR/SHAKEN verification** — carrier caller ID auth failure (Android 11+)
6. **Neighbor spoofing** — same area code + exchange as user's number
7. **Heuristic engine** — VoIP range detection, international premium, rapid-fire, toll-free scoring
8. **SMS content analysis** — spam keywords, URL shorteners, suspicious TLDs, phishing patterns, excessive caps

## Aggressive Mode
Lowers heuristic threshold from 60 to 30 (calls) and 50 to 25 (SMS). More spam blocked but potential false positives. Contacts always whitelisted regardless.

## Version
v1.1.0

## GitHub Data Flow
1. `scripts/update_ftc.py` fetches from FTC API (api.ftc.gov/v0/dnc-complaints, DEMO_KEY)
2. GitHub Actions runs weekly (Monday 6am UTC) + manual dispatch
3. App fetches raw URL, checks commit SHA for changes, syncs to local Room DB
4. Community reports via GitHub Issues get manually/bot-merged into the JSON
5. FTC API only exposes ~50 most recent complaints per day; database grows over weekly runs

## Gotchas
- `CallScreeningService` requires the app to be set as default call screener via RoleManager
- SMS blocking via `abortBroadcast()` only works if receiver has highest priority
- GitHub raw URLs have ~5min CDN cache — app uses commit SHA check to detect real changes
- Unauthenticated GitHub API: 60 requests/hour per IP
- FTC CSV download blocked (403) — must use API instead
- Room DB version 2: added `confidence` field to `call_log` table
- Contact lookup requires READ_CONTACTS permission
- `TelephonyManager.getLine1Number()` often returns null on modern Android — neighbor spoof needs this
