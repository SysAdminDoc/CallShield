# Changelog

All notable changes to CallShield will be documented in this file.

## Unreleased

### Added

- Tapping a "Block area code" smart suggestion now asks for confirmation (showing
  the coverage — roughly 7.9 million numbers) before adding the rule, and shows a
  toast when it's applied. The auto-generated rule description is now a localized
  string instead of a hardcoded English literal.

### Fixed

- External-link, dial, and settings intents (blocked-log/recent-calls web search,
  number-detail GitHub report and dial, overlay/notification/app-settings launches
  across Dashboard, Settings, Onboarding, and Protection Test) no longer crash on
  browserless devices or ROMs missing the target activity — they fall back to the
  app-details screen or a toast, via a shared `startActivitySafely` helper.
- The caller-ID overlay's paddings, accent line, corner radii, and button spacing
  are now density-scaled instead of raw pixels, so it renders with consistent
  proportions across mdpi–xxxhdpi devices (was oversized on low-density, cramped
  on high-density screens).
- Settings toggle titles (Power mode, Aggressive blocking, Quiet hours, Silent
  voicemail mode, Log cleanup) and the export/restore-defaults buttons now use
  sentence case, matching the screen's section headers.
- The More screen's "Synced Xm ago" label now advances while the screen stays
  open instead of freezing at the value it had when the screen opened.
- `PremiumCard`'s accent color now renders as a subtle hairline border for cards
  that opt into one (it was previously an ignored parameter).
- Backup-restore and external-blocklist status lines now carry a typed
  success/failure flag from the view model instead of the UI guessing from the
  (localized) message text with `startsWith("Restored ")` / `"Applied"` — success
  vs error coloring stays correct under non-English locales and pseudolocales.
- Rule schedule labels now use localized day names and translatable "Every
  day"/"Mon–Fri"/"Weekends" strings, and the auto-generated rule descriptions
  written on "not spam" reports and blocked-log swipe-to-block are now localized
  string resources instead of hardcoded English.

### Performance

- Wildcard/regex blocking rules now compile their pattern once and reuse it across
  screening calls (process-lifetime compiled-`Regex` cache) instead of re-escaping
  and recompiling per rule per incoming call on the 5-second hot path.

## v1.7.25 — 2026-07-28

### Added

- The repeat-caller auto-block threshold is now adjustable in Settings, and its
  description reflects the configured count (it was engine-consumed but had no UI).

### Fixed

- Unified the "Trusted" terminology across the trusted-numbers add flow (the tab,
  dialog, button, and confirmation no longer switch between "Trusted"/"Whitelist").
- Lookup, block, and report error messages are now localized generic messages
  instead of leaking raw exception text; the pipeline-trace verdict labels are
  localizable resources.

## v1.7.24 — 2026-07-28

### Security

- The community-report pipeline now validates every reported number for
  plausibility (Cloudflare Worker and merge script): fictional NANP numbers
  (area/exchange 555, N11 service codes), leading-zero "country codes", and
  implausibly short numbers are rejected instead of shipping to all users.
- Anonymous `not_spam` votes can no longer de-list authoritative FCC/FTC
  entries — they may only weaken community-reported rows. This closes a
  path that let an attacker remove real spammers from the shared database.

### Accessibility

- The Dashboard "Block calls"/"Block SMS" toggles, blocklist rule switches,
  schedule toggle, push-alert source rows, and backup-encryption toggle now
  announce their name and state to TalkBack as a single node.
- Blocked-log rows expose Delete and Block as TalkBack custom actions (no
  longer swipe-only), and the block swipe now offers Undo.
- Below Android 13, the app now detects when notifications are disabled in OS
  settings instead of always reporting them ready, and the enable-in-settings
  action is reachable.
- Bottom-nav items and compact buttons no longer announce their label twice;
  the active blocking profile is exposed as a selection state.

### Fixed

- Timestamps in the blocked log, recent calls, and number detail now follow
  the device 12/24-hour and locale conventions instead of forcing AM/PM.
- Haptic feedback honors the system "Touch feedback" setting.
- Backup copy no longer references non-existent API keys; onboarding feature
  text and category action chips no longer clip at large font scales.
- Blocked-call and blocked-SMS notifications from the same number now use
  distinct action intents, so tapping Block/Report affects the right one and
  reports the correct spam type.
- The Quick Settings tile toggle no longer risks leaving call and SMS blocking
  split if the shade is dismissed mid-toggle.
- Orphaned crash-log temp files are swept so they can't accumulate.
- Opening the Blocklist no longer loads the entire spam database into memory —
  the full list is fetched only when the Database tab is viewed.
- SMS content analysis matches URL shorteners and suspicious TLDs against the
  extracted host, not the raw URL — legitimate links like microsoft.com,
  reddit.com, and target.com are no longer flagged as shortened links, and a
  `.info` path segment no longer trips the suspicious-TLD signal.
- Whitelisted, contact, and temporarily-allowed SMS senders are no longer
  blocked by the burst or content heuristics; an explicit user allow now
  carries through to the SMS extension chain (keyword rules still apply).
- The "Neighbor spoofing" detection toggle is now actually honored — turning
  it off stops the neighbor-spoof heuristic from scoring.
- Wildcard rules can no longer be used to stall call screening: runs of glob
  `*` collapse before compilation and regex patterns with long chains of
  open-ended quantifiers are rejected, so a pathological rule can't blow the
  5-second screening deadline.
- Contacts-only mode is calls-only again — an SMS from a non-contact (OTP
  shortcode, bank, delivery) is no longer logged as a blocked call.
- A suspicious SMS re-scored through the shared chain no longer pops the
  incoming-call overlay or fires live caller lookups.
- Wangiri detection no longer flags plus-less local-format numbers whose
  leading digits collide with international country codes.
- Explicit international numbers (`+45…`, `+47…`, `+212…`) are no longer
  mis-formatted as North American, and formatWithCountryCode no longer
  fabricates a `+1` prefix on them.
- Restoring a backup now refreshes the range-rule (hash-wildcard) cache, so
  restored range rules take effect immediately without an app restart.
- Re-applying an external blocklist subscription preserves an active
  temporary block's expiry instead of silently making it permanent.
- Backup restore keeps blocked-SMS log rows from alphanumeric senders
  (e.g. "BANK-ALERT") instead of dropping them on the round-trip.
- Block and whitelist edits are now atomic, so a process death mid-edit can
  no longer strip a trust entry without applying the block.
- The restore preview no longer loads the entire call log (including SMS
  bodies) into memory when logs aren't part of the restore.
- Opening a number's detail from Recent now canonicalizes the number, so a
  national-format call-log entry shows its blocked history, reputation, and
  the correct Block/Unblock state.
- Rotating or otherwise recreating the activity no longer replays a shortcut
  scan, re-opens a closed deep link, or snaps back to the shortcut's tab.
- Undo after removing a temporary block restores it as temporary (keeping its
  expiry) instead of as a permanent block.
- The database-count subtitle and "Setup needed" pill now update immediately
  after import/restore/block and after granting permissions in OS settings.
- The Dashboard "Just now" / sync-freshness labels advance while the screen
  stays open; number-detail lookups reset when the number changes in place.
- Recent Calls now shows system-blocked and voicemail call-log entries with
  their own icons and includes blocked calls in the Missed filter.
- Global search matches formatted phone queries (e.g. "555-123-4567") against
  canonically-stored numbers.
- Onboarding survives rotation, counts the call-screening step as required
  only on devices that have it, and no longer flashes the main UI first-run;
  the model-health card updates once the model finishes loading.
- Portable backups keep the full BackupRestore payload family (settings,
  range rules, logs) from R8 stripping, so a minified release build no longer
  risks silently losing restored data.
- The hot-list generator selects trending numbers by a real calendar date
  instead of a string compare, so corrupt future-dated rows (e.g. year 2915)
  can no longer pin themselves to the top of the bundled hot list.
- The merge script writes the database atomically (temp file + replace),
  quarantines unreadable/malformed reports instead of retrying them forever,
  self-heals implausible legacy rows, and repairs impossible first/last-seen
  dates. Drained the two-month report backlog and purged 426 fictional
  numbers left over from bulk imports.

## v1.7.23 — 2026-07-22

### Fixed

- Phone identity canonicalization now resolves the SIM region before the
  visited network region, so roaming no longer canonicalizes national numbers
  (and the one-time schema v12 migration) under the wrong country. The v12
  migration also skips unchanged rows so the bundled 32k-row database no
  longer pays ~33k no-op writes inside the first call's 5-second screening
  window, keeps community reputation visible after a merged temporary user
  block expires, and preserves the emergency flag when whitelist rows merge.
- The per-category guard for explicit blocks now matches the system block
  list's real match source (`system_block_list`); a typo had made that guard
  dead code. Daily digest source breakdowns now bucket category-policy
  verdicts by their underlying detection source instead of "other".
- Outgoing risk warnings are suppressed for numbers on the personal whitelist
  — the standing false-positive correction no longer re-warns on every dial —
  and the warning overlay ignores the registration-time IDLE snapshot so it
  can no longer dismiss itself before it is readable.
- Repeated-call urgency now recognizes genuinely spaced retries even when a
  provider double-logs one attempt; machine-speed bursts remain rejected.
- Unknown-direction calls receive an explicit allow response instead of no
  response, so OEM stacks that omit the direction no longer hold the call
  until Android's screening timeout.
- The caller ID overlay unregisters the previous call-state watcher before
  registering a new one; back-to-back overlays no longer leak telephony
  registrations until idle-dismiss silently stops working.
- The "Call screening is off" alert clears as soon as the app resumes with the
  role restored, and never fires on installs that finished onboarding without
  ever granting the role.
- Lettered SMS sender IDs can no longer opt out of keyword and content
  screening: non-Latin sender names keep a readable canonical form and
  unrepresentable ones get a stable hashed identity instead of being skipped.
- Backups, blocklist exports, and log exports no longer crash the app when the
  export cannot be written (full storage, oversized encrypted payload); they
  report a clear failure instead. Blocklist imports now run in a single
  transaction, so a mid-import failure rolls back instead of leaving thousands
  of half-imported rows behind a failure message.
- Restoring with a typed passphrase now rejects plaintext files instead of
  silently restoring unauthenticated content; encrypted backups honor the
  authenticated KDF iteration count in their header so future strengthening
  cannot orphan old backups; repeated merges of legacy backups no longer
  duplicate log rows; restore counts no longer include entries refused by
  conflicting permanent decisions; and backup files are timestamped so a new
  backup cannot clobber one still being shared.
- The Lookup app shortcut opens the Lookup tab again, and the Lookup tab no
  longer reads the clipboard on every visit — the paste action reads it only
  when tapped, so Android's clipboard-access toast fires only on request.
- Quick Links no longer crash on devices without a browser.

### Changed

- Settings toggle rows are now fully tappable and read as a single switch to
  TalkBack; the push-alert switch regained its full touch target; informational
  captions moved to a higher-contrast text color; the push-alert sheet footer
  stays reachable in landscape; the last selected contact group can be
  deselected (falling back to All contacts); and the group picker no longer
  claims groups are unavailable when the real cause is a denied permission.
- Quiet-hours hour labels follow the device's 12/24-hour preference and
  locale. The widget's secondary text meets contrast requirements and its
  daily trend window is DST-safe. Onboarding's Enable Notifications opens the
  system notification settings on Android 10-12 where the runtime permission
  does not exist.

## v1.7.22 — 2026-07-22

### Changed

- Detekt and ktlint now run as zero-baseline gates in the standard Android
  verification lifecycle. Kotlin sources were normalized to the shared style,
  Compose-specific conventions are explicit, and actionable complexity,
  correctness, and unused-code checks remain enforced.
- Release verification now rejects app-version drift across Gradle, README
  highlights and badges, Fastlane changelogs and store copy, signing examples,
  and the F-Droid handoff. The F-Droid draft now labels v1.7.12 as the last
  externally prepared build instead of implying it is current app metadata.
- Outgoing risk warnings are now available as an opt-in, local-only safety
  check. Exact known-risk matches show one non-blocking overlay without remote
  lookups, contact analysis, call responses, blocked-log entries, or after-call
  feedback; unknown numbers remain silent.
- Contact trust can now cover all saved contacts or only selected Android
  contact groups. Group identity stays local and privacy-safe, membership
  changes invalidate the call-path cache, permission loss fails closed with a
  visible degraded state, and portable backup schema v7 preserves the scope.
- Portable backups can now be protected with a non-persisted passphrase. The
  versioned binary envelope uses PBKDF2-HMAC-SHA256 and AES-256-GCM with fresh
  salt and nonce values; wrong passphrases, tampering, and oversized plaintext
  fail before restore parsing or database mutation, while legacy JSON remains
  importable.
- Call handling can now be set per detected category to inherit the global
  policy, allow, send to voicemail, or block. Emergency and personal allow
  rules still win over category actions, while explicit personal blocks remain
  authoritative; the policy is explained in blocked activity and portable
  backup schema v6 preserves every selection.
- Phone identity now uses the device's injected ISO region to canonicalize
  valid national numbers to E.164 across matching, storage, imports, backups,
  and community reports. Short codes and opaque SMS sender IDs remain distinct.
- Room schema v12 migrates safe phone keys and merges national/E.164
  collisions without weakening permanent manual block or allow decisions.

### Fixed

- Checker failures are now recorded before the fail-open detection pipeline
  continues. Static cleanup also removes an unused overlay verification payload,
  stale statistics state, and an avoidable certificate-pin array copy.
- Release preflight now scans tracked build, script, and configuration sources
  for literal signing passwords and private-key material. Environment/property
  lookups remain valid, and synthetic guard tests prove failures never echo the
  matched secret value.
- Enabled installations now detect a lost Android call-screening role after
  startup, reboot, app update, and in a daily health check. One recovery alert
  is shown per loss episode; restoring the role or pausing call protection
  clears the private notice gate.
- Portable restore now writes all selected settings in one atomic DataStore
  update before mutating Room. A settings failure leaves every selected table
  untouched, and a later Room failure restores the exact prior preferences.
- Robolectric screening and SMS tests now own isolated DataStore files, Room
  databases, and coroutine lifecycles, eliminating cross-sandbox preference
  leakage and intermittent Windows atomic-rename failures.

## v1.7.21 — 2026-07-22

### Added

- A persistent appearance selector now offers System, Light, Graphite, and
  true-black AMOLED themes. Existing installs retain AMOLED as their default,
  while System follows the device's light/dark setting.
- Theme palettes have automated WCAG AA contrast coverage for primary text,
  secondary text, and primary actions.

### Changed

- The visual system now uses a tighter type scale, calmer semantic surfaces,
  restrained borders, compact section spacing, and consistent control density
  instead of decorative cards and gradients.
- Settings, onboarding, dashboard chrome, and the More hub use shorter copy,
  clearer grouping, and smaller headers while preserving 44 dp action targets.
- Nested More destinations now share one app header and a predictable Back
  action instead of stacking a second screen header below the shell.

## v1.7.20 — 2026-07-22

### Security

- Android cloud backup no longer uploads the Room database, which may contain
  phone numbers, call history, and raw SMS bodies. Direct device-to-device
  transfer and explicit user-created portable backups remain available.
- APK packaging now stages an explicit five-feed runtime allowlist instead of
  recursively embedding `data/`; 130 raw community report payloads and the
  maintainer README are no longer shipped. Debug/release verification fails if
  repository-only data enters the APK again.
- Outgoing calls are now rejected at the screening-service boundary before any
  inbound checker, response, overlay, log, or after-call feedback can run.
- External blocklist subscriptions now require HTTPS up front, matching the
  app-wide cleartext-deny policy instead of accepting feeds that can never load.

### Fixed

- RCS and notification-screening URL warnings remain active when SMS spam
  blocking is paused, matching direct-SMS phishing protection; the notification
  screening toggle still fully controls whether message content may be read.
- Corrupt public or private DataStore files now recover to safe defaults instead
  of repeatedly breaking settings reads, onboarding, and the screening hot path.
- Portable backup restore rejects more than 100,000 aggregate rows before
  normalization, conflict analysis, or database mutation, closing a CPU/memory
  exhaustion path that remained despite the existing 32 MiB byte cap.
- Portable backups now preserve active temporary block/allow expiries and the
  user's selected notification-screening apps. Expired temporary decisions are
  discarded during validation instead of returning as permanent rules.
- Repeated-call urgency now requires attempts separated by at least 15 seconds;
  duplicate call-log rows and machine-speed retry bursts can no longer create
  an automatic allow decision.
- Exported telephone deep links and post-call handles are now normalized with
  bounded work before reaching number details, preventing oversized or
  Unicode-homoglyph input from crossing the UI trust boundary.
- Language changes now preserve the active main tab and nested More screen
  instead of returning users to Home. Launcher shortcuts still navigate to
  their requested destination.
- Swipe-to-block in the activity log now restores the row after applying the
  block; swipe-to-delete retains its working Undo action on the current
  Material 3 dismissal API.
- Setup and statistics copy now correctly identifies the caller-ID overlay as
  optional and reports database entries rather than implying a byte size.
- The trusted-notification source picker now shows stable app names for all 17
  supported packages and uses a neutral state when none are installed.
- Deprecated Compose tab and swipe APIs were replaced, and the required API
  29–30 phone-state compatibility path is explicitly isolated, restoring a
  warning-free Kotlin build.
- The local community-report pipeline regression test no longer depends on a
  GitHub Actions workflow removed in v1.7.12; it once again validates the
  domain, hot-list, campaign-range, merge, and cleanup stages end to end.
- User-shared crash logs now redact phone-like values, credential fields, and
  URL queries/fragments from exception messages, while bounding individual
  messages and stack-frame output against pathological crash payloads.
- Screening now canonicalizes bounded E.164 caller IDs before applying unknown
  caller rules, so malformed, text-only, and overlong `tel:` handles cannot
  bypass the user's block-unknown setting.
- The reproducibility gate now verifies both signed and unsigned release APK
  names instead of failing after a valid unsigned release build.
- Pseudolocale screenshot QA now taps the actual Settings row in both layout
  directions and retries transient null UI roots after locale restarts instead
  of mislabeling or aborting captures.

## v1.7.19 — 2026-07-21

### Added

- **Offline region blocking and carrier-name trust rules.** Users can allow
  selected US states, Canadian provinces, territories, and toll-free numbers,
  then block calls outside that set. Optional case-insensitive CNAP glob
  patterns allow carrier-presented names through weaker region/statistical
  layers, while exact, system, prefix, and wildcard blocks remain authoritative.
- **Carrier-name block patterns.** The same bounded, case-insensitive CNAP glob
  rules can now reject recurring spam display names even as their numbers
  rotate. This best-effort layer runs after explicit and behavioral allows,
  persists through backup/restore, and fails open when no name is presented.
- **Opt-in notification screening for RCS, private messengers, and email.**
  Google and Samsung Messages remain the only defaults; users can separately
  enable AOSP Messages, SMS Organizer, Signal, WhatsApp, WhatsApp Business,
  Gmail, Outlook, or Thunderbird. Unselected sources are rejected before
  notification extras are read, and private-message/email detections produce
  non-destructive alerts.
- **Rule-priority conflict warnings.** Exact blocks, wildcard/range blocks,
  whitelist entries, and emergency allows are compared while editing. The
  inline warning names the rule that wins, including representative numbers
  covered by a broader pattern, before the user saves the overlap.
- **Persistent per-app language selection.** Settings now includes a language
  picker synchronized with Android's App Languages preference on Android 13+
  and persisted through AppCompat on Android 12 and lower. Release builds list
  only actual shipped locales; debug builds also expose en-XA and ar-XB for QA.
- **Repeatable pseudolocale screenshot QA.** Debug builds now include Android's
  `en-XA` and `ar-XB` resources, and an emulator script captures onboarding,
  dashboard, blocklist, and Settings in both locales while restoring the app's
  locale afterward.
- **A device-level call-screening deadline gate.** An API 35 instrumentation
  harness starts the real Hilt service lifecycle, drives the cold Room,
  DataStore, contact, and checker path, records latency, and reserves a full
  second beneath Telecom's five-second response deadline. The screening path
  is also included in baseline and startup profiles for ART compilation and
  release DEX layout; the acceptance run completed in 592 ms.
- **Deterministic off-device screening entrypoint coverage.** A Hilt-provided
  process coroutine scope can now be replaced by tests, and Robolectric drives
  the real call-screening service through allow, silence, and reject outcomes.
  It also delivers a valid UCS-2 SMS PDU through `SmsReceiver.onReceive()` and
  verifies that local phishing warnings remain active when SMS blocking is off.
- **Visible ML model health.** Protection Test now shows whether the GBT,
  compatible logistic model, built-in defaults, or a degraded fallback is
  active. Parse failures and GBT-to-LR degradation use explicit warning states
  instead of remaining visible only in local logs.
- **Automated accessibility regression coverage.** Compose Accessibility Test
  Framework checks now gate onboarding, dashboard, blocklist, and Settings for
  missing labels, undersized targets, contrast, and traversal problems.
  Expandable detail controls announce expanded/collapsed state, while duration
  labels use Android 16's native `TtsSpan` with a plain-text fallback below API
  36. The core surfaces also pass on-device checks with system outline text on.
- **Contacts-mode degradation detection.** `CallShieldPermissions`
  `isContactsModeDegraded()` reports when a contacts-dependent screening mode
  (contact-whitelist or contacts-only) is enabled but `READ_CONTACTS` is
  currently denied — a silent protection weakening that the base permission
  readiness matrix flagged only generically. Pure boolean predicate plus a
  `Context` overload; covered by readiness-matrix tests (789 tests total). The
  UI warning surfacing follows separately.
- **Opt-in Android post-call review.** Android 11+ users can enable a native
  Telecom post-call surface with clear “Mark spam & block” and “Add to
  contacts” actions. The spam action reuses CallShield's existing local block
  and community-report path, contact saving stays in the system editor, and
  the existing after-call notification remains the default when this is off.

### Changed

- **CallShield has a new cohesive icon system.** A flatter protected-handset
  mark now covers the repository logo, Play Store artwork, legacy square and
  round launchers, adaptive icons, shortcuts, splash screen, and Android 13+
  monochrome theming while retaining the app's AMOLED Catppuccin palette.
- **Manual refresh uses Android 16 progress notifications.** User-triggered
  database sync now shows a native `ProgressStyle` journey on API 36+ and an
  indeterminate progress notification on older releases, then removes it when
  the refresh finishes. After-call feedback intentionally retains its custom
  “Spam / Not spam” actions: API 35 and API 37 both reject `CallStyle` outside
  a foreground call service, user-initiated job, or full-screen call flow.
- **The on-device GBT catches more spam without abandoning its precision
  guard.** Training now exports sklearn's initial class-prior log odds and
  calibrates the shipped threshold for maximum held-out recall at a 0.92
  minimum precision. Exact on-device evaluation improved from 0.283 to 0.314
  recall while retaining 0.944 precision; legacy v3 models remain compatible
  with a zero initial score.
- **Compose navigation and setup surfaces are easier to scan.** The dashboard,
  onboarding, lookup, blocklist, recent activity, blocked log, More hub, and
  Settings now share a leaner hierarchy, clearer state and recovery copy, and
  consistent accessible actions without duplicating status information.

### Fixed

- **Oversized SMS analysis remains bounded on adversarial text.** URL matching
  now observes DNS label limits and skips regex scanning when no URL marker is
  present, eliminating quadratic backtracking within the existing 16 KB guard.
- **Phone numbers stay left-to-right inside RTL screens.** Every in-app,
  overlay, and localized-toast display path now wraps formatted numbers in
  Unicode bidi isolation; the UI padding audit found no physical left/right
  padding to migrate.
- **Onboarding respects edge-to-edge system insets.** Its progress header no
  longer renders underneath the Android status bar.

## v1.7.17 — 2026-07-21

Reliability drain — background-execution survival, corrupt-DB recovery, bounded
digest, and off-device test coverage of the receiver hot paths.

### Added

- **OEM background-kill risk detection.** `BackgroundExecutionStatus` classifies
  the AOSP-portable risk that an aggressive battery manager (MIUI/HyperOS
  autostart, Samsung, ColorOS) silently kills sync and unbinds the listener,
  using `ActivityManager.isBackgroundRestricted()` +
  `PowerManager.isIgnoringBatteryOptimizations()`, with a best-effort MIUI
  autostart probe and the settings intent to fix it.
- **Sync-staleness predicate.** `SyncFreshness` flags when local hot-campaign
  data has decayed past a configurable threshold (default 3× the 6h cadence),
  so protection quietly falling back to the bundled snapshot can be surfaced.
- **Robolectric harness.** Adopted Robolectric 4.16 (no `returnDefaultValues`)
  with real-framework tests over `SpamActionReceiver` (notification-cancel hot
  path), `CheckerPipeline.run` (5s-deadline short-circuit / first-non-null-wins
  ordering / exception tolerance), and a pure `SmsReceiver.reassembleBody`
  multipart 16 KB-cap helper.

### Changed

- **Bounded daily digest.** `DigestWorker` computes blocked/call/SMS counts via
  aggregate SQL and reads only the short `matchReason` column for the source
  breakdown, instead of materializing the full 24 h `call_log` window (including
  message bodies) in a constrained background process.
- **Dependency freshness.** OkHttp 5.3.2 → 5.4.0, Moshi 1.15.1 → 1.15.2 (the
  AGP-8-safe subset; core-ktx/activity-compose/navigation upgrades require AGP 9
  and stay in the blocked tranche).

### Fixed

- **RCS filter self-heals after an OS unbind.** `RcsNotificationListener` now
  `requestRebind()`s in `onListenerDisconnected()`, so a low-memory/app-update/
  OEM unbind no longer silently stops all RCS filtering and push-alert capture
  until reboot.
- **Protection re-asserted on in-place update.** `BootReceiver` now also handles
  `MY_PACKAGE_REPLACED` (auto-update without reboot), reschedules the pending
  blocked-call-log worker, and rebinds the notification listener alongside the
  existing worker reschedules.
- **Corrupt database no longer fails open forever.** The call screener detects
  on-disk SQLite corruption in its fail-open catch, rebuilds a clean database,
  and re-syncs from GitHub/bundled data instead of allowing every call through
  indefinitely with no signal.

## v1.7.16 — 2026-07-21

### Added

- **Import/restore files are size- and row-bounded.** Blocklist import and
  backup-restore preview read the SAF-selected file through a bounded reader
  (32 MB cap) and cap applied blocklist rows (100k), instead of materializing an
  arbitrarily large file whole into memory — an OOM/ANR guard at the import
  trust boundary. Oversize files fail gracefully with the existing error copy.

### Changed

- **Blocked-call notifications align with Android 16 grouping.** The grouped
  blocked-event notifications and their summary now use `GROUP_ALERT_SUMMARY`,
  so a burst of blocks alerts through the single group summary rather than
  per-child — keeping protection activity visible without being cooldown-muted.
- **On-device ML model health is now observable.** A corrupt or incompatible
  model payload that silently fell back to logistic regression (or failed to
  parse during sync) is now recorded as a typed `ModelHealth` state and logged,
  instead of degrading detection quality with no signal.

Deep audit pass — correctness, data-safety, and security hardening in the
data/persistence/network layers.

### Fixed

- **Legitimate US/Canada callers are no longer misblocked as international
  "wangiri" scams.** The heuristic checked a +1 caller's area code against a set
  that mixed genuine Caribbean codes with international country codes, so real
  domestic area codes that collide with them — 678 (Atlanta), 267
  (Philadelphia), 224 (Chicago), 385 (Salt Lake City), 386 (Daytona), 248
  (Detroit), 252 (Greenville NC), 269 (Kalamazoo), 672 (Vancouver) — scored +80
  and were hard-blocked. NANP numbers are now only matched against genuine
  Caribbean area codes; international codes apply only to international numbers.
- **International numbers sharing digits with US premium NPAs are no longer
  misblocked.** A number such as Mongolia (+976) was flagged as a US 976
  premium-rate line; the premium check is now scoped to NANP numbers.
- **Backup restore is now atomic (prevents data loss).** In Replace mode the
  restore cleared the selected sections and then re-inserted rows in a bare
  loop; a failure partway through left the user's data half-cleared and
  half-restored. The clear plus every re-insert now run in a single Room
  transaction that rolls back on any error. Settings (stored in DataStore) are
  applied after the transaction commits.
- **Hot-list refresh is now atomic.** `replaceHotList` deleted the old
  `hot_list` rows and inserted the new ones as two separate statements, leaving
  a window (every 30-minute sync) where a concurrent call-screening lookup could
  miss a hot-list number. It now uses the DAO's transactional `replaceBySource`.

### Security

- **CSV log export neutralizes spreadsheet formula injection.** A blocked-call
  `matchReason` or (with raw bodies enabled) SMS text beginning with `= + - @`
  could execute as a formula when the exported CSV was opened in Excel/Sheets.
  Such cells are now prefixed with a single quote.
- **URLhaus response bodies are size-capped.** The phishing-URL check read the
  response with no bound, unlike every other remote lookup; it now uses the
  shared bounded reader (256 KB cap).
- **Hot-list/range sanitization enforces ASCII-only digits.** `HotDataSync`
  used Kotlin's Unicode-aware `Char.isDigit`, so a malformed feed row with
  non-ASCII digits could enter the hot-range set or dedup key yet never match
  the ASCII-normalized screening path. It now uses the project's `isAsciiDigit`.

### Changed

- Removed a dead `hot_list`-prefix branch in the daily digest's source
  breakdown (database hits always record `matchReason = "database"`).

### Security

- **CVE-2026-53914 (Kotlin build-cache deserialization) posture documented.** The
  runtime is unaffected; the exposure requires a shared/remote Gradle build cache
  feeding untrusted metadata. CallShield configures none, so the vector is not
  reachable. `gradle.properties` now documents this and warns against adding a
  remote cache until Kotlin reaches ≥ 2.4.20 (that bump rides the AGP 9 tranche).

### Fixed

- **After-call feedback notification is now dismissed by its own action buttons.**
  The "Was this spam?" notice was posted with one ID (`stableId(number, 62)`) but
  the Spam / Not-Spam actions cancelled a different, long-abandoned ID
  (`number.hashCode() + 10000`), leaving the notification stuck on screen. Both
  sites now use a single shared `NotificationHelper.feedbackNotificationId()`.
- **Phishing-URL (URLhaus) warnings no longer require SMS spam blocking to be on.**
  `SmsReceiver` returned early when "Block SMS" was disabled, before the message
  body was assembled, so the URLhaus malware-URL check could never run. Message
  extraction and the phishing check now run independently of the block-SMS
  toggle; only spam classification and block-logging remain gated behind it.
- **SMS senders no longer pollute the voice-call campaign detector.** SMS checks
  reuse the voice-call checker chain, so every incoming SMS sender's NPA-NXX was
  being recorded into the in-memory call-burst detector — a burst of legitimate
  SMS from one carrier prefix could trip campaign-burst blocking of real voice
  calls from that prefix. The recorder now ignores SMS-path invocations.
- **Caller-ID overlay now dismisses when the call actually ends.** It previously
  stayed up for a fixed 20 seconds, hovering over unrelated UI after a 2-3 s
  blocked/rejected call. A permission-gated call-state watcher dismisses it on
  `CALL_STATE_IDLE`; the 20 s timeout remains as a backstop.

- **Malformed v3 `fallback_weights` are rejected instead of silently zeroed.**
  The logistic-regression fallback guard checked the (always-20) array size, so
  a model whose fallback keys failed to parse produced an all-zero LR that never
  flagged spam. It now requires a minimum count of successfully-parsed named
  weights.
- **RCS encrypted-message placeholder detection is now locale-tolerant.** It
  matched English literals only, so on non-English devices the localized
  "encrypted message" placeholder was fed to the SMS content rules as real
  content. Detection is now keyword-anchored (en/es/pt/fr/de/it/nl) and
  length-bounded so it cannot misclassify real short messages.

### Changed

- **GitHub default-branch resolution is cached (6 h TTL).** Every raw-feed fetch
  used to issue a fresh unauthenticated `GET /repos/{owner}/{repo}`; a single
  hot-list refresh plus model-weight sync could burn several of GitHub's 60
  requests/hour unauthenticated budget, after which all feeds silently fell back
  to bundled data. Resolution is now cached per repo.
- **SMS-only checkers use dedicated priority constants** (`SMS_KEYWORD`,
  `SMS_CONTEXT_TRUST`, `SMS_CONTENT`) instead of borrowing call-ladder values by
  arithmetic, so a future renumber of the call ladder can't silently reorder SMS
  checks. Values and ordering are unchanged.
- Corrected the misleading "geographic_distance" ML-feature documentation — it
  is a coarse fixed-reference NPA proxy, not true user-relative geography (a
  user-relative feature remains roadmap 2.2.4). Behavior and model schema
  unchanged.
- **Phone numbers are bidi-isolated in notification text** so they render
  left-to-right and correctly ordered inside RTL (Arabic/Hebrew) sentences.
  Added reusable `PhoneFormatter.isolate()` / `formatIsolated()` helpers; the
  wider UI-screen adoption + logical-padding audit remains tracked in ROADMAP.

## v1.7.13 — 2026-07-20

### Changed

- **Removed the only manual API-key entry.** The optional AbstractAPI carrier /
  line-type key field is gone from Settings. CallShield now requires and offers
  no credential entry of any kind — every lookup and enrichment source it uses
  is free and keyless (GitHub raw data, SkipCalls, PhoneBlock, WhoCalledMe,
  OpenCNAM, URLhaus, and the app's own Cloudflare Worker).
- Any AbstractAPI key stored by an earlier build is purged from both the public
  and private no-backup DataStores on first launch (`purgeLegacyAbstractApiKey`).

### Removed

- Deleted the unused `NumberTypeChecker` (AbstractAPI phone-validation client);
  it was never invoked on the screening or overlay path.
- Dropped the `phonevalidation.abstractapi.com` certificate pin and its
  `HttpClientTest` assertion, plus the AbstractAPI Settings strings.

### Testing

- Updated `RemoteLookupParserTest` and `HttpClientTest` to match the removed
  surface; `testDebugUnitTest` green.

## v1.7.12 — 2026-06-27

### Hardening

- Added a durable pending blocked-call log queue. `CallShieldScreeningService`
  writes an idempotent Room row before responding to Android, then a
  Hilt-backed WorkManager retry worker flushes it into `call_log` without
  duplicate final rows, notifications, or widget refreshes.
- Upgraded Room schema to v10 with `call_log.logKey` and
  `pending_blocked_call_logs`, plus migration and DAO coverage for duplicate
  suppression and retry timing.
- Added a shared ASCII-only digit utility and routed security-sensitive phone
  extraction paths away from Unicode digit matching.
- Added Cloudflare Worker rate-limit/dedup tests and Android 429 retry-delay
  feedback for community report submissions.

### Testing

- Verified `testDebugUnitTest`: 645 tests, 0 failures, 0 errors.

## v1.7.11 — 2026-05-18

### Fixed

- **Profile selection auto-reset** ([#2](https://github.com/SysAdminDoc/CallShield/issues/2)) — Selecting a blocking profile (Work / Personal / Sleep / Maximum / Off) appeared to "reset" on the Dashboard chip row after process death or ViewModel recreation. The profile's underlying flag changes (block calls, aggressive mode, time block, etc.) were correctly applied and persisted, but the active-profile *indicator* itself was held in an in-memory `MutableStateFlow` and lost on every VM init. Now persisted to DataStore under `KEY_ACTIVE_PROFILE`; the dashboard chip stays selected across restarts.

### Distribution prep (post-v1.7.10) — UX and visual polish

- Extended the premium Compose component system across the major user-facing
  flows, including dashboard scan actions, number details, recent-call recovery,
  blocklist utilities, diagnostics, onboarding, and settings.
- Replaced mixed raw action buttons and ad hoc icon wells with shared premium
  actions, compact actions, and icon tiles so destructive, recovery, loading,
  and secondary states read consistently across the app.
- Tightened trust-critical copy and semantics for reporting, source checks,
  permission recovery, import/export, and settings save actions.
- Added a shared per-permission degraded-mode matrix so dashboard and
  Protection Test recovery states name the affected feature and next action.

### Fixed

- Routed lookup, manual block, whitelist, recent-call, and blocked-log
  phone-number inputs/actions through the shared ASCII-only digit helper, with
  regression coverage for Unicode digit spoofing.
- Added bounded response-body guards and typed fallback statuses for
  enrichment lookups so oversized or malformed third-party responses do not
  crash overlay, details, or line-type parsing.
- Added byte, row-count, and schema guardrails for first-party GitHub spam,
  hot-list, spam-domain, and model-weight feeds so malformed responses fail
  before replacing the last known-good local data.
- Added external blocklist subscription guardrails: HTTP(S)-only CSV/TXT/JSON
  feeds are byte- and row-capped, preview add/remove/source impact before
  commit, and can be disabled or removed with feed-owned rows rolled back.
- Added URLhaus privacy mode and local-domain-first SMS/RCS URL checks so
  spam-domain feed matches avoid remote lookups, fragments are always stripped,
  and query strings are removed by default before URLhaus submission.
- Guarded the app-start contacts cache observer behind `READ_CONTACTS` so a
  clean install without contacts permission still starts and runs instrumented
  platform smoke tests.
- Added typed per-source enrichment diagnostics and privacy copy across overlay
  and number details so timeout, rate-limit, parse-error, oversized, disabled,
  unavailable, clean, and found states are visible without logging raw queried
  numbers.
- Redacted blocked SMS bodies from default log previews and CSV exports while
  keeping a separate warning-gated raw SMS export action for cases that require
  original message text.
- Added answered-caller trust with configurable count/window limits so repeated
  answered callers can bypass weaker heuristic/ML suspicion while explicit
  block rules still win first.
- Added emergency-callback grace with a configurable window so unknown callers
  can ring through after a local emergency call while explicit block rules
  still win first.
- Added SMS burst protection for repeated unknown senders and same-prefix flood
  patterns, including blocked-SMS notification actions to mark safe or report.
- Revised STIR/SHAKEN authenticated-allow copy and reasoning details
  so carrier attestation is described as caller-ID authentication, not caller
  safety, with A/B/C PASSporT wording covered by JVM tests.
- Added a backup restore preview step with parsed counts, conflict warnings,
  and explicit Merge or Replace apply modes so restores validate before
  mutating local blocklist state.
- Added selective backup and restore sections for blocklist, whitelist,
  wildcard/range rules, SMS keyword rules, non-secret settings, and opt-in logs
  so transfers can avoid unrelated personal data.
- Enabled checked-in Room schema export with instrumented migration coverage
  from database versions 5 through 9, plus a CI guard that fails on
  uncommitted schema drift.
- Added a high-API instrumented smoke lane for target-SDK permission,
  protected-service, Android 16 `SDK_INT_FULL`, notification-channel,
  full-screen-permission, and Android 17 OTP-delay compatibility assumptions
  while keeping the full API 29 emulator suite.
- Migrated Compose ViewModel Flow collection to lifecycle-aware
  `collectAsStateWithLifecycle()` so UI-only collectors stop with their
  lifecycle instead of continuing while screens are stopped.
- Aligned community-report number normalization with the hardened
  ASCII-only screening normalizer across the Android app, Cloudflare Worker,
  and Python report/import scripts, with regression coverage for Unicode
  digit spoofing and overlong numbers.
- Preserved pending community-report evidence for hot-list and spam-domain
  generation by deriving those feeds before the destructive report merge, with
  a Python regression test wired into CI.
- Added privacy-preserving SMS spam community reports that submit only
  sanitized domain and URL-signal indicators, never raw message text, while
  feeding fixture domains into `spam_domains.json`.

### Architecture

- Added production-used domain use-case wrappers for call spam checks, SMS spam
  checks, database sync, blocklist management, and log/blocklist export, then
  routed the live screening/scanner/sync/ViewModel entrypoints through them
  while preserving `SpamRepository` as the backing implementation.
- Moved `SpamCheckResult` and `SyncResult` into `domain/model` so use cases,
  UI, services, and tests now consume the same domain result types instead of
  repository-local data classes.
- Added domain repository contracts for spam checks, database sync, and
  blocklist management, then routed the use cases through a
  `SpamRepositoryAdapter` bridge ahead of the larger repository split.
- Split `SpamRepository` into a compatibility facade over
  `SpamRepositoryImpl`, `SettingsRepository`, `SyncRepository`, and
  `BlocklistRepository`, preserving existing singleton callers while moving
  detection, settings, sync, and blocklist/log concerns behind data-layer
  collaborators.
- Added the Hilt 2.58 Gradle plugin/runtime/compiler on the KSP path and
  annotated `CallShieldApp` with `@HiltAndroidApp`, establishing the DI
  migration baseline while staying compatible with AGP 8.10.1.
- Added a Hilt `DatabaseModule` that provides `AppDatabase` and `SpamDao`
  singletons for the upcoming repository and consumer injection steps.
- Added a Hilt `RepositoryModule` that provides the existing
  `SpamRepository` facade and binds the spam-check, sync, and blocklist domain
  repository interfaces to `SpamRepositoryAdapter`.
- Added a Hilt `NetworkModule` that provides the existing pinned
  `HttpClient.shared` as the singleton `OkHttpClient`.
- Migrated `MainActivity` and `MainViewModel` onto Hilt, with
  `MainViewModel` now receiving the existing `SpamRepository` facade plus
  sync, blocklist, and export use cases through constructor injection.
- Migrated `CallShieldScreeningService` onto Hilt field injection for the
  `SpamRepository` facade and call-spam use case while preserving the existing
  one-snapshot, fail-open 5-second screening flow.
- Added AndroidX Hilt Work 1.3.0, installed `HiltWorkerFactory`, removed the
  default WorkManager initializer, and migrated sync, hot-list, and digest
  workers to `@HiltWorker` assisted injection without changing schedules.
- Added a Hilt `DetectionModule` plus `CheckerDependencies` so the live
  checker pipeline, screening service, app-startup hot-data/model priming, and
  sync workers consume detection helpers through an injectable seam while the
  remaining object-to-class conversion continues.
- Converted the remaining detection helper singletons into
  constructor-injectable classes backed by shared compatibility facades, keeping
  existing UI/test utility call sites source-compatible while Hilt consumers
  receive injectable instances.

### Testing

- Upgraded AndroidX Test/Espresso to the 1.7.0/3.7.0 line and refreshed locks
  so Compose instrumented settings coverage runs on the API 36 emulator.
- Added Kover 0.9.8 to the locked Gradle graph and wired CI to run
  `:app:koverVerifyDebug` plus `:app:koverXmlReportDebug`, gating the
  JVM-tested data/util core at a 35% minimum line-coverage threshold.
- Added ktlint 1.8.0 and detekt 1.23.8 as locked Gradle checks, with
  baselines for existing style/complexity debt and a CI `static-analysis` job
  that fails on new ktlint or detekt findings.
- Added an `AppDatabase` constructor seam to `SpamRepository` so Android
  integration tests can run the repository against an in-memory Room database
  without starting the larger DI refactor.
- Added instrumented call-pipeline integration coverage for manual whitelist,
  STIR/SHAKEN failed/trusted ordering, user blocklist, prefix, wildcard,
  hash-wildcard, and frequency escalation priority tiers.
- Added instrumented SMS-pipeline coverage for whitelisted-sender inspection,
  keyword-before-content ordering, and generic SMS content-analysis blocking.
- Added a `SpamDataSource` seam and mocked in-memory Room sync integration
  tests for `syncFromGitHub()`, including remote snapshot population and
  preservation of user-block flags during remote refresh.
- Added a `HotFeedDataSource` seam for hot-list feeds and instrumented
  in-memory Room coverage for hot number insertion, stronger existing-row
  preservation, hot-range refresh, spam-domain refresh, and invalid-entry
  tolerance.
- Added deterministic onboarding Compose UI coverage for the four-page setup
  walkthrough, permission request affordances, call-screener setup action, and
  unsupported-screener fallback state.
- Extracted dashboard hero, setup checklist, and stats-row composables for
  deterministic Compose coverage of protection hero copy, sync freshness,
  blocked-count stats, and the call-screener setup action.
- Added blocklist swipe-left removal with snackbar undo for manual block
  entries, plus Compose coverage for manual add normalization, regex validation,
  delete action, and swipe removal.
- Extracted quiet-hours settings into a deterministic composable, added an
  all-day warning when start and end match, and covered the toggle callback,
  validation message, and hour picker in Compose tests.

### Localization

- Audited all 100 tracked main Kotlin files for hardcoded user-facing strings
  and recorded the findings in `docs/hardcoded-string-audit.md`.
- Moved clear backup/restore, blocklist import/export, CSV export, spam share,
  sync result, Quick Settings tile, clipboard, search-result, recent-duration,
  and number-detail stragglers into `strings.xml` resources and plurals.

### F-Droid

- Added Fastlane listing metadata under `fastlane/metadata/android/en-US/`.
- Added a draft `fdroiddata` metadata file with the v1.7.10 build, upstream
  binary URL, expected signer fingerprint, and tag/update settings.
- Added an F-Droid submission runbook with the remaining GitLab MR,
  fdroidserver lint/build, and signature-copy verification steps.

## [v1.7.10] - 2026-05-14

Continuation roadmap pass focused on the Compose dependency refresh.

### Compose stack

- **Compose BOM 2026.05.00 upgrade** — moved the UI stack from the 2024.12
  BOM to the 2026.05 release train, resolving Compose UI/Foundation/Runtime to
  1.11.1 and Material 3 to 1.4.0.
- **Locked graph refresh** — regenerated dependency locks across debug,
  release, and unit-test classpaths so every app configuration resolves the
  same Compose, lifecycle, core, savedstate, and profileinstaller graph.
- **Compose resource lint cleanup** — replaced stale `LocalContext.getString`
  and `LocalContext.resources` reads in composables with `stringResource`,
  `pluralStringResource`, or `LocalResources`, keeping snackbar, toast,
  semantic, and validation copy configuration-aware.
- **Verification** — `lintDebug` is green on the refreshed Compose train; the
  full reproducible-build, unit-test, and lint pipeline was rerun after the
  migration.

## [v1.7.9] - 2026-05-14

Continuation roadmap pass focused on WorkManager dependency and scheduling
hardening.

### Background work

- **WorkManager 2.11.2 upgrade** — upgraded the background-work stack and
  refreshed dependency locks across debug, release, and unit-test classpaths.
- **Schedule contract coverage** — added JVM tests for `SyncWorker`,
  `HotListSyncWorker`, and `DigestWorker` WorkRequest construction, covering
  repeat intervals, connected-network constraints, initial delay, and backoff
  policy.
- **Schedule construction seam** — centralized each worker's WorkRequest
  creation behind testable companion helpers while preserving the existing
  unique periodic work names and `KEEP` enqueue policy.
- **Stable DoS-guard verification** — warmed the SMS analyzer before timing
  the large-body guard so the test measures analyzer behavior rather than JVM
  regex initialization.

## [v1.7.8] - 2026-05-14

Continuation roadmap pass focused on DataStore and backup privacy hardening.

### Settings and backup privacy

- **DataStore 1.2.1 upgrade** — upgraded the settings dependency and refreshed
  the locked dependency graph.
- **No deprecated encrypted-preferences path** — audited the settings and
  backup layer; CallShield does not use `EncryptedSharedPreferences`,
  `androidx.security`, or Tink-backed preferences today, so no encrypted
  preference migration target exists.
- **No-backup credential storage** — moved the optional AbstractAPI enrichment
  key into a private DataStore under `noBackupFilesDir`, with one-time
  migration from the legacy public settings key.
- **Scoped restore boundary** — public DataStore settings and the Room
  database remain restorable, while optional local credentials are kept out of
  Android Auto Backup scope.
- **Backup-rule documentation** — corrected backup rule comments and README
  copy so they no longer imply that every preference is excluded.

## [v1.7.7] - 2026-05-14

Continuation roadmap pass focused on reproducible-build groundwork.

### Build integrity

- **Gradle dependency locking** — enabled repository-wide dependency locking
  and checked in the generated lockfiles for release/debug/test resolution.
- **Build metadata guard** — added `verifyReproducibleBuildInputs` to fail if
  Gradle build scripts start embedding wall-clock build metadata into APK
  inputs.
- **AGP VCS metadata disabled** — release APKs no longer include
  `META-INF/version-control-info.textproto`, and
  `verifyReleaseApkReproducibleMetadata` fails if it returns.
- **Release hash sidecars** — CI release artifacts now include `.sha256`
  sidecars for artifact integrity, and local signed releases can generate the same sidecar via
  `scripts/write-release-sha256.ps1`.
- **APK content comparator** — added `scripts/compare-apk-contents.ps1` for
  rebuild checks that compare ZIP entries while making APK Signing Block
  differences explicit.
- **Verification runbook** — documented the fixed inputs, signed-vs-unsigned
  artifact distinction, offline rebuild command, and hash comparison workflow
  in `docs/reproducible-builds.md`.

## [v1.7.6] - 2026-05-14

Continuation roadmap pass focused on network dependency hardening.

### Network and dependency hardening

- **OkHttp 5 upgrade** — upgraded OkHttp from 4.12.0 to 5.3.2 and kept
  callers on the shared `HttpClient` derived-client pattern.
- **Central certificate pinning** — added SPKI pins for GitHub raw/API,
  Cloudflare community reports, URLhaus, AbstractAPI, and the free caller-ID
  enrichment hosts.
- **Kotlin, AGP, and Room alignment** — upgraded AGP to 8.10.1, Kotlin/KSP to
  2.2.21, and Room to 2.8.4 so the OkHttp 5 dependency stack builds cleanly
  with current metadata.
- **Pinning regression coverage** — added `HttpClientTest` to lock the pinned
  host inventory and pin-format requirements.

## [v1.7.5] - 2026-05-13

Continuation polish pass focused on Statistics localization and scan feedback.

### Stats and scan feedback polish

- **Localized chart labels** — the weekly Statistics chart now uses locale
  weekday abbreviations instead of hardcoded English day names.
- **Resource-backed detection labels** — Statistics detection-source names are
  now string resources, including the corrected "Prefix match" label.
- **Consistent scan errors** — call-log and SMS inbox scan permission/failure
  messages now use the same resource-backed copy system as the rest of the UI.
- **Cleaner legend formatting** — source legend count/percentage labels now
  use a formatted string resource for localization-ready output.

## [v1.7.4] - 2026-05-13

Continuation polish pass focused on the advanced settings credential flow.

### Settings trust polish

- **Safer optional API-key control** — the AbstractAPI key field is now masked
  by default, with explicit show/hide control rather than a permanently visible
  credential field.
- **Clear saved-state feedback** — the control now distinguishes "Not
  configured", "Saved locally", and "Unsaved changes" so users can tell whether
  a key is active or pending.
- **Lower-friction saving** — the save action is disabled until the local value
  changes, and switches to a clear-key action when a stored key is being
  removed.
- **Trust copy** — advanced settings now state that the optional key is stored
  only on the device and can be left blank to disable carrier enrichment.

## [v1.7.3] - 2026-05-13

Premium-polish pass focused on trust, visual discipline, state recovery,
and user-facing release clarity.

### UX and visual polish

- **Shared shape and typography rhythm** — standardized premium surfaces to
  modest rectangular 12 dp corners, removed negative/expanded letter tracking,
  and kept status backdrops within the existing no-pill design rule.
- **App chrome refinement** — tightened the top shell spacing, reduced header
  card weight, and removed the selected bottom-navigation pill indicator. The
  active tab is now communicated through icon/text color and weight.
- **State treatment consistency** — aligned buttons, filter chips, dialogs,
  progress bars, tabs, cards, and icon backdrops around the same restrained
  radius system.

### Trust and feedback

- **Blocked Log recovery states** — empty and filtered log states now explain
  what is happening, use a proper premium state card, and provide a "Show all
  activity" recovery action when filters hide existing records.
- **Trusted-source sheet feedback** — the push-alert source picker now shows
  installed-source coverage, total supported-source coverage, and skeleton
  loading while app labels resolve.
- **Lookup/report semantics** — spam report actions now use a flag icon rather
  than a favorite icon, and Number Detail database/risk labels use the shared
  rectangular status treatment instead of default Material chip backdrops.

## [v1.7.2] - 2026-05-13

Extreme hardening pass. Eight surgical fixes across UI design rules,
phone-number spoofing defence, regex-DoS guards, unbounded in-memory
maps, and crash-log durability. 28 new JVM unit tests; full suite at 613
passing.

### Security & correctness

- **ASCII-only phone-number normalization** — `normalizePhoneNumber()`
  now drops Arabic-Indic (٠-٩), fullwidth (０-９), and other non-ASCII
  digits that `Char.isDigit()` previously accepted. Visually-identical
  homoglyph caller IDs no longer bypass exact blocklist matches. Also
  strips zero-width / RTL marks (ZWSP, LRM, RLM, BOM) injected into
  spoofed numbers before the `+` check.
- **SMS body DoS guard (`SmsContentAnalyzer.MAX_ANALYSIS_LENGTH = 16 KB`)**
  — caps the input fed into the regex sweep. Multi-MB SMS bodies on the
  inbox-scan path can no longer pin the 5 s screening deadline.
- **Multipart SMS reassembly cap (`SmsReceiver.MAX_REASSEMBLED_BODY = 16 KB`)**
  — a malformed delivery claiming hundreds of segments can no longer
  drive `joinToString` into unbounded memory.
- **WildcardRule ReDoS hardening** — rejects catastrophic-backtracking
  shapes (`(a+)+`, `(a*)+`, `(a|aa)+`) at validation time, before the
  regex even compiles. Phone-shaped patterns (`^\+?1?\d{10}$`, area-code
  alternations) still pass.
- **CrashReporter atomic write** — crash logs now write to `*.txt.tmp`
  and atomically rename. Power loss or a second crash mid-write can no
  longer leave a half-written report that looks legitimate.

### Reliability

- **OneShotNoticeGate bounded map** — added a 1 024-entry LRU cap on
  top of the existing 6 h TTL prune. Long-lived processes that see many
  unique callers can no longer grow the notice-gate map without bound.
- **NotificationHelper PendingIntent ID separation** — `notifyAfterCall`
  now derives its request codes from `stableId(number, salt)` with
  distinct salts per intent rather than `number.hashCode()` and
  `hashCode() + 1`, removing collisions with block-notification intents
  for adjacent hashes.
- **`updateSummary` cancel guarded against SecurityException** — the
  no-blocks-yet `NotificationManager.cancel(SUMMARY_ID)` path now
  swallows the API 33+ revoke-between-check race the same way
  `safeNotify()` does.

### Design system

- **No pill / oval backdrops** — removed every `RoundedCornerShape(999.dp)`
  in the app:
  - `StatusPill` (Theme.kt) now uses 6 dp corner radius and differentiates
    by colour/border/font-weight, not shape.
  - Onboarding and Protection-Test progress bars switched from
    `RoundedCornerShape(999.dp)` to 4 dp.
  - Blocked-log count badge switched from `CircleShape` to
    `RoundedCornerShape(10.dp)` (text-bearing badge, no longer fully
    rounded).

### Tests

- 28 new JVM unit tests across `NormalizePhoneNumberTest`,
  `SmsContentAnalyzerTest`, `OneShotNoticeGateTest`, `WildcardRuleTest`.
- Full suite: 613 tests passing in ~10 s.

## [v1.7.1] - 2026-04-29

### Improved

- **Caller-ID overlay race & feedback** — `CallerIdOverlayService` no longer blocks on all three external lookups; first spam-hit-wins via the `Race.kt` helper, so user-visible callerID appears sooner.
- **External lookup robustness** — tightened `ExternalLookup.kt` against transient races and stale results when one provider returns much later than the others.
- **Push-alert allow feedback** — `PushAlertRegistry` + `OneShotNoticeGate` now surface "Allowed by you" notices the next time a previously-allowed number rings, so the user can revoke without digging into logs.

## [v1.7.0] - 2026-04-24

Round-2/3 borrow-and-harden pass. Competitor-OSS research
(aj3423/SpamBlocker, adamff-dev/spam-call-blocker-app, rspamd patterns)
distilled into two user-facing detection-pipeline improvements plus a
behavior-preserving refactor of the block-response decision table.
Fourteen new JVM unit tests cover the priority ladder and the
silence-vs-reject branches.

### Added

- **STIR/SHAKEN Trusted-Caller Allow** — new detection layer that short-
  circuits the weaker statistical blockers (heuristic, ML, campaign-burst,
  frequency-escalation) when the carrier signs a `PASSED` attestation on
  the calling number. Paired with the existing STIR_SHAKEN block slot
  (which fires on `FAILED`) to form a clean decision table: PASSED → allow,
  FAILED → block, NOT_VERIFIED / null → no opinion.
  - New checker: `data/checker/Checkers.kt::StirShakenTrustChecker` at
    priority slot `STIR_SHAKEN_TRUSTED = 5_300`. Sits **below** every
    explicit user rule (manual whitelist, contact whitelist, user
    blocklist, prefix, wildcard, hash-wildcard, system-block-list) so an
    intentionally blocked number stays blocked even if the carrier
    verifies it. Sits **above** heuristic / ML / campaign-burst /
    frequency so it does what it's here to do.
  - New setting: `KEY_STIR_TRUSTED_ALLOW`, **defaulted on**. Toggle
    exposed in Settings → Detection Engines → "STIR Trusted-Caller Allow".
  - Source: Round-2 OSS research — aj3423/SpamBlocker + adamff-dev
    attestation-level filtering. We intentionally do NOT treat attestation
    `C` (verified-fail) as a sole block signal because US wholesale
    carriers routinely stamp C on legitimate traffic.
- **Auto-Mute Low-Confidence Blocks** — new opt-in setting that silences
  blocks with `confidence < 60` to voicemail instead of hard-rejecting
  them. Users can then review uncertain calls after the fact without an
  audible interruption. High-confidence hits (database match, user
  blocklist, STIR FAILED, heuristic ≥ 60) are always hard-rejected even
  with auto-mute on.
  - New helper: `CallShieldScreeningService.shouldSilence(silentVoicemail,
    autoMuteLowConf, confidence)` — pure decision function, testable
    without a CallScreeningService / Android runtime.
  - New constant: `AUTO_MUTE_CONFIDENCE_THRESHOLD = 60`.
  - New setting: `KEY_AUTOMUTE_LOW_CONFIDENCE`, defaulted off. Toggle
    exposed in Settings → Detection Engines → "Auto-Mute Low-Confidence
    Blocks".
  - `KEY_SILENT_VOICEMAIL` still wins when on — silence-every-block
    beats silence-only-uncertain.
- **14 new JVM unit tests** — `StirShakenTrustCheckerTest` (8 cases:
  enablement gates, pure decision paths, full priority-ladder regression
  sweep) and `CallShieldScreeningServiceAutoMuteTest` (6 cases: the three
  decision table branches + confidence-threshold boundary).

### Changed

- `CallShieldScreeningService.respondBlock()` now delegates response-
  shape decisions to a new private `buildBlockResponse(prefs, confidence)`
  helper. All three response shapes (silent-voicemail / auto-mute /
  hard-reject) share one reviewable decision table. Behavior-preserving
  for v1.6.3 users — same `CallResponse.Builder()` flag sequence is still
  emitted per branch.
- `CheckerPriority` ladder extended with `STIR_SHAKEN_TRUSTED = 5_300`.
  Existing slot numbers are unchanged.
- ROADMAP `Current State` header rolled forward from v1.2.8 → v1.7.0 and
  now documents the round-1 architecture refactor (IChecker pipeline,
  Race.kt, PushAlertChecker) that shipped in the v1.6.x series.

### Fixed

- Internal audit caught during the v1.7.0 development cycle: the
  first-cut priority slot for `STIR_SHAKEN_TRUSTED` was placed above
  `USER_BLOCKLIST`, which would have let a carrier-signed PASSED call
  override an intentional user block. Corrected to slot `5_300` before
  ship; added a regression test suite in `StirShakenTrustCheckerTest`
  that asserts the ladder invariant against every explicit user rule
  so the bug cannot return silently.

## [v1.6.3] - 2026-04-24

Hardening pass targeting defects the v1.6.1 audit missed. Nine
surgical correctness fixes across the screening pipeline, backup
system, SMS receiver, and several services.

### Fixed (high)

- **PushAlertChecker — title/body scope for anchored digit match**
  (`data/checker/PushAlertChecker.kt`). The v1.6.1 fix added a
  lookbehind/lookahead to the 7-digit match so "5551234" wouldn't
  match as a substring of a longer digit run, but the regex still
  ran against `title + "\n" + body`. Since `\n` counts as a
  non-digit boundary, a standalone 7-digit run in the **title**
  (order ID "Order #5551234", delivery PIN, tracking number) could
  allow an unrelated caller whose last-7 happened to match. The
  digit match is now scoped to `alert.body` only. Phrase matches
  (`your driver`, `out for delivery`, …) still run against the full
  title+body because phrases legitimately appear in titles.
- **BackupRestore — schedule fields now round-trip** (`data/BackupRestore.kt`).
  `BackupWildcard`/`BackupKeyword` dropped `scheduleDays`/`scheduleStartHour`/
  `scheduleEndHour` on export. A time-gated rule restored from a v2
  backup silently lost its schedule and fired 24/7. Backup version
  bumped to 3; readers still accept v1–v3.
- **SmsReceiver — removed misleading `abortBroadcast()`** (`service/SmsReceiver.kt`).
  CallShield isn't the default SMS app, so `abortBroadcast()` on
  `SMS_RECEIVED_ACTION` is either unordered or a no-op depending on
  OEM/API level — the message still landed in the inbox. The call
  (with its apologetic comment) suggested CallShield blocked SMS
  delivery when it only logs the block. Comment rewritten to be
  explicit: we log, we do not suppress.
- **CallerIdOverlayService — publish session id before `addView`**
  (`service/CallerIdOverlayService.kt`). Lookup jobs scheduled
  during view construction could compare against an older
  `activeSessionId` and mis-attribute results to the prior session.
  Belt-and-braces: set the id first, unwind on addView failure.

### Fixed (medium)

- **CallbackDetector — `NUMBER LIKE ?` SQL prefilter** (`data/CallbackDetector.kt`).
  The outgoing-24h and incoming-5min queries previously scanned the
  full CallLog window and post-filtered in Kotlin. On heavy users
  that's hundreds of rows decoded per screening call. SQLite now
  narrows on the trailing 7 digits before handing the cursor back;
  Kotlin still does an exact 10-digit match because CallLog formats
  vary (parentheses, dashes, country-code presence).
- **CallShieldTileService — Mutex-serialized toggles** (`service/CallShieldTileService.kt`).
  Two rapid QS-tile taps could both read the current state, both
  compute the opposite, and both write — leaving the toggle
  apparently stuck. A single `Mutex` now serializes the
  read-modify-write on each tap.
- **SpamMLScorer — sweep stale `.tmp` on `loadWeights`** (`data/SpamMLScorer.kt`).
  A process kill between `writeText` and `renameTo` left an orphan
  `spam_model_weights.json.tmp` that accumulated forever because
  `loadWeights` only reads the final file. Startup now deletes any
  stale tmp before reading.
- **WildcardRule — regex path uses `numberVariants`** (`data/model/WildcardRule.kt`).
  The glob path matched an input across its `+1`, bare-digit, and
  `1`-prefixed forms; the regex path only matched the raw input.
  A user-written regex anchored to E.164 (`^\+1832555\d{4}$`)
  silently missed SMS senders arriving without the `+1`. Regex now
  tries every variant for parity.

### Fixed (low)

- **SitTonePlayer — AtomicBoolean CAS for single-flight** (`service/SitTonePlayer.kt`).
  The `@Volatile var isPlaying` check-then-set wasn't atomic; two
  coroutines entering `play()` concurrently could both see `false`
  and both proceed. Replaced with `AtomicBoolean.compareAndSet`.

### Tests

- `CallbackDetectorTest` updated for the new query builder signature
  and the `NUMBER LIKE '%<last7>'` prefilter.
- `PushAlertCheckerTest` gains two regression cases covering the
  body-only digit scoping.
- `WildcardRuleTest` (new) covers the glob+regex parity and the
  ReDoS guard.
- `BackupRestoreTest` updated for v3 schema, including schedule-field
  round-trip cases.
- **577 unit tests pass.**

### Known follow-ups (not fixed this pass)

Real findings from the audit that deserve their own session:

- Contact cache has no `ContactsContract` observer — a contact added
  via the OS remains "unknown" for up to 60s. Needs lifecycle wiring
  and should land alongside a sibling observer for
  `BlockedNumberContract`.
- `MainActivity` uses `collectAsState` (not `collectAsStateWithLifecycle`)
  so the dashboard minute-ticker keeps running in background. Touches
  many screens; do in one sweep.
- Backup restore is additive; picking the wrong file mixes two users'
  state. Needs a product decision on Replace-vs-Merge prompt.
- Screening-service block logging is fire-and-forget on `appScope`;
  under memory pressure Android can reclaim the process before the
  Room insert runs. Needs `goAsync`-equivalent keep-alive.
- `MainViewModel` is 400+ lines with 28 StateFlows; wants to split
  into domain-specific ViewModels.
- Phone-number normalization duplicated across 13+ screens — wants
  a `PhoneUtils` extraction.

## [v1.6.2] - 2026-04-24

Maintenance release. No app-code changes since v1.6.1 — bundles the
refreshed spam database and a CI cadence fix.

### Changed
- **CI**: `merge-reports.yml` now runs weekly (Mon 08:00 UTC) instead of
  every 30 minutes. The hot-list refresh cadence was generating ~48
  commits/day to master; the weekly build workflow already covers the
  same ground. No effect on shipped app behavior.
- **Data**: refreshed community hot list and campaign-range aggregates.

## [v1.6.1] - 2026-04-22

Post-release audit fixes. Every item came out of a v1.6.0 code review;
no new features.

### Fixed (critical)
- **STIR/SHAKEN bypassed whitelist**: `CallShieldScreeningService` ran the STIR check inline *before* `isSpam()`, so a user's manual whitelist entry (emergency contact added via the app UI, not the device address book) was hard-rejected whenever the caller failed carrier verification. STIR is now a regular `IChecker` at priority 8,500 — below MANUAL_WHITELIST (10k) and CONTACT_WHITELIST (9k), above every block. Verification status threaded through `CheckContext.verificationStatus`.

### Fixed (high)
- **PushAlertChecker direct-number match anchoring**: a spam caller's last 7 digits appearing as a *substring* of any digit run in a recent notification (order ID, tracking number, dial-in PIN) used to return `allow("push_alert")`. Now anchored with `(?<!\d)…(?!\d)` boundaries so only standalone 7-digit runs qualify. TTL on both match paths unified to 10 minutes (was 30 min for number match).
- **PushAlertChecker trust-phrase list tightened**: dropped the bare `calendar` regex (matched any notification mentioning "calendar"). Tightened `(is |has )?outside` to require a subject word ("is outside", "arriving outside", "I'm outside", "we're outside") so weather notifications no longer fire it.
- **Verification/MFA phrases now package-gated**: "verify", "verification code", "OTP" etc. only fire for the four messaging-app packages that actually send SMS codes. Outlook MFA pushes no longer unblock unrelated callers. `appointment reminder` is gated to calendar apps.

### Fixed (medium)
- `util/Race.kt`: a competitor that threw was synthesizing `onTimeout` as its result and, for callers where `decisive(onTimeout) == true`, winning the race on the failure. Now failures are tracked as a separate state and only decrement the `remaining` tally.
- `PushAlertRegistry` opt-out updates are now atomic via `applyOptOuts(Set)`: prune-before-publish eliminates the window where a concurrent screening verdict could read stale alerts from a just-disabled package. Defensive `HashSet(disabled)` copy on every write guards future callers.
- `SystemBlockList.isBlocked`: a `SecurityException` (default-dialer role revoked mid-session) now clears the lookup cache in addition to marking availability off — prevents a stale `true` entry from the previous role session influencing subsequent checks.

### Fixed (low)
- `CheckerPipeline.run` now bails before each checker when `ctx.timeLeftMillis() <= 0` — cheap insurance in case a future checker blocks long enough to eat the 5-second deadline.

### Added
- `PushAlertCheckerTest` — 11 regression tests pinning the H1/H2/H3 fixes (anchored digit match, dropped bare phrases, package-gated MFA).

## [v1.6.0] - 2026-04-22

Peer-inspired track — features ported from the strongest OSS Android call/SMS blockers
(SpamBlocker, YetAnotherCallBlocker, Saracroche, Fossify Phone, BlackList).

### Added
- **Priority-sorted checker pipeline (A1)** — every detection layer is now an `IChecker` implementation with an explicit priority. 13 checkers (manual whitelist → ML scorer) run through `CheckerPipeline.run` with first-non-null wins. Replaces the 140-line `isSpam()` waterfall with a 25-line dispatcher. Each layer is testable in isolation and the "why blocked" trail is explicit. Inspired by SpamBlocker's `IChecker` architecture.
- **Budget-aware parallel race (A2)** — `util/Race.kt` races N suspend blocks against a budget, returns the first decisive result, cancels losers. Built on `Channel` + `select` + `AtomicInteger`. Foundation for reputation-API work under the 5-second CallScreeningService deadline.
- **Push-alert bridge (A3)** — notifications from 24 messaging/delivery/rideshare apps (Uber, DoorDash, Amazon, FedEx, Google Messages, Gmail, Outlook, etc.) can vouch for an unknown caller within 30 minutes. Direct number match OR trust-phrase match ("your driver", "verification code") allows the call through. Biggest false-positive fix in the OSS landscape.
- **A3 allowlist editor** — modal bottom sheet lists every trusted source package with per-package switches + "Restore defaults". `PackageManager`-resolved labels, installed-first sort. Opt-out semantics — future default additions propagate automatically. Gated behind the master toggle.
- **System block-list checker (A4)** — read-only bridge to `BlockedNumberContract.BlockedNumbers`. If the user has marked a number via stock Phone/Messages, CallShield respects it. Graceful degradation via `SecurityException` catch for non-default-dialer installs.
- **Length-locked `#` wildcard rules (A5)** — Saracroche-style range patterns like `+33162######`. Pure character-index matching, no regex JIT. Covers any NPA-NXX in one rule. New "Ranges" tab with pattern overlap detection, coverage count pill, and a safety rail rejecting patterns that cover >100M numbers. Country-prefix variant generator matches national-format and international-format numbers from the same rule.
- **Per-rule schedule gating (A7)** — every rule type (`WildcardRule`, `HashWildcardRule`, `SmsKeywordRule`) now carries an optional day-of-week + hour-window schedule. Rules can be restricted to e.g. "Mon–Fri 09:00–17:00". Shared `ScheduleSection` composable across all three rule-add dialogs; list items show an "Active …" pill when gating. `daysMask == 0` is the "no gating" sentinel so legacy rules behave identically.

### Fixed (opening audit pass, opus-4.7 context)
- SpamMLScorer double-scoring: `isSpam()` + `confidence()` each ran feature extraction + tree traversal. Replaced with `verdict()` — single pass returns `(score, confidence, isSpam)`. Material on the 5-second deadline.
- SpamMLScorer parse-failure leak: `parseAndApply` mutated state to defaults mid-parse. Now `parseModel` is pure (returns `ModelState?`) and the caller commits atomically. A corrupt sync can no longer expose default weights to live scorers.
- CoroutineScope leaks in `SpamActionReceiver` and `CallShieldWidget` — each allocated a fresh scope per invocation. Both now use `CallShieldApp.appScope`.
- CallShieldScreeningService hot path: 4+ individual DataStore `Flow.first()` reads before `isSpam()` took its own snapshot. Now single `readPrefsSnapshot()` threaded through the entire call chain.
- CallShieldScreeningService response-loss: Room logging ran before `respondToCall`, risking "Android auto-allows after 5s" if the service was unbound mid-write. Response now happens first; logging offloaded to `appScope`.
- Room `LIKE` wildcard injection in `searchNumbers` / `searchLog` — user-typed `%` or `_` was silently treated as SQL wildcard. Pre-escaped via `escapeLikeQuery()` with `ESCAPE '\'` on the queries.
- `SmsContentAnalyzer`: dead code (unused `body.lowercase()`) removed; `PHONE_IN_BODY` regex switched to `(?i)` flag for consistency.

### Performance
- Single-snapshot prefs sharing across `isSpam` + `isSpamSms` removes one DataStore round-trip on every SMS.
- Checker-level `isEnabled(ctx)` gating skips expensive checks (regex compile, ML traversal, heuristic analyze) before any work, not after.
- `HashWildcardMatcher.matches` is ~20 ns per call — beats regex by 100x on length-locked range patterns.
- `PushAlertRegistry` is lock-bounded but uses a 128-entry ring buffer; dedup of identical sequential alerts.

### Database
- Room `DB_VERSION = 9`. Three additive migrations layered on top of v1.5:
  - `MIGRATION_6_7`: new `hash_wildcard_rules` table.
  - `MIGRATION_7_8`: schedule columns on `hash_wildcard_rules`.
  - `MIGRATION_8_9`: schedule columns on `wildcard_rules` and `sms_keyword_rules`.
- All additive, all `DEFAULT 0` sentinels — zero risk for existing data.

## [v1.5.0] - 2026-04-15

### Fixed
- SpamMLScorer race condition: replaced 6 independent @Volatile fields with single atomic ModelState snapshot to prevent half-updated model reads during concurrent scoring
- WildcardRule glob-to-regex: all regex metacharacters (`.`, `(`, `)`, `[`, `]`, etc.) now properly escaped — previously only `+` was escaped, causing false matches on patterns like "212.555*"
- Frequency auto-escalation: unbounded time window caused legitimate callers with 3+ calls over months to be auto-blocked; now uses 7-day sliding window
- RcsNotificationListener: replaced verbose `scope.coroutineContext[Job]?.cancel()` with idiomatic `scope.cancel()`
- SyncWorker: non-retryable failures (HTTP 404) were silently reported as success to WorkManager; now correctly returns Result.failure()
- SpamActionReceiver: bare CoroutineScope without SupervisorJob could crash before pendingResult.finish() on uncaught exceptions
- MainViewModel scan guard: TOCTOU race allowed duplicate concurrent call log / SMS scans; flag now set before coroutine launch
- isWangiriCountryCode: Caribbean +1 NPAs (876 Jamaica, 284 BVI, 649 Turks & Caicos, etc.) were never detected as wangiri due to overly broad US/CA exemption

### Performance
- Consolidated 7 separate OkHttpClient instances into shared `HttpClient.shared` singleton with per-caller derived builders — eliminates redundant connection pools and thread pools
- Cached hot-path Room queries (prefixes, wildcards, keyword rules) behind @Volatile lazy fields with write-through invalidation — removes per-call disk I/O from the 5-second screening deadline
- DataStore settings in isSpam(): single `dataStore.data.first()` read replaces 8+ separate Flow .first() collector operations
- CampaignDetector.trimTrackedPrefixes: replaced O(n log n) sort with O(n) min-scan inside synchronized block

### Hardening
- WildcardRule: user-provided regex patterns capped at 200 chars to guard against ReDoS on the call screening path
- SmsContentAnalyzer: URL regex pattern length-capped at 2048 chars to prevent ReDoS on pathological SMS bodies

## [v1.4.0]

- Changed: Update hot list + merge community reports 2026-04-13T15:33
- Changed: Update hot list + merge community reports 2026-04-13T14:38
- Changed: Update hot list + merge community reports 2026-04-13T13:40
- Changed: Update hot list + merge community reports 2026-04-13T12:56
- Changed: Update hot list + merge community reports 2026-04-13T12:29
- Changed: Update hot list + merge community reports 2026-04-13T11:31
- Changed: Update hot list + merge community reports 2026-04-13T10:36
- Changed: Update hot list + merge community reports 2026-04-13T09:41
- Changed: Update hot list + merge community reports 2026-04-13T08:47
- Changed: Update hot list + merge community reports 2026-04-13T08:44
