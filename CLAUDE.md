# CallShield - Project Notes

## Overview
Open-source Android spam call/text blocker. GitHub-hosted spam database, 11-layer on-device detection engine, caller ID overlay, home screen widget, wildcard blocking, quiet hours. No API keys.

## Tech Stack
- Kotlin, Jetpack Compose, Material 3, AMOLED black + Catppuccin Mocha
- Room (SQLite v3), OkHttp, Moshi, DataStore Preferences, WorkManager
- Min SDK 29, Target SDK 35

## 11 Detection Layers
1. Contact whitelist
2. User blocklist
3. Database match (exact)
4. Prefix match (19 rules)
5. Wildcard/regex rules
6. Time-based quiet hours
7. Frequency auto-escalation (3+ calls = auto-block)
8. STIR/SHAKEN (Android 11+)
9. Neighbor spoofing
10. Heuristic engine (VoIP, premium, wangiri, rapid-fire)
11. SMS content analysis (30+ regex patterns)

## v2.0.0 Features (12 new)
1. Bulk blocklist import script (FCC data)
2. FCC complaint API source
3. Call log scanner
4. After-call spam rating notification
5. Notification quick actions (Block/Report)
6. Home screen widget
7. Caller ID overlay for suspicious calls
8. Wildcard/regex blocking rules
9. Time-based quiet hours
10. Frequency auto-escalation
11. Community reporting (GitHub Issues)
12. Export/import blocklist (JSON)

## Key Files
- `.../data/SpamRepository.kt` — detection orchestration (11 layers)
- `.../data/SpamHeuristics.kt` — heuristic engine
- `.../data/SmsContentAnalyzer.kt` — SMS content analysis
- `.../data/BlocklistExporter.kt` — export/import
- `.../data/model/WildcardRule.kt` — wildcard entity
- `.../service/CallShieldScreeningService.kt` — call screening + rating prompt
- `.../service/NotificationHelper.kt` — notification channels + actions
- `.../service/SpamActionReceiver.kt` — notification action handler + community report
- `.../service/CallLogScanner.kt` — existing call history scanner
- `.../service/CallerIdOverlayService.kt` — overlay warning
- `.../ui/widget/CallShieldWidget.kt` — home screen widget
- `scripts/update_ftc.py` — FTC API importer
- `scripts/import_blocklists.py` — multi-source blocklist aggregator

## GitHub Repo
https://github.com/SysAdminDoc/CallShield — branch protection on master

## Version
v2.0.0

## Room DB
Version 3. Entities: spam_numbers, spam_prefixes, call_log, wildcard_rules. Uses fallbackToDestructiveMigration.

## Permissions
READ_PHONE_STATE, READ_CALL_LOG, READ_CONTACTS, RECEIVE_SMS, READ_SMS, INTERNET, POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, SYSTEM_ALERT_WINDOW

## Gotchas
- Overlay requires SYSTEM_ALERT_WINDOW — user must grant manually via Settings
- FileProvider needed for blocklist export sharing
- Widget uses RemoteViews (not Glance) for simplicity
- FTC API only returns ~50 records/day; FCC open data JSON can be huge
- Must disable/re-enable branch protection to push
