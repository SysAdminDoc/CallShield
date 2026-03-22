# CallShield - Project Notes

## Overview
Open-source Android spam call/text blocker. Spam database hosted on GitHub, synced to device via raw URL fetch. No API keys required.

## Tech Stack
- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **Theme**: AMOLED black + Catppuccin Mocha accents
- **Database**: Room (SQLite) for local cache
- **Networking**: OkHttp for GitHub raw URL fetches
- **JSON**: Moshi for parsing
- **Settings**: DataStore Preferences
- **Background Sync**: WorkManager (every 6 hours)
- **Min SDK**: 29 (Android 10) — required for CallScreeningService role
- **Target SDK**: 35

## Architecture
- `data/spam_numbers.json` — GitHub-hosted spam database, fetched by app
- `CallScreeningService` — Android system service, screens incoming calls
- `SmsReceiver` — BroadcastReceiver for SMS filtering
- `SyncWorker` — WorkManager periodic sync from GitHub
- `SpamRepository` — single source of truth, Room + DataStore + remote fetch
- `MainViewModel` — shared ViewModel across all tabs

## Key Files
- `app/src/main/java/com/sysadmindoc/callshield/service/CallShieldScreeningService.kt` — call blocking
- `app/src/main/java/com/sysadmindoc/callshield/data/SpamRepository.kt` — core logic
- `app/src/main/java/com/sysadmindoc/callshield/data/remote/GitHubDataSource.kt` — GitHub fetch
- `data/spam_numbers.json` — the spam database
- `scripts/update_ftc.py` — FTC data importer

## Detection Layers
1. User blocklist (manual)
2. GitHub database match (exact number)
3. Prefix match (e.g., +1900 premium rate)
4. STIR/SHAKEN verification failure (Android 11+)
5. Neighbor spoofing detection (placeholder)

## Build
Standard Android Gradle build. Requires JDK 17.

## Version
v1.0.0

## GitHub Data Flow
1. `scripts/update_ftc.py` fetches FTC complaint CSV, merges into `data/spam_numbers.json`
2. GitHub Actions can auto-run this weekly
3. App fetches raw URL, checks commit SHA for changes, syncs to local Room DB
4. Community reports via GitHub Issues get manually/bot-merged into the JSON

## Gotchas
- `CallScreeningService` requires the app to be set as default call screener via RoleManager
- SMS blocking via `abortBroadcast()` only works if receiver has highest priority
- GitHub raw URLs have ~5min CDN cache — app uses commit SHA check to detect real changes
- Unauthenticated GitHub API: 60 requests/hour per IP
