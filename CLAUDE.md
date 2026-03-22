# CallShield - Project Notes

## Overview
Open-source Android spam call/text blocker. GitHub-hosted spam database, 11-layer on-device detection engine with spam score gauge, number lookup, area code mapping, caller ID overlay, home screen widget. ~38 Kotlin files, ~5,200 lines. No API keys.

## Tech Stack
- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **Theme**: AMOLED black + Catppuccin Mocha accents
- **Database**: Room (SQLite v5) — 6 entities
- **Networking**: OkHttp for GitHub raw URL fetches
- **JSON**: Moshi + KotlinJsonAdapterFactory
- **Settings**: DataStore Preferences
- **Background**: WorkManager (sync every 6h, daily digest)
- **Min SDK**: 29 (Android 10), **Target SDK**: 35

## Version
v2.5.0

## 11 Detection Layers (in order)
1. Manual whitelist (always-allow list)
2. Contact whitelist (phone contacts)
3. User blocklist (manual blocks)
4. Database match (FTC/FCC/community)
5. Prefix match (19 rules — premium rate, wangiri countries)
6. Wildcard/regex rules (user-defined patterns)
7. Quiet hours (time-based blocking)
8. Frequency auto-escalation (3+ calls = auto-block)
9. STIR/SHAKEN verification (Android 11+)
10. Heuristic engine (VoIP ranges, neighbor spoof, rapid-fire, toll-free)
11. SMS content analysis (30+ regex patterns + custom keyword rules)

## UI Structure
### 6 Main Tabs
Home, Recent, Log, Lookup, Blocklist, More (Settings)

### 5 Blocklist Sub-Tabs
Blocklist, Wildcards, Keywords, Whitelist, Database

## Room Database (v5)
Entities: `spam_numbers`, `spam_prefixes`, `call_log`, `wildcard_rules`, `whitelist`, `sms_keyword_rules`
Uses `fallbackToDestructiveMigration()`.

## Key Files
### Data Layer
- `SpamRepository.kt` — detection orchestration (11 layers), sync, settings
- `SpamHeuristics.kt` — on-device heuristic engine (contact check, VoIP, wangiri, neighbor spoof, rapid-fire)
- `SmsContentAnalyzer.kt` — 30+ regex patterns for SMS spam detection
- `PhoneFormatter.kt` — (212) 555-1234 formatting
- `AreaCodeLookup.kt` — 330+ US/CA area codes mapped to city/state
- `BackupRestore.kt` — full app backup/restore as JSON
- `BlocklistExporter.kt` — blocklist export/import
- `LogExporter.kt` — CSV log export

### Services
- `CallShieldScreeningService.kt` — Android CallScreeningService, 11-layer check + rating prompt
- `SmsReceiver.kt` — SMS filtering with Thread+runBlocking (fixed race condition)
- `NotificationHelper.kt` — channels, blocked/rating notifications, stable hash-based IDs
- `SpamActionReceiver.kt` — notification action handler (block/report/safe)
- `CallLogScanner.kt` — scan existing call history for spam
- `CallerIdOverlayService.kt` — overlay warning with permission check
- `SyncWorker.kt` — periodic GitHub sync (6h)
- `DigestWorker.kt` — daily blocked summary notification

### UI Screens
- `DashboardScreen.kt` — pulsing shield, animated counters, quick toggles, scanner
- `RecentCallsScreen.kt` — phone call log with spam annotations + area codes
- `BlockedLogScreen.kt` — swipe-to-dismiss, grouping toggle, long-press copy, filters
- `LookupScreen.kt` — number input, spam score arc gauge, detection icons, haptics
- `BlocklistScreen.kt` — 5 sub-tabs (blocklist/wildcards/keywords/whitelist/database)
- `StatsScreen.kt` — weekly bar chart, type breakdown, top offenders
- `NumberDetailScreen.kt` — spam gauge, contact name, timeline, actions, live check
- `OnboardingScreen.kt` — 4-page wizard with call screener setup
- `SettingsScreen.kt` — all toggles, quiet hours, cleanup, export, backup/restore

### Widget
- `CallShieldWidget.kt` — RemoteViews, auto-refresh on block

## Scripts
- `scripts/update_ftc.py` — FTC API importer (api.ftc.gov, DEMO_KEY, ~50 records/day)
- `scripts/import_blocklists.py` — FCC open data aggregator

## Permissions
READ_PHONE_STATE, READ_CALL_LOG, READ_CONTACTS, RECEIVE_SMS, READ_SMS, INTERNET, POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, SYSTEM_ALERT_WINDOW

## Build
```
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
ANDROID_HOME="$HOME/AppData/Local/Android/Sdk"
./gradlew assembleRelease
```
Keystore: `callshield-release.jks` (gitignored). Alias: `callshield`.

## Gotchas
- Must disable/re-enable branch protection to push (enforce_admins: true)
- Material Icons: `SmsOff`->`SpeakerNotesOff`, `Nearby`->`NearMe`, `BlockFlipped`->`Block`
- STIR/SHAKEN: use `Connection.VERIFICATION_STATUS_FAILED` not `Call.Details`
- `settings.gradle.kts`: use `dependencyResolutionManagement` not `dependencyResolution`
- Room `NumberCount` data class needs explicit import in DAO
- `SwipeToDismissBox` requires `@OptIn(ExperimentalMaterial3Api::class)`
- Overlay requires `Settings.canDrawOverlays()` check before showing
- SMS receiver uses `Thread` + `runBlocking` (not `CoroutineScope`) to prevent race condition
- Notification IDs are stable hashes from phone number (no counter overflow)
- Frequency threshold validated with `coerceAtLeast(2)`
- Time block hours validated with `coerceIn(0, 23)`, same hour = disabled
- Auto-cleanup minimum 7 days enforced
- FileProvider required for export sharing (backup, blocklist, CSV)
- `tel:` deep link intent filter on MainActivity
