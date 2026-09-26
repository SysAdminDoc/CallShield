# Getting Started with CallShield

## Install

Download the APK from the [latest GitHub release](https://github.com/SysAdminDoc/CallShield/releases/latest) and install it. Android will ask to allow installs from this source if you haven't already.

If you're upgrading from a version before v1.7.37, Android may refuse the install because the signing key changed. Export your settings first (Settings > Advanced > Backup & restore), uninstall, then install the new APK and restore.

## First run

The setup wizard walks through five Android permissions, one screen each. The first two are required. The other three can be skipped and turned on later from Settings.

1. **Phone & messages.** Access to calls, contacts and messages, so screening and the spam checks can read the caller's number and screen texts.
2. **Call screening.** Makes CallShield the call-screening app, so Android asks it about unknown calls before they ring.
3. **Protection alerts** (optional). Notifications for blocked calls, flagged texts, the daily digest and protection health.
4. **Caller ID overlay** (optional). Shows the caller's details and risk over the incoming-call screen.
5. **Notification access** (optional). Lets CallShield read message notifications from Google Messages, Samsung Messages and other apps, so it can flag RCS spam.

Setup then checks that the required access really was granted and asks how strict to be: **Recommended**, **Strict** or **Contacts only**. You can change the level any time on Home or in Settings. After setup, Home runs the first database sync, and once it finishes CallShield is protecting you.

## Recommended starting profile

The defaults work well for most people. Out of the box you get:

- **Database matching** against 51,000+ reported spam numbers
- **Heuristics** such as neighbor spoofing, STIR/SHAKEN, wangiri and premium-rate detection
- **An on-device ML scorer**, a gradient-boosted tree trained on the spam database
- **SMS content analysis** in English, Spanish, Portuguese and Italian
- **Trending feeds**, checked every 30 minutes
- **Community reporting**, so your reports help everyone else

A few things are off by default and worth a look:

| Setting | What it does | Turn it on if |
|---------|-------------|---------------|
| Notification access (setup, or Settings > Access status) | Screens spam notifications from messaging apps | You use Google or Samsung Messages |
| Caller ID overlay (setup, or Settings > Access status) | Shows the caller's risk over the incoming-call screen | You want the verdict before answering |
| Block unknown numbers | Blocks calls with no caller ID | You rarely get genuine private-number calls |
| Contacts-only mode | Only contacts and trusted callers ring | You want strict protection during a spam wave |
| Check links with PhishTank and OpenPhish | Checks links in texts against phishing lists | You want phishing link detection |

## Settings reference

Settings opens on **Basic**, the everyday switches. **Advanced** holds detection, lists, exports and backup. Each section below matches a card in the app, with the DataStore key and default of every setting it holds. Rows without a key open a list, a dialog or an Android screen.

### Protection level

Recommended, Strict or Contacts only (`active_profile_name`, Recommended after setup). A level sets several switches at once. Change one yourself and the card says your settings are custom, with a button to restore the level.

### Access status

Shows whether Phone & messages, Call screening, the caller ID overlay, notifications and notification access are granted, with a Grant or Enable button for each one that isn't. Advanced always shows it. Basic shows it only while something required is missing.

### Appearance

| Setting | Key | Default |
|---------|-----|---------|
| Theme | `app_theme` | AMOLED black |
| Language | Android's per-app language | System default |

### Blocking

| Setting | Key | Default |
|---------|-----|---------|
| Block spam calls | `block_calls_enabled` | on |
| Analyze spam SMS | `block_sms_enabled` | on |
| Block unknown numbers | `block_unknown_enabled` | off |
| Call handling by category | `category_call_actions` | every category follows the global setting |

### Safety

| Setting | Key | Default |
|---------|-----|---------|
| Trust contacts | `contact_whitelist_enabled` | on |
| Allowed contact scope | `selected_contact_group_keys` | all contacts |
| Contacts-only mode | `contacts_only_mode_enabled` | off |
| Warn before known-risk outgoing calls | `outgoing_risk_warning_enabled` | off |
| Stop calls to flagged numbers | `outgoing_call_hold_enabled` | off (needs the call redirection role) |
| Region & caller-name rules | `region_block_enabled`, `allowed_call_regions`, `cnap_trust_patterns`, `cnap_block_patterns` | off, all empty |

Allowed regions take two-letter US state and Canadian province codes (`NY`, `ON`), `TF` for toll-free, and `+` with a calling code for a country (`+39`). A Caribbean country that shares +1 goes in by each of its area codes, so the Dominican Republic is `+1809`, `+1829` and `+1849`. A bare `+1` isn't accepted.

### Notifications

| Setting | Key | Default |
|---------|-----|---------|
| Android post-call screen | `post_call_screen_enabled` | off |
| Notifications | Android's notification permission | asked during setup |

### Quiet hours

| Setting | Key | Default |
|---------|-----|---------|
| Block unknowns during quiet hours | `time_block_enabled` | off |
| Start | `time_block_start_hour` | 22 |
| End | `time_block_end_hour` | 7 |

### Telemarketing ranges

| Setting | Key | Default |
|---------|-----|---------|
| Block Spain 400 telemarketing | `reg_spain_400_enabled` | off |
| Block India 140 promotional | `reg_india_140_enabled` | off |
| Block Brazil 0303 telemarketing | `reg_brazil_0303_enabled` | off |
| Protect India 1600 series | `reg_india_1600_allow_enabled` | off |

### Detection engines

| Setting | Key | Default |
|---------|-----|---------|
| STIR/SHAKEN | `stir_shaken_enabled` | on |
| STIR/SHAKEN authenticated allow | `stir_trusted_allow_enabled` | on |
| Answered-caller trust | `answered_caller_trust_enabled` | on |
| Answered calls | `answered_caller_trust_threshold` | 2 |
| Lookback window (days) | `answered_caller_trust_window_days` | 30 |
| Emergency callback grace | `emergency_callback_grace_enabled` | on |
| Grace window (minutes) | `emergency_callback_grace_window_minutes` | 240 |
| Neighbor spoofing | `neighbor_spoof_enabled` | on |
| Heuristic analysis | `heuristics_enabled` | on |
| SMS content analysis | `sms_content_analysis_enabled` | on |
| Check links with PhishTank and OpenPhish | `urlhaus_remote_lookup_enabled` | off |
| Live caller enrichment | `live_caller_enrichment_enabled` | off |
| SMS burst protection | `sms_burst_detection_enabled` | on |
| Repeat caller auto-block | `freq_escalation_enabled` | on |
| Call count | `freq_threshold` | 3 |
| ML spam scorer | `ml_scorer_enabled` | on |
| Database prefix expansion | `db_prefix_expansion_enabled` | off |
| Message notification screening | `rcs_filter_enabled` | on |
| Choose screened apps | `notification_screening_packages` | the built-in list of messaging apps |
| Push-alert bridge | `push_alert_enabled` | on |
| Configure trusted sources | `push_alert_disabled_packages` | every source trusted |
| Silent voicemail mode | `silent_voicemail_mode` | off |
| Auto-mute low-confidence blocks | `automute_low_confidence_enabled` | off |
| Answer & hang up blocked calls | `answer_hang_up_mode` | off |
| Hang up after (seconds) | `hang_up_delay_seconds` | 1 |

### Meeting mode

| Setting | Key | Default |
|---------|-----|---------|
| Silence unknown callers in meetings | `meeting_mode_enabled` | off |
| Choose meeting apps | `meeting_mode_packages` | none |

### Power mode

| Setting | Key | Default |
|---------|-----|---------|
| Aggressive blocking | `aggressive_mode_enabled` | off |

### Log cleanup

| Setting | Key | Default |
|---------|-----|---------|
| Auto-cleanup old entries | `auto_cleanup_enabled` | off |
| Keep for (7, 14, 30 or 90 days) | `cleanup_retention_days` | 30 |

### Export

Three CSV exports of the blocked log: the everyday log, blocked calls laid out for a regulator or carrier complaint, and a separate file with the original text of blocked messages, which warns you first since it holds message text.

### Backup & restore

Choose which sections go into a backup (rules, settings, the log) and which a restore brings back, and whether a restore merges with what's here or replaces it. Backups are plain JSON files. They never hold secrets or this phone's sync state.

### External blocklists

Subscribe to number lists published as HTTPS CSV, TXT or JSON (`external_blocklist_subscriptions`, none by default). CallShield previews what a list would change, caps how much it imports and keeps each list under its own source, so removing a list removes only its numbers.

### Feed mirror

A second address for CallShield's protection data, for places where GitHub is blocked (`feed_mirror_url`, none by default). GitHub goes first, except for a few minutes after it couldn't be reached. Every file still has to carry the project's signature, so a mirror can't change the data, though it can hold back updates.
