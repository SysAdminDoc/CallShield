# Getting Started with CallShield

## Install

Download the APK from the [latest GitHub release](https://github.com/SysAdminDoc/CallShield/releases/latest) and install it. Android will ask to allow installs from this source if you haven't already.

If you're upgrading from a version before v1.7.37, Android may refuse the install because the signing key changed. Export your settings first (More > Backup and restore), uninstall, then install the new APK and restore.

## First run

The setup wizard asks for three things:

1. **Call Screening role.** This lets CallShield see incoming calls before they ring and decide whether to block them.
2. **Phone and SMS permissions.** Needed to read the caller's number and to screen text messages.
3. **Notification Access** (optional). Lets the RCS notification listener filter spam texts from Google Messages, Samsung Messages, and other messaging apps.

After setup, the Home screen runs an initial database sync. Once that finishes, CallShield is protecting you.

## Recommended starting profile

The defaults work well for most people. Out of the box you get:

- **Database matching** against 51,000+ reported spam numbers
- **Heuristic engine** (neighbor spoofing, STIR/SHAKEN, wangiri, premium-rate detection)
- **ML scorer** (gradient-boosted tree trained on the spam database)
- **SMS content analysis** in English, Spanish, Portuguese, and Italian
- **Trending feeds** refreshed every 30 minutes
- **Community reporting** so your reports help everyone else

Things that are off by default and worth considering:

| Setting | What it does | Turn it on if |
|---------|-------------|---------------|
| Notification Access | Filters spam notifications from messaging apps | You use Google/Samsung Messages |
| Caller ID Overlay | Shows a risk badge on incoming calls | You want to see the verdict before answering |
| Block unknown callers | Blocks calls with no caller ID | You rarely get legitimate private-number calls |
| Contacts-only mode | Blocks everything not in your contacts | You want strict protection during a spam wave |
| URLhaus remote lookup | Checks URLs in texts against the abuse.ch feed | You want phishing link detection |

## Settings reference

Every user-facing toggle with its DataStore key and default value.

### Protection

| Setting | Key | Default |
|---------|-----|---------|
| Block calls | `block_calls_enabled` | on |
| Block SMS | `block_sms_enabled` | on |
| Block unknown callers | `block_unknown_enabled` | off |
| STIR/SHAKEN verification | `stir_shaken_enabled` | on |
| STIR/SHAKEN trusted allow | `stir_trusted_allow_enabled` | on |
| Heuristics engine | `heuristics_enabled` | on |
| Neighbor spoof detection | `neighbor_spoof_enabled` | on |
| Auto-mute low confidence | `automute_low_confidence_enabled` | off |
| SMS content analysis | `sms_content_analysis_enabled` | on |
| SMS burst detection | `sms_burst_detection_enabled` | on |
| Database prefix expansion | `db_prefix_expansion_enabled` | off |
| Aggressive mode | `aggressive_mode_enabled` | off |

### Contacts and trust

| Setting | Key | Default |
|---------|-----|---------|
| Contact whitelist | `contact_whitelist_enabled` | on |
| Contacts-only mode | `contacts_only_mode_enabled` | off |
| Answered-caller trust | `answered_caller_trust_enabled` | on |
| Answered-caller threshold | `answered_caller_trust_threshold` | 2 |
| Answered-caller window (days) | `answered_caller_trust_window_days` | 30 |
| Emergency callback grace | `emergency_callback_grace_enabled` | on |
| Emergency callback window (min) | `emergency_callback_grace_window_minutes` | 120 |

### Time and frequency

| Setting | Key | Default |
|---------|-----|---------|
| Time-based blocking | `time_block_enabled` | off |
| Quiet hours start | `time_block_start_hour` | 22 |
| Quiet hours end | `time_block_end_hour` | 7 |
| Meeting mode | `meeting_mode_enabled` | off |
| Meeting apps | `meeting_mode_packages` | empty |
| Frequency escalation | `freq_escalation_enabled` | on |

### Enrichment and lookup

| Setting | Key | Default |
|---------|-----|---------|
| Live caller enrichment | `live_caller_enrichment_enabled` | off |
| URLhaus strip query | `urlhaus_strip_query_enabled` | on |
| URLhaus remote lookup | `urlhaus_remote_lookup_enabled` | off |
| Outgoing risk warning | `outgoing_risk_warning_enabled` | off |

### Region and caller-name rules

| Setting | Key | Default |
|---------|-----|---------|
| Region block | `region_block_enabled` | off |
| Allowed regions | `allowed_call_regions` | empty |
| Caller-name trust patterns | `cnap_trust_patterns` | empty |
| Caller-name block patterns | `cnap_block_patterns` | empty |

Allowed regions take two-letter US state and Canadian province codes (`NY`, `ON`), `TF` for toll-free, and `+` with a calling code for a country (`+39`, or `+1809` for a Caribbean country that shares +1).

### Telemarketing ranges

| Setting | Key | Default |
|---------|-----|---------|
| Block Spain 400 telemarketing | `reg_spain_400_enabled` | off |
| Block India 140 promotional | `reg_india_140_enabled` | off |
| Block Brazil 0303 telemarketing | `reg_brazil_0303_enabled` | off |
| Protect India 1600 series | `reg_india_1600_allow_enabled` | off |
