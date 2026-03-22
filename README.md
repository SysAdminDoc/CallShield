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
| 11 | **SMS Content Analysis** | 30+ regex patterns + custom keyword rules: phishing links, URL shorteners, scam keywords |

## Features

### Detection & Blocking
- 11-layer detection engine with confidence scoring
- Wildcard and regex blocking rules
- Custom SMS keyword blocking — block texts containing specific words
- Manual whitelist (always-allow specific numbers beyond contacts)
- Time-based quiet hours with configurable start/end hours
- Repeat caller auto-escalation (3+ calls = auto-blocked)
- Aggressive mode (lower thresholds, contacts always safe)
- Block hidden/unknown caller ID
- Block entire area codes with one tap

### Number Lookup
- **Instant spam check** — type or paste any number, get a verdict through all 11 layers
- **Spam Score Gauge** — animated arc widget showing 0-100 confidence with color coding
- **Detection method icons** — unique icon per layer (database, heuristic, STIR/SHAKEN, keyword, etc.)
- **Area code lookup** — 330+ US/CA area codes mapped to city and state
- **Haptic feedback** — vibration on lookup results (stronger for spam)

### Awareness
- **Caller ID overlay** — warning banner for suspicious but not-blocked calls
- **After-call spam rating** — "Was this spam?" notification for unknown callers
- **Call log scanner** — scan your existing call history for known spam numbers
- **Number detail screen** — tap any number for full history, spam gauge, contact name, timeline, and actions
- **Contact name resolution** — shows the contact display name if number is in your contacts
- **Notification quick actions** — Block forever / Report directly from notification

### Recent Calls
- Pulls from your phone's actual call log
- Every number annotated with spam indicators and area code location
- Call type icons (incoming, outgoing, missed, rejected)
- Staggered entrance animations

### Blocked Log
- **Swipe-to-dismiss** — swipe left to delete, right to block permanently
- **Log grouping** — toggle to collapse repeated numbers with count badges
- **Long-press to copy** — copy any number to clipboard
- **Filter chips** — filter by all, calls only, or SMS only
- **Snackbar feedback** — confirmation after every action
- **Area code location** shown on every entry

### Statistics
- **Weekly bar chart** — visualize spam trends over the last 7 days
- **Type breakdown** — see which detection layers are catching the most spam
- **Top offenders** — ranked list of the most persistent spam numbers

### Rules Management (5 tabs)
- **Blocklist** — manually blocked numbers with descriptions
- **Wildcards** — pattern rules like `+1832555*` with enable/disable toggle
- **Keywords** — SMS keyword blocking rules with case-sensitivity option
- **Whitelist** — always-allow numbers that bypass all detection
- **Database** — browse the full synced spam database

### Data & Backup
- **Full backup/restore** — export all blocklist, whitelist, and wildcard rules as JSON
- **Export/import blocklist** — share blocklists with friends
- **CSV log export** — export entire blocked log as CSV for analysis or evidence
- **Auto-cleanup** — configurable log retention (7, 14, 30, or 90 days)
- **Auto-sync** — database syncs from GitHub every 6 hours
- **Daily digest notification** — 24-hour summary of blocked calls and texts

### Community
- **Report to community** — one-tap opens pre-filled GitHub Issue from notifications or number detail
- **Home screen widget** — blocked count today + total, auto-refreshes
- **Global search** — search across the entire spam database from the top bar
- **Deep link handling** — receive `tel:` intents to check any number

### User Experience
- First-launch onboarding wizard with call screener setup
- Phone number formatting — `(212) 555-1234` throughout the app
- AMOLED black theme with Catppuccin Mocha accents
- Animated dashboard — pulsing shield, counter rollup animations
- Staggered entrance animations and smooth tab transitions
- Material You monochrome icon (Android 13+)
- Black splash screen (no white flash)
- 6-tab navigation: Home, Recent, Log, Lookup, Blocklist, More
- Individual toggles for every detection engine

## Requirements

- Android 10+ (API 29)
- STIR/SHAKEN requires Android 11+ (API 30)
- Caller ID overlay requires "Display over other apps" permission

## Report a Spam Number

1. [Open an Issue](../../issues/new?template=spam_report.md) with the phone number
2. Or submit a PR editing `data/spam_numbers.json` directly

## Data Sources

- **FTC Do Not Call Complaints** — Auto-imported from FTC public API via `scripts/update_ftc.py`
- **FCC Consumer Complaints** — Aggregated via `scripts/import_blocklists.py`
- **Prefix Rules** — 19 curated rules for premium rate and wangiri scam country codes
- **Community Reports** — Submitted via GitHub Issues and Pull Requests
- **On-Device Heuristics** — VoIP ranges, spam patterns, SMS content analysis, keyword rules

## Building

```bash
./gradlew assembleRelease
```

Requires JDK 17+. Signed APK output at `app/build/outputs/apk/release/app-release.apk`.

## License

MIT
