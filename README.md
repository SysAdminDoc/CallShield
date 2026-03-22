# CallShield

Open-source spam call and text blocker for Android. Uses a community-maintained spam number database hosted right here on GitHub — no API keys, no accounts, no tracking.

## How It Works

1. **GitHub-hosted database** — Spam numbers are stored in `data/spam_numbers.json` in this repo
2. **App syncs locally** — CallShield fetches the database and caches it on your device for instant offline lookups
3. **Screens calls & texts** — Uses Android's `CallScreeningService` to silently reject spam calls and filter spam SMS
4. **Community-driven** — Anyone can report spam numbers via GitHub Issues or PRs

## Detection Layers

| Layer | Method | Details |
|-------|--------|---------|
| Database | Exact match | Known spam numbers from FTC, FCC, and community reports |
| Prefix | Pattern match | Blocks entire number ranges (e.g., premium rate +1900) |
| STIR/SHAKEN | Carrier verification | Blocks calls that fail caller ID authentication (Android 11+) |
| Neighbor Spoof | Similarity check | Flags calls from numbers suspiciously similar to yours |
| User Blocklist | Manual | Your personal block list, stored on-device |

## Features

- AMOLED black theme with Catppuccin Mocha accents
- Block spam calls and SMS independently
- Block hidden/unknown caller ID numbers
- View blocked call/SMS log with details
- Browse the full spam database on-device
- Manual number blocking with descriptions
- Automatic background sync every 6 hours
- No API keys or accounts required
- Fully open source

## Requirements

- Android 10+ (API 29)
- STIR/SHAKEN features require Android 11+ (API 30)

## Report a Spam Number

1. [Open an Issue](../../issues/new?template=spam_report.md) with the phone number
2. Or submit a PR editing `data/spam_numbers.json` directly

## Data Sources

- **FTC Do Not Call Complaints** — Bulk imported via `scripts/update_ftc.py`
- **FCC Consumer Complaints** — Merged into the database
- **Community Reports** — Submitted via GitHub Issues and Pull Requests

## Building

```bash
./gradlew assembleDebug
```

Requires JDK 17.

## License

MIT
