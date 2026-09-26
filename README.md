<p align="center">
  <img src="logo.png" width="128" alt="CallShield Logo">
</p>

<h1 align="center">CallShield</h1>

<p align="center">
  <strong>Open-source spam call and text blocker for Android</strong><br>
  15+ layer detection + Gradient-Boosted Tree ML | 51,357 spam numbers | Real-time caller ID | RCS filter | No required API keys
</p>

<p align="center">
  <a href="https://github.com/SysAdminDoc/CallShield/releases/latest"><img src="https://img.shields.io/github/v/release/SysAdminDoc/CallShield?style=flat-square&color=a6e3a1" alt="Release"></a>
  <img src="https://img.shields.io/badge/Spam%20Numbers-51%2C357-f38ba8?style=flat-square" alt="51,357 Numbers">
  <img src="https://img.shields.io/badge/JVM%20Tests-1559-94e2d5?style=flat-square" alt="1559 JVM tests">
  <img src="https://img.shields.io/badge/Android-10%2B-89b4fa?style=flat-square" alt="Android 10+">
  <img src="https://img.shields.io/badge/License-MIT-cba6f7?style=flat-square" alt="MIT License">
  <img src="https://img.shields.io/badge/API%20Keys-None-fab387?style=flat-square" alt="No required API keys">
</p>

<p align="center">
  <a href="https://ko-fi.com/X8K126YVER">
    <img height="42" src="https://storage.ko-fi.com/cdn/kofi2.png?v=3" alt="Buy me a coffee on Ko-fi" />
  </a>
</p>

<p align="center">
  <sub><em>If CallShield helps keep spam out of your day, a coffee helps me keep its protection data and app current.</em></sub>
</p>

---

CallShield blocks spam calls and texts using a **15+ layer on-device detection engine** with a gradient-boosted tree ML scorer, bounded campaign and churn evidence, conservative carrier identity metadata signals, an RCS notification filter, and real-time caller ID. Its 51,357-number database sits alongside a trending-numbers feed the app checks every 30 minutes. There are no accounts or tracking.

The database keeps `data/spam_numbers.json` as a stable legacy GitHub-raw
endpoint for older clients, while current builds bundle a hash manifest and
256 content-addressed shards. The manifest, the legacy database, the trending
feeds and the model weights each carry a detached ECDSA P-256 signature, and the
app refuses any of them that doesn't verify against a key built into it. Devices
fetch only changed shards, check every shard against the signed manifest before
a transactional Room refresh, and fall back to the legacy snapshot when the
shard service is unavailable.

## Screenshots

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/01-home.png" width="30%" alt="CallShield protection dashboard">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/02-blocked.png" width="30%" alt="Blocked call activity">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/03-lookup.png" width="30%" alt="Explainable number lookup">
</p>
<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/04-overlay.png" width="30%" alt="Live call risk overlay">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/05-settings.png" width="30%" alt="Privacy and blocking settings">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/06-more.png" width="30%" alt="Protection tools and release information">
</p>

## Getting Started

1. **Install the APK** from the [latest release](https://github.com/SysAdminDoc/CallShield/releases/latest). See [Installing](#installing) for signature details.
2. **Run the setup wizard.** Phone and SMS access and the Call Screening role are required when Android supports screening. You can skip notifications, Notification Access (for RCS filtering), and Overlay (for live caller ID). The review shows what each skipped grant disables. The last step lets you choose Recommended, Strict, or Contacts only. Open Settings and tap **Run setup again** whenever you want to change these choices.
3. **Sync the database.** The Home screen runs a first sync on its own. After that, background syncs check every six hours.
4. **Recommended starting profile.** Choose Recommended for the default call and text controls. Strict adds aggressive call checks, blocks hidden callers, and turns on quiet hours. Contacts only lets contacts and trusted numbers ring. Every profile change offers Undo. Turn on Notification Access if you use Google Messages or Samsung Messages and want SMS filtering through the notification listener too.

New installs use the AMOLED theme. Settings opens on Basic controls for blocking,
safety, notifications and appearance. Advanced holds the detailed access and
detection options. Change the theme to Graphite, Light or System in Basic.

For a full walkthrough of every toggle with its default, see [docs/getting-started.md](docs/getting-started.md).

Version highlights for each release are in [CHANGELOG.md](CHANGELOG.md).

## How It Works

1. **51,357 imported spam numbers.** Sources include FCC consumer complaints (2+ reports each), FTC Do Not Call, ToastedSpam, and corroborated community reports.
2. **15+ layer detection + ML**. Database, heuristics, bounded campaign/churn detection, on-device gradient-boosted tree, SMS content/burst analysis, RCS filter, STIR/SHAKEN, and more
3. **Real-time caller ID overlay**. An optional SkipCalls spam check for locally suspicious calls, with SIT tone anti-autodialer
4. **Trending feeds**. The app checks for trending spam numbers and campaign ranges every 30 minutes. The maintainer regenerates them by hand from new community reports
5. **Callback-aware**. Won't block callbacks from numbers you recently called, answered repeatedly, after a local emergency call, or urgent repeated callers
6. **Community-driven**. One-tap anonymous contribution via Cloudflare Worker, merged into the database by the maintainer

## v1.8.1 Highlights

A fix release, from a review of the work that went into 1.8.0.

- **Category rules** no longer act on a weak ML block. An ML score under 80 doesn't count as a robocall any more.
- **Filters** on the Database tab and in the Blocked log keep their chips when Android closes the app, and a new filter never shows the last one's rows while it loads.
- **Outgoing call check** no longer drops the second of two overlapping calls.
- **Light theme contrast** meets the AA minimum on the log cleanup chips, the repeat counts and the rule-conflict warning.
- **Telemarketing range names** in the "why was this blocked" panel follow the app's language.

## v1.8.0 Highlights

- **Answer & hang up** takes a blocked call and drops it straight away, so spam can't leave a voicemail. Off by default. Contributed by tikkamasalla.
- **Outgoing call check** holds a call you dial to a number CallShield already flags and tells you why before it connects.
- **Meeting mode** sends unknown callers quietly to voicemail while a meeting app you pick has a call up.
- **Signed protection data.** Every feed the app downloads now carries the maintainer's signature, the certificate pins that had refused downloads since August are fixed, and a feed mirror covers places where GitHub is blocked.
- **Region rules** reach outside North America, and Settings gains telemarketing ranges for Spain, India and Brazil.
- **Chinese** now covers every line of system text, and the block log, Lookup and the "why was this blocked" panel are translatable.
- **Community reports** go out once and wait for a connection when you're offline. A new number enters the database after reports on two different UTC days. Reports with reporter buckets also need three distinct buckets.

## Detection Pipeline (v1.8.1)

All detection layers implement a shared `IChecker` interface and run in priority order via `CheckerPipeline.run`. First non-null result wins, every layer is testable in isolation. Priorities are stable numbers, and the ladder below is the live order.

| Priority | Layer | Verdict | How It Works |
|---------:|-------|---------|-------------|
| 10000 | **Manual Whitelist** | Allow | Numbers you've explicitly marked as always-allow |
|  9000 | **Contact Whitelist** | Allow | Numbers in your phone's contacts always pass through |
|  8800 | **Contacts-Only Mode** | Block | Calls outside your contacts and trusted numbers are blocked |
|  8500 | **STIR/SHAKEN Failed** | Block | Carrier-authenticated caller ID failure gets blocked before heuristic layers |
|  7000 | **User Blocklist** | Block | Your own exact blocks, permanent or temporary |
|  6900 | **System Block List** (A4) | Block | Read-only bridge to Android's `BlockedNumberContract`. Respects stock Phone/Messages blocks |
|  5500 | **Wildcard / Regex** | Block | Custom patterns like `+1832555*` or full regex, now with optional schedule |
|  5400 | **Range Patterns** (A5) | Block | Length-locked `#` patterns like `+33162######`, with schedule + coverage safety rail |
|  5350 | **Temporary Allow** | Allow | One-off false-positive recovery from the Blocked Log. Beats all downloaded data, never your own rules |
|  5320 | **Prefix Rules** | Block | Downloaded wangiri country codes, US premium rate (+1900), international premium |
|  5310 | **Regulatory Prefix** | Block | Opt-in (Settings > Telemarketing ranges) blocks for ranges regulators set aside for sales calls: Spain 400 (from 17 October 2026), India 140 (TRAI), Brazil 0303 (ANATEL). Matches the number with its country code, and without it on a phone from that country |
|  5300 | **STIR/SHAKEN Authenticated** | Allow | Carrier-authenticated caller ID allows through heuristic/ML suspicion, and through a database match only when that row's newest evidence, community reports included, is over a year old and the number isn't trending right now. Explicit blocks still win first |
|  5250 | **Regulatory Allow** | Allow | Opt-in protected series that rings through past the database and statistics. India 1600 (banks, insurers and government offices, per TRAI) |
|  5200 | **Spam Database** | Block | 51,357 imported spam numbers plus the trending-numbers feed |
|  5150 | **Database Prefix Expansion** | Block | Auto-blocks last-two-digit siblings of confirmed database entries |
|  5000 | **Recently Dialed** | Allow | Numbers you called in the last 24h. They're probably calling back |
|  4980 | **Emergency Callback** | Allow | Unknown callbacks can ring through after a local emergency call during the configured grace window |
|  4950 | **Answered Caller** | Allow | Numbers answered repeatedly inside the configured lookback window |
|  4900 | **Repeated Urgent** | Allow | Same number calls 2x in 5 min → allowed through |
|  4850 | **Caller Name Trust** | Allow | Carrier-presented name matches one of your trust patterns (requires the device to provide a name during screening) |
|  4700 | **Push-Alert Bridge** (A3) | Allow | Uber/DoorDash/Amazon/Gmail notification about an arriving call? Let it through |
|  4500 | *Campaign Recorder* |. | Side-effect only; feeds burst detection below |
|  4300 | **Region Rules** | Block | Opt-in offline blocking outside the US/Canadian regions and country calling codes you allow |
|  4000 | **Quiet Hours** | Block | Block all non-contact calls during configurable hours (calls only) |
|  3500 | **Frequency Auto-Block** | Block | With call log access, numbers with 3+ incoming calls that rang in 7 days get auto-blocked; screened blocks and silences don't count |
|  3000 | **Heuristic Engine** | Block | North American VoIP ranges, neighbor spoofing, toll-free and rapid-fire checks; international risk checks also run |
|  2500 | **Campaign Burst** | Block | NPA-NXX prefix clustering detects coordinated spam waves |
|  2250 | **Caller Name Rules** | Block | Carrier-presented names can match bounded, user-defined `*`/`?` patterns after every allow layer (requires the device to provide a name during screening) |
|  2000 | **ML Spam Scorer** | Block | 20-feature on-device gradient-boosted tree model |
|  1500 | **Meeting Mode** | Silence | Opt-in. While a meeting app you pick shows a call in progress, calls from outside your contacts go quietly to voicemail. Runs on notification access, with no calendar permission |

SMS-specific layers (append after the shared chain, in their own priority order): **SMS Keyword Rules** (5400, with schedule) → **SMS Context Trust** (4700, trusted-sender allow) → **SMS Burst Protection** (4650) → **SMS Content Analysis** (1900. 30+ regex patterns, URL shorteners, suspicious TLDs, spam domain blocklist).

> **A blocked text still reaches your inbox.** Calls are different from
> messages here, and the table above is about verdicts, not delivery. CallShield
> screens calls through Android's `CallScreeningService`, which can actually
> reject a call. For messages it listens on `SMS_RECEIVED_ACTION`, and since
> Android 4.4 that broadcast cannot be aborted by anyone. Only the default SMS
> app receives `SMS_DELIVER_ACTION` and controls what lands in the inbox, and
> becoming the default SMS app would mean replacing your whole messaging app.
> So an SMS or RCS verdict logs the message, notifies you, and feeds the
> statistics. It does not delete the message or stop it arriving.

### Additional Layers
- **Caller ID Overlay**. Suspicious calls (heuristic score 30-59) can use an explicit, default-off live enrichment option that checks SkipCalls. Clean calls never trigger it
- **Region & caller-name rules**. Opt-in offline blocking outside the US states, Canadian provinces, area codes (`+1809`) and country calling codes (`+39`) you allow, plus bounded `*`/`?` trust and block patterns for carrier-presented caller names. Explicit number, system, prefix and wildcard blocks, and all allow layers, keep priority. Area codes absent from the pinned NANP table pass through this regional rule
- **Opt-in message notification screening**. Google/Samsung Messages are enabled by default. AOSP Messages, SMS Organizer, Signal, WhatsApp, WhatsApp Business, Gmail, Outlook, and Thunderbird can be enabled individually. Private-messenger/email matches show a separate warning without removing the original notification.
- **URL Safety**. Local spam-domain checks stay on-device. Optional link checks use PhishTank and a six-hour OpenPhish feed. PhishTank receives only the site's base domain
- **STIR/SHAKEN**. Blocks calls failing carrier caller ID verification (Android 11+)
- **Outgoing call check**. Opt-in, through Android's call redirection role. A call you start to a number on your blocklist, in the spam database, on a premium-rate line or on a callback-scam country code is held, and a notification tells you why and offers Call anyway. Contacts and trusted numbers ring through, and a slow check never delays the call. If you wouldn't see the notification, in car mode or under Do Not Disturb for example, the call goes through.
- **Answer & hang up**. Opt-in. A call CallShield would reject is answered without video and hung up after a delay you set (1 to 10 seconds), so spam can't leave a voicemail. Silent voicemail mode, auto-mute and a meeting-mode silence still go to voicemail, and the short answered call never counts toward trusting the caller. Contributed by tikkamasalla
- **After-Call Feedback**. "Was this spam?" notification after suspicious calls, plus an optional Android 11+ post-call screen for block/report and save-contact actions

### Per-Rule Schedules (A7)
Any wildcard, range, or SMS keyword rule can be time-gated to specific days of the week and an hour window. The hour picker supports overnight wrap. `daysMask = 0` is the "no gating" sentinel, so rules created before v1.6 behave identically.

## Live Caller ID Overlay

For a locally suspicious call, CallShield can show a real-time overlay when the default-off **Live caller enrichment** setting is on. It checks the number against SkipCalls' spam reports:

```
┌──────────────────────────────────┐
│ LIKELY SPAM                      │
│ (212) 555-1234                   │
│ New York, NY                     │
│ Spam Score: 50% (Flagged)        │
│ ⚠ SkipCalls: Flagged             │
│ All sources checked              │
│ [Search] [Block] [Dismiss]       │
│ 🔈 Play SIT Tone (anti-dialer)  │
└──────────────────────────────────┘
```

- Shows instantly with the area code, then updates when SkipCalls answers. A scam, robocall, telemarketer or fraud category can flag the call. An answer without one of those categories reads "Reported, no category" and leaves CallShield's own warning in place. SkipCalls gives no report count
- If SkipCalls can't answer, the overlay keeps CallShield's own warning and says no source returned a definitive result
- **SIT Tone**. ITU-T E.180 three-tone sequence tricks autodialers into removing your number
- Color-coded: green (safe) → yellow → orange → red (spam)

PhoneBlock, OpenCNAM and WhoCalledMe were dropped in September 2026. PhoneBlock and OpenCNAM now require accounts, which CallShield never asks for, and WhoCalledMe's domain is parked. `scripts/probe_live_sources.py` checks that SkipCalls still answers the way the app reads it.

## ML Spam Scorer

On-device **20-feature gradient-boosted tree** model. Pure Kotlin, no TFLite, no heavy ML libraries. Runs in microseconds.

| Feature | Description |
|---------|------------|
| toll_free | 800/888/877/etc. prefix |
| high_spam_npa | Area code in high FTC/FCC complaint set |
| voip_range | NPA-NXX in known VoIP spam carrier range |
| repeated_digits_ratio | Fraction of most-common digit |
| sequential_asc/desc_ratio | Sequential digit pairs |
| all_same_digit | All 10 digits identical |
| nxx_555 | Exchange is 555 (test numbers) |
| last4_zero | Subscriber is 0000 |
| invalid_nxx | NXX starts with 0 or 1 (NANP-invalid) |
| subscriber_all_same | Last 4 digits all same (9999) |
| alternating_pattern | Even/odd positions uniform (5050505050) |
| nxx_below_200 | Often unassigned ranges |
| low_digit_entropy | Fewer than 4 distinct digits |
| subscriber_sequential | Last 4 form ascending/descending run |
| + 6 additional | Campaign proximity, time-of-day, call frequency, area code density, prefix heat, neighbor spoof score |

Trained by hand on the maintainer's machine from the CallShield database (50K positive + 50K negative samples). The scorer uses the threshold stored in the weights file, which is 0.648 for the model that ships today.

SMS content regressions use a separate CC0, CallShield-authored synthetic
corpus covering seven locales, sender forms, link classes, legitimate messages,
and hard negatives. The test reports precision, recall, and false-positive rate
by locale and message type without shipping personal data:
`./gradlew :app:testDebugUnitTest --tests com.sysadmindoc.callshield.data.SmsEvaluationCorpusTest`.

## Features

### Number Lookup
- Instant spam check through all 15+ detection layers, with an animated confidence gauge for probabilistic signals
- Auto-paste from clipboard, area code lookup (453 active geographic NANP codes), haptic feedback
- Verdict cards lead with the deciding rule or causal signal, show confidence only for
  probabilistic layers, and keep “This is not spam” / “Remove my rule” actions visible
- On-request SkipCalls spam lookup

### Recent Calls & Blocked Log
- Recent calls with contact names, risk indicators, call type icons, filter chips (All/Missed/Spam)
- Blocked log with swipe-to-dismiss + undo, grouping with severity-scaled accent bars, filter chips.
  Swipe actions also have equivalent TalkBack/switch-access actions and 48dp touch targets
- Staggered entrance animations, shimmer loading skeletons

### Assistive Telephony
- RTT calls receive an immediate allow from the screening service. CallShield doesn't reject,
  silence, or launch the caller-ID overlay for an active RTT session.
- Block reasons are spoken as complete plain-English sentences, while swipe-only block, delete,
  and unblock actions remain available through accessibility actions.

### Rules Management (5 tabs)
- Blocklist, Wildcards, Keywords, Whitelist, Database
- Export/import blocklists as JSON, per-rule enable/disable toggles
- Regex validation before adding wildcard rules
- Inline priority-conflict warnings name the whitelist, emergency allow, or block rule that wins before an overlapping rule is saved

### Statistics
- Weekly bar chart with daily breakdown
- Detection source donut chart
- Monthly trend line
- Top offenders, area code heatmap, hourly heatmap
- Protection Test shows local WorkManager attempts and Android stop reasons, with a warning when background quota repeatedly defers protection refreshes

### Smart Features
- Smart suggestions. Detects area code spam patterns, one-tap block entire area code
- Weekly trend indicator. Shows if spam is increasing or decreasing vs last week
- Last blocked preview card on dashboard with tap-to-inspect
- Blocking profiles: Recommended / Strict / Contacts only / Personal / Sleep / Off, with Undo
- Callback detection + repeated urgent caller allow-through
- FTC Do Not Call complaint filing
- After-call "Was this spam?" feedback notification

### Home Screen Widget
- Today vs yesterday blocked count with trend indicator
- Last blocked number and time
- Quick-access to lookup and protection status

### Community
- **One-tap anonymous contribution** via [Cloudflare Worker](https://callshield-reports.snafumatthew.workers.dev). Each number and vote goes out at most once a day, and a report made offline is sent when the connection returns
- "Not spam" reports can put a community-only number up for maintainer review. They never remove a number by themselves
- Share spam warnings to any app

### Data & System
- Selective backup/restore for rules, non-secret settings, and opt-in logs, plus CSV log export and auto-cleanup (7/14/30/90 days)
- Database sync every 6 hours, a trending-feed check every 30 minutes, and a daily digest notification
- External blocklist subscriptions (Settings > External blocklists) take HTTPS CSV, TXT or JSON number lists of up to 1 MB and 20,000 rows. Each list is fetched again once a day, or on the interval it declares in its header (`# Expires: 12 hours`, or `"expires": "12h"` in JSON), never more often than every six hours and at least weekly. A download that comes back empty or with under half the list's numbers isn't applied in the background. The list keeps its last good copy and says why on its row. Each row shows the list's name, the host it comes from, how many numbers it holds and when it last updated, and TalkBack reads it as one item. A row's switch turns its list off or on; a tap on the list's name does nothing. Removing a list takes its numbers out straight away, and Undo puts both back without downloading the list again
- If GitHub is blocked where you live, Settings > Feed mirror takes a second address for the protection data. CallShield asks GitHub first, then the mirror, then falls back to the copy bundled with the app. For ten minutes after GitHub couldn't be reached at all, the mirror goes first. One tap fills in jsDelivr (`https://cdn.jsdelivr.net/gh/SysAdminDoc/CallShield@master/`), which serves the same files and can run up to 12 hours behind. Mirrored files go through the same signature check, so a mirror can't alter the data, though it can hold back updates. [data/README.md](data/README.md#mirrors-and-recovery) covers running your own
- Quick Settings tile, app shortcuts, home screen widget
- Protection test validates all layers and permissions, including checker errors and deadline cutoffs
- Rules surface priority conflicts after sync and edits, with the winning rule and a review path
- Home leads with localized blocked-call/text outcomes and collapses completed setup into a review row
- Optional weekly GitHub Releases update checks are off by default and only offer release/SHA256 links
- Maintainer data regeneration uses the gated `scripts/import_all_sources.py` pipeline. Legacy direct writers aren't supported
- Onboarding wizard with permission requests

## Data Sources

### Database (51,357 numbers + 653 range prefixes, locally maintained)
| Source | Method |
|--------|--------|
| **FCC Consumer Complaints** | Socrata API, 500K records, min 2 reports |
| **FTC Do Not Call** | `api.ftc.gov` (DEMO_KEY) |
| **Saracroche** | Daily French telemarketing ranges; imported as compact prefixes |
| **PhoneBlock** | Optional authenticated bulk snapshot, maintainer import only |
| **Nomorobo IRS** | Optional carrier-authorized callback-scam CSV feed |
| **ToastedSpam** | Community curated list |
| **Community Reports** | Anonymous via Cloudflare Worker |

The source importer also has optional adapters for **PhoneBlock's** versioned
bulk list. PhoneBlock requires an account for bulk downloads, so that adapter
runs only when the maintainer has access, and the app itself no longer contacts
PhoneBlock. Run `python scripts/import_all_sources.py --include-saracroche` to
refresh the French ranges. Add `--phoneblock-limit 5000` and
`PHONEBLOCK_API_KEY` only when the maintainer has bulk-feed access. Saracroche
range data is published under CC BY-NC-SA 4.0, must retain attribution and
those downstream restrictions, and is accepted only as a `+33` French
allocation. A successful non-empty refresh expires removed Saracroche ranges.
A failed or empty response keeps the last known good set.

Every feed is declared in `data/source-manifest.json` with its access mode,
geography, licence, attribution, parser version, redistribution policy, and
freshness window. Each importer run writes a local `data/source-snapshot.json`
for release review. It's deliberately left out of the APK. After the
community merge, its health section adds per-source freshness, accepted and
quarantine counts, corroboration, and bounded false-positive rates without
copying phone numbers, contacts, SMS, or call audio. Anonymous `not_spam`
votes never change the database by themselves. Once they outnumber a
community-only row's reports, the row becomes a review candidate. After an
operator marks it `approved: true`,
`python scripts/merge_community_reports.py --apply-reviewed-corrections`
can decay or remove only that community-only contribution. FTC and FCC runs
persist bounded high-water cursors in `data/source-cursors.json`, which the
importer creates on its first run. They keep caller-ID and callback-business
evidence as separate roles, and don't promote unverified complaint-only rows
without independent caller corroboration. The importer leaves out complaints
with missing or future dates. `data/source-freshness.json` records the newest
complaint date, and the weekly check flags an FTC or FCC feed when that date
falls outside its freshness window.

The importer also accepts a carrier-authorized Nomorobo IRS callback-scam CSV
feed without embedding credentials in the app. Pass the HTTPS URL with
`--nomorobo-irs-url` (or `NOMOROBO_IRS_FEED_URL`) and, if required by the feed,
the bearer token with `--nomorobo-irs-token`/`NOMOROBO_IRS_TOKEN`. The adapter
is disabled unless explicitly configured and rejects cleartext URLs. It doesn't
scrape Nomorobo's restricted carrier feed.

### Hot List (checked every 30 minutes, regenerated by hand)
| File | Contents |
|------|----------|
| `hot_numbers.json` | Top 500 trending numbers (last 24h) |
| `hot_ranges.json` | NPA-NXX prefixes where at least 4 trending numbers drew reports from at least 6 reporters between them |
| `spam_domains.json` | Phishing/spam domains from community SMS reports |

### Real-Time Lookup (overlay only)
| Source | What It Returns | Auth |
|--------|----------------|------|
| **SkipCalls** | Spam flag and category | None |

### URL Safety (post-decision)
| Source | What It Checks |
|--------|---------------|
| **Local spam-domain list** | Checks known spam domains on the phone |
| **PhishTank** | Optional link lookup; receives only the site's base domain |
| **OpenPhish** | Optional feed checked for updates after six hours of use; matches stay on the phone |

## Security

- **Network security config**. Cleartext traffic disabled in production
- **Signing credentials**. Stored in `local.properties`, not hardcoded in build files
- **Restricted FileProvider paths**. Scoped to export directory only
- **Scoped backup**. Cloud backup includes non-secret settings only. The
  sensitive database is limited to direct device transfer and explicit
  user-created portable backups
- **APK privacy gate**. Builds package only the five runtime protection feeds.
  Verification rejects raw community submissions and maintainer files
- **Direct-boot boundary**. A minimal device-encrypted mirror keeps explicit
  user blocks active before first unlock. The full database and settings remain
  credential-encrypted
- **Community report abuse controls**. The Worker requires a Cloudflare client
  identity and separates malformed requests from unavailable or corrupt rate-limit state.
  It counts an IPv6 client by its /64, and trending corroboration takes at most two /64s from one /48, so one
  subscriber can't pass as many reporters by rotating addresses

## Privacy

Call and message decisions run on-device. No personal data is collected. Network requests:
- Syncing spam database from GitHub (public)
- Optional live caller enrichment is off by default and runs only for locally suspicious calls. The setting names every destination host before a number is shared
- Community reports to Cloudflare Worker (phone number only, no identity)
- Local spam-domain checks don't disclose SMS or RCS links. Optional PhishTank lookups send only the site's base domain, and the OpenPhish feed is downloaded for local matching. Neither receives message text

No API keys. None required, none optional, no credential entry anywhere in the app. No accounts. No analytics. No ads.

## Requirements

- Android 10+ (API 29)
- STIR/SHAKEN requires Android 11+ (API 30)
- Caller ID overlay requires "Display over other apps" permission
- RCS filter requires Notification Access permission

## Installing

Every release from v1.7.37 onward is signed with a new key. The original
keystore password was lost in a machine rebuild, which also meant releases
v1.7.26 through v1.7.29 shipped unsigned and would not install at all, so the
key was rotated rather than recovered.

**Upgrading over an older install can fail with a signature mismatch.** Android
refuses to replace an app with a build signed by a different key, and releases
before v1.7.37 were signed with keys that are no longer available. If the
install fails, you have to uninstall first, which erases your rules and logs,
so export them before you do:

1. Open CallShield, go to **More → Backup and restore**, and create a portable
   backup. Save it somewhere outside the app, and set a passphrase if the
   backup includes logs.
2. Uninstall CallShield.
3. Install the new APK and restore from that file.

A fresh install is unaffected, and upgrades between v1.7.37 and later releases
work normally.

Verify an APK before installing it:

```bash
apksigner verify --print-certs CallShield-vX.Y.Z.apk
```

The signer certificate SHA-256 must be:

```
920e583ae6ce9f3863a6b3b8847e927d53a66c38a245e12e30ce124c9f4a75f5
```

Every release also ships a `.sha256` sidecar for the APK itself.

### Permissions look granted but nothing works

On Android 13 and later a sideloaded app is put behind **restricted settings**.
The system hides the SMS role and notification access from an app that was not
installed by an app store, and the permission dialog either never appears or
appears and changes nothing. GrapheneOS and CalyxOS apply this the same way
stock Android does, and it is the most likely reason CallShield sees no
messages after a clean sideload.

To lift it:

1. Long-press the CallShield icon and open **App info** (or Settings → Apps →
   CallShield).
2. Tap the **⋮** menu in the top right and choose **Allow restricted settings**.
3. Confirm with your device PIN or biometric.
4. Reopen CallShield and grant the permissions again. Onboarding rechecks each
   one and will move on by itself once they take.

Setup points you here too. A step you were sent to grant that comes back still
off shows this hint, with a button that opens App info.

If the ⋮ menu has no such entry, the permission is actually denied rather than
restricted. Grant it from **App info → Permissions**. A permission you denied
twice is treated as permanently denied by Android, and CallShield's onboarding
sends you to App info instead of re-prompting, because the prompt would no
longer show.

Installing through a client that registers as the installing package (Obtainium
and F-Droid both do) avoids the restriction entirely.

## Building

```bash
./gradlew verifyReleaseMetadata verifyReproducibleBuildInputs verifyReleaseSbom verifyReleaseApkReproducibleMetadata
```

`verifyReleaseMetadata` invokes `scripts/verify_release_drift.py`, which prints
the synchronized app/F-Droid/changelog versions, locked dependency summary,
known-advisory dispositions, and source-snapshot provenance. Run the report
directly with `python scripts/verify_release_drift.py` when reviewing metadata
without building an APK.

Requires JDK 17+. With release signing properties configured, the signed APK is
at `app/build/outputs/apk/release/app-release.apk`. Without them, the local
verification build emits `app-release-unsigned.apk`.
Generate the release hash sidecar with:

```powershell
.\scripts\write-release-sha256.ps1
```

`verifyReleaseSbom` also writes `<release-apk-stem>.cdx.json`,
`<release-apk-stem>.provenance.json`, and `<release-apk-stem>.sha256` beside the
APK. The SBOM contains the exact `releaseRuntimeClasspath` coordinates from
`app/gradle.lockfile`, and the verifier fails if the APK, lockfile, SBOM, or
provenance record drift. CallShield releases are built locally, so the signed APK remains
governed by the maintainer release key and its SHA-256 sidecar. The local JSON
provenance is evidence, not a replacement for that signature.

See `docs/reproducible-builds.md` for the dependency-lock and hash-comparison
runbook.

F-Droid submission prep lives in `fastlane/metadata/android/en-US/`,
`docs/fdroid/com.sysadmindoc.callshield.yml`, and
`docs/fdroid-submission.md`. The actual F-Droid merge request and signature-copy
verification still require an fdroiddata/GitLab environment.

**Signing:** Create `local.properties` in the project root with your keystore credentials:
```properties
RELEASE_STORE_FILE=path/to/keystore.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

## Testing

```bash
./gradlew testDebugUnitTest   # 1556 tests
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.sysadmindoc.callshield.platform.TargetSdkBehaviorSmokeTest
./gradlew verifyPipelineTests # Cloudflare Worker (node) + data-pipeline and translation checks (python)
```

The suite is **1556 total JVM unit tests**, run with Robolectric wherever a screen, a service or the database is involved.

Two GitHub workflows run without building the app. **Validation** runs the Worker and
pipeline suites on every push except report-only ones (`run-pipeline-tests.ps1 -CorrectnessOnly`),
and the runner fails if any suite changes a file under `data/`. **Pipeline
liveness** runs weekly: it fails when community reports sit unconsumed for over a week
or a daily or weekly upstream source misses its `stale_after_days`, and it keeps one issue
labelled `pipeline-stalled` open until the next passing run. A source that only imports with
an opt-in flag, such as `--include-saracroche`, is held to its limit once it has been imported.

Run tests, lint, release metadata checks, and artifact builds locally before publishing.

## Translations

CallShield ships in English with a substantial Simplified Chinese translation.
More translations are welcome. See
[docs/TRANSLATING.md](docs/TRANSLATING.md) for the resource layout, the priority
order for partial translations, and `scripts/check_translations.py`, which
verifies format specifiers and plural coverage before a PR lands. Claim a
language in [issue #7](https://github.com/SysAdminDoc/CallShield/issues/7).

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin 2.3.21 |
| UI | Jetpack Compose BOM 2026.06.01 + Material 3 |
| Theme | System, Light, Graphite, and true-black AMOLED |
| Database | Room 2.8.5 (SQLite). 8 entities |
| Networking | OkHttp 5.4.0 + certificate pinning |
| JSON | Moshi |
| ML | Pure Kotlin gradient-boosted tree (20 features) |
| Settings | DataStore Preferences 1.2.1 |
| Background | WorkManager 2.11.2 |
| Community API | Cloudflare Workers |
| URL Safety | Local spam-domain data; optional PhishTank and OpenPhish |
| Verification | Local Gradle, lint, and release-artifact checks |
| Tests | 1559 JVM unit tests (JUnit) |
| Strings | 1637 string resources and 38 plural groups (translation-ready) |
| Accessibility | 100+ content descriptions, 48dp touch targets |
| Min SDK | 29 (Android 10) |
| Target SDK | 36 |

## License

MIT
