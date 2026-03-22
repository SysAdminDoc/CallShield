# CallShield

Open-source spam call and text blocker for Android with an **11-layer detection engine**. Uses a community-maintained spam database hosted right here on GitHub — no API keys, no accounts, no tracking. Everything runs on-device.

## How It Works

1. **GitHub-hosted database** — Spam numbers and prefix rules stored in `data/spam_numbers.json`
2. **App syncs locally** — Fetches the database and caches it on your device for instant offline lookups
3. **11-layer detection** — Every call and SMS passes through multiple detection engines
4. **Contact safe** — Numbers in your contacts are *never* blocked
5. **Community-driven** — Report spam numbers via GitHub Issues or PRs

## Detection Layers

| # | Layer | Details |
|---|-------|---------|
| 1 | **Manual Whitelist** | Always-allow list for specific numbers you trust |
| 2 | **Contact Whitelist** | Numbers in your phone's contacts always pass through |
| 3 | **User Blocklist** | Your personal block list with descriptions |
| 4 | **Database Match** | Known spam from FTC/FCC complaints and community reports |
| 5 | **Prefix Rules** | 19 rules — US premium rate, wangiri country codes |
| 6 | **Wildcard/Regex** | Custom pattern rules like `+1832555*` or full regex |
| 7 | **Quiet Hours** | Block all non-contact calls during set hours (e.g., 10 PM - 7 AM) |
| 8 | **Frequency Auto-Block** | Numbers that call 3+ times get auto-blocked |
| 9 | **STIR/SHAKEN** | Blocks calls failing carrier caller ID authentication (Android 11+) |
| 10 | **Heuristic Engine** | VoIP spam ranges, international premium, rapid-fire, toll-free abuse |
| 11 | **SMS Content Analysis** | 30+ regex patterns: phishing links, URL shorteners, scam keywords |

## Features

### Detection & Blocking
- 11-layer detection engine with confidence scoring
- Wildcard and regex blocking rules
- Manual whitelist (always-allow specific numbers beyond contacts)
- Time-based quiet hours (block unknowns during sleep)
- Repeat caller auto-escalation (3+ calls = auto-blocked)
- Aggressive mode (lower thresholds, contacts always safe)
- Block hidden/unknown caller ID

### Awareness
- **Caller ID overlay** — warning banner for suspicious but not-blocked calls
- **After-call spam rating** — "Was this spam?" notification for unknown callers
- **Call log scanner** — scan your existing call history for known spam numbers
- **Number detail screen** — tap any number for full history, timeline, and actions
- **Notification quick actions** — Block forever / Report directly from notification

### Statistics
- **Weekly bar chart** — visualize spam trends over the last 7 days
- **Type breakdown** — see which detection layers are catching the most spam
- **Top offenders** — ranked list of the most persistent spam numbers

### Community
- **Report to community** — one-tap opens pre-filled GitHub Issue
- **Export/import blocklist** — share your blocklist as JSON with friends
- **Home screen widget** — blocked count today + total
- **Search** — find numbers across the entire spam database

### User Experience
- First-launch onboarding wizard with call screener setup
- Auto-sync on first launch
- Phone number formatting — `(212) 555-1234` throughout the app
- AMOLED black theme with Catppuccin Mocha accents
- Staggered entrance animations and smooth tab transitions
- 5-tab navigation: Dashboard, Log, Blocklist, Stats, Settings
- Individual toggles for every detection engine

## Requirements

- Android 10+ (API 29)
- STIR/SHAKEN requires Android 11+ (API 30)
- Caller ID overlay requires "Display over other apps" permission

## Report a Spam Number

1. [Open an Issue](../../issues/new?template=spam_report.md) with the phone number
2. Or submit a PR editing `data/spam_numbers.json` directly

## Data Sources

- **FTC Do Not Call Complaints** — Auto-imported from FTC public API
- **FCC Consumer Complaints** — Aggregated via `scripts/import_blocklists.py`
- **Prefix Rules** — 19 curated rules for premium rate and wangiri scam country codes
- **Community Reports** — Submitted via GitHub Issues and Pull Requests
- **On-Device Heuristics** — VoIP ranges, spam patterns, SMS content analysis

## Building

```bash
./gradlew assembleRelease
```

Requires JDK 17+. Signed APK output at `app/build/outputs/apk/release/app-release.apk`.

## License

MIT
