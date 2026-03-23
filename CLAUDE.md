# CallShield - Project Notes

## Overview
Open-source Android spam call/text blocker. 49 Kotlin files, ~6,600 lines. 32,933 spam numbers from 1.75M FCC records. 11-layer detection + callback detection. Real-time multi-source caller ID overlay. Anonymous community contribution via Cloudflare Worker. No API keys.

## Version
v1.0.0

## Tech Stack
- Kotlin, Jetpack Compose, Material 3, AMOLED black + Catppuccin Mocha
- Room (SQLite v5) — 6 entities: spam_numbers, spam_prefixes, call_log, wildcard_rules, whitelist, sms_keyword_rules
- OkHttp, Moshi, DataStore Preferences, WorkManager
- Cloudflare Workers (community reports endpoint)
- Min SDK 29, Target SDK 35

## Detection Pipeline (in order)
1. Manual whitelist
2. Contact whitelist
3. Callback detection (recently dialed = allow)
4. Repeated urgent caller (2x in 5min = allow)
5. User blocklist
6. Database match (32,933 numbers)
7. Prefix rules (19 wangiri/premium)
8. Wildcard/regex rules
9. SMS keyword rules
10. Quiet hours
11. Frequency auto-block (3+ calls)
12. STIR/SHAKEN (Android 11+)
13. Heuristic engine (VoIP, neighbor spoof, rapid-fire)
14. SMS content analysis (30+ regex)

## Data Sources
### Database (scripts/, CI)
- FCC Unwanted Calls: 1,753,601 records → 32,933 with 3+ reports (Socrata API)
- FTC Do Not Call: api.ftc.gov, DEMO_KEY, ~50/day
- Community reports: Cloudflare Worker → data/reports/ → merge script

### Live Lookup (in-app, per call)
- SkipCalls: spam.skipcalls.app/check/{number} — free, no key
- PhoneBlock: phoneblock.net/phoneblock/api/num/{number} — free, no key
- WhoCalledMe: web scrape — free

## UI Structure
- 6 main tabs: Home, Recent, Log, Lookup, Blocklist, More
- 5 blocklist sub-tabs: Blocklist, Wildcards, Keywords, Whitelist, Database
- More hub: Statistics, Settings, Protection Test, What's New, Quick Links, About
- Caller ID overlay: live multi-source lookup with real-time score updates

## Infrastructure
- Cloudflare Worker: callshield-reports.snafumatthew.workers.dev
- GitHub Actions: weekly database update + daily community report merge
- Keystore: callshield-release.jks (gitignored)

## Build
```
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
ANDROID_HOME="$HOME/AppData/Local/Android/Sdk"
./gradlew assembleRelease
```

## Gotchas
- Must disable/re-enable branch protection to push (enforce_admins: true)
- GitHubDataSource branch default is "master" not "main"
- Icons: SmsOff→SpeakerNotesOff, Nearby→NearMe, BlockFlipped→Block
- STIR/SHAKEN: Connection.VERIFICATION_STATUS_FAILED (not Call.Details)
- settings.gradle.kts: dependencyResolutionManagement (not dependencyResolution)
- Room NumberCount data class needs explicit import in DAO
- SwipeToDismissBox requires @OptIn(ExperimentalMaterial3Api::class)
- SMS receiver uses Thread+runBlocking (not CoroutineScope) to prevent race
- Notification IDs are stable hashes from phone number (no counter overflow)
- Adaptive icon XML overrides PNG mipmaps on API 26+ — deleted it so logo PNG is used
- PullToRefresh API unstable in Material3 — removed
- PhoneBlock bulk blocklist requires auth, per-number lookup does not
- FCC Socrata API is slow for large offsets — use CSV bulk download instead
