# CallShield

Open-source spam call and text blocker for Android with an **8-layer detection engine**. Uses a community-maintained spam database hosted right here on GitHub — no API keys, no accounts, no tracking. Everything runs on-device.

## How It Works

1. **GitHub-hosted database** — Spam numbers and prefix rules stored in `data/spam_numbers.json`
2. **App syncs locally** — Fetches the database and caches it on your device for instant offline lookups
3. **8-layer detection** — Every call and SMS passes through multiple detection engines before reaching you
4. **Contact safe** — Numbers in your contacts are *never* blocked, regardless of other signals
5. **Community-driven** — Report spam numbers via GitHub Issues or PRs to protect everyone

## Detection Layers

CallShield runs every incoming call and SMS through 8 detection layers, in order:

| # | Layer | Method | Details |
|---|-------|--------|---------|
| 1 | **Contact Whitelist** | Contacts lookup | Numbers in your phone's contacts always pass through. Zero false positives for people you know. |
| 2 | **User Blocklist** | Manual list | Your personal block list with custom descriptions, stored on-device. |
| 3 | **Database Match** | Exact number | Known spam numbers from FTC complaints and community reports, synced from this repo. |
| 4 | **Prefix Rules** | Pattern match | 19 rules blocking entire number ranges — US premium rate (+1900), wangiri country codes (Sierra Leone, Somalia, Jamaica, Dominican Republic, Grenada, BVI, and more). |
| 5 | **STIR/SHAKEN** | Carrier verification | Blocks calls where the carrier's caller ID authentication fails — catches spoofed numbers. Android 11+ only. |
| 6 | **Neighbor Spoofing** | Similarity check | Detects calls from numbers matching your area code + exchange — a common spoofing technique to make spam look local. |
| 7 | **Heuristic Engine** | On-device scoring | Analyzes VoIP spam ranges, international premium rate numbers, toll-free abuse patterns, rapid-fire calling (3+ calls/hour), and invalid number formats. Returns a confidence score. |
| 8 | **SMS Content Analysis** | Regex scanner | Scans message text for 30+ spam patterns: phishing links, URL shorteners (bit.ly, tinyurl, etc.), suspicious TLDs (.xyz, .top, .club), urgency language, financial scam keywords, excessive caps, and callback number insertion. |

## Aggressive Mode

For maximum protection, enable **Aggressive Mode** in settings. This lowers detection thresholds so more borderline spam gets caught. Your contacts are always whitelisted regardless.

| Mode | Call threshold | SMS threshold |
|------|--------------|---------------|
| Normal | 60/100 confidence | 50/100 confidence |
| Aggressive | 30/100 confidence | 25/100 confidence |

## Features

- 8-layer detection engine with confidence scoring
- AMOLED black theme with Catppuccin Mocha accents
- Block spam calls and SMS independently
- Block hidden/unknown caller ID numbers
- Contact whitelist prevents false positives
- SMS content analysis catches spam from unknown numbers
- Heuristic engine detects wangiri, VoIP spam, rapid-fire robocalls
- View blocked call/SMS log with match reasons and confidence %
- Browse the full spam database on-device
- Manual number blocking with descriptions
- Automatic background sync every 6 hours
- Individual toggles for each detection engine
- No API keys or accounts required
- Fully open source

## Requirements

- Android 10+ (API 29)
- STIR/SHAKEN features require Android 11+ (API 30)

## Report a Spam Number

1. [Open an Issue](../../issues/new?template=spam_report.md) with the phone number
2. Or submit a PR editing `data/spam_numbers.json` directly

## Data Sources

- **FTC Do Not Call Complaints** — Auto-imported from the FTC public API via `scripts/update_ftc.py`
- **Prefix Rules** — 19 curated rules covering premium rate and wangiri scam country codes
- **Community Reports** — Submitted via GitHub Issues and Pull Requests
- **On-Device Heuristics** — VoIP ranges, spam patterns, and SMS content analysis run locally

## Building

```bash
./gradlew assembleDebug
```

Requires JDK 17.

## License

MIT
