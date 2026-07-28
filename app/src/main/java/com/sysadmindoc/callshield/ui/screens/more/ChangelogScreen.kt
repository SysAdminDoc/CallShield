package com.sysadmindoc.callshield.ui.screens.more

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.ui.theme.*

@Composable
fun ChangelogScreen() {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        VersionEntry(
            "1.7.24",
            "Deep-audit fixes across detection, data, backups, and accessibility",
            isLatest = true,
            changes =
                listOf(
                    "Community reports validate every number and can't be used to remove trusted database entries",
                    "Backups keep all data under release optimization; restore refreshes range rules and keeps temporary blocks",
                    "Fixed SMS URL matching, whitelisted-sender protection, a wildcard slowdown, and international number formatting",
                    "Corrected the spam count, onboarding, Recent call types, search, and locale-aware timestamps",
                    "Labeled toggles for TalkBack, blocked-log actions, and honest notification state below Android 13",
                ),
        )
        VersionEntry(
            "1.7.23",
            "Deep-audit hardening across screening, backups, and accessibility",
            changes =
                listOf(
                    "Fixed roaming corrupting number matching — identities now canonicalize under the SIM's home region",
                    "Hardened screening edge cases: unknown-direction calls, duplicate-row urgency, and lettered SMS sender IDs",
                    "Made exports crash-free, blocklist imports transactional, and passphrase restores reject plaintext files",
                    "Fixed the caller ID overlay leaking telephony watchers and the role-loss alert lingering after recovery",
                    "Made settings toggles fully tappable TalkBack nodes and raised caption contrast in every theme",
                ),
        )
        VersionEntry(
            "1.7.22",
            "Safer trust controls, protected backups, and stronger release gates",
            changes =
                listOf(
                    "Added selected contact-group trust and category-specific call handling",
                    "Added passphrase-protected portable backups with atomic restore rollback",
                    "Added optional local-only warnings for known-risk outgoing calls",
                    "Added call-screening role-loss detection with actionable recovery guidance",
                    "Removed static-analysis baselines and hardened release metadata and signing preflight",
                ),
        )
        VersionEntry(
            "1.7.21",
            "Professional themes and a calmer, denser interface",
            changes =
                listOf(
                    "Added persistent System, Light, Graphite, and true-black AMOLED themes",
                    "Tightened typography, spacing, borders, and control density across shared components",
                    "Shortened onboarding and settings copy while preserving accessible action targets",
                    "Consolidated nested More destinations under one compact app header",
                    "Added automated contrast and theme-preference regression coverage",
                ),
        )
        VersionEntry(
            "1.7.20",
            "Privacy, recovery, and interaction hardening",
            changes =
                listOf(
                    "Excluded private call and message history from Android cloud backup and trimmed " +
                        "repository-only APK assets",
                    "Hardened caller-ID, deep-link, external-feed, backup-restore, repeated-call, and " +
                        "crash-log trust boundaries",
                    "Made temporary decisions and notification-source choices survive portable backup round trips",
                    "Preserved nested navigation across language changes and refined secondary " +
                        "settings, log, and statistics states",
                    "Restored the local report-pipeline regression test and warning-free Kotlin compilation",
                ),
        )
        VersionEntry(
            "1.7.19",
            "Roadmap completion: stronger detection, platform integrations, and release proof",
            changes =
                listOf(
                    "Added region and carrier-name trust/block rules with explicit priority safeguards",
                    "Improved on-device GBT recall while retaining the precision guard and legacy-model compatibility",
                    "Added notification-source controls, Android 16 sync progress, and an optional " +
                        "Android post-call review",
                    "Expanded call/SMS entrypoint, screening-deadline, accessibility, RTL, and API 35/37 " +
                        "device coverage",
                    "Surfaced ML health, rule conflicts, app language selection, and premium " +
                        "setup/navigation refinements",
                ),
        )
        VersionEntry(
            "1.7.17",
            "Reliability: self-healing sync, recovery, and boot survival",
            changes =
                listOf(
                    "RCS listener re-binds itself after the system disconnects it",
                    "Detects and rebuilds a corrupt on-disk database, then re-syncs the spam data",
                    "Reschedules work and re-binds the listener after a reboot or app update",
                    "Daily digest counts use bounded aggregate queries instead of full-window scans",
                ),
        )
        VersionEntry(
            "1.7.16",
            "Bounded imports and clearer model health",
            changes =
                listOf(
                    "Capped import/restore size and row counts to keep large files responsive",
                    "Aligned Android 16 grouped-notification alert behavior",
                    "Exposed typed ML model-health states for diagnostics",
                ),
        )
        VersionEntry(
            "1.7.13",
            "Fully free and keyless: the optional API key entry is gone",
            changes =
                listOf(
                    "Removed the optional AbstractAPI key field from Settings — CallShield now needs no API keys at all",
                    "Every lookup and enrichment source the app uses is free and requires no sign-up or credentials",
                    "Any key stored by an earlier version is purged from the device on first launch",
                    "Dropped the unused carrier/line-type network checker and its certificate pin",
                ),
        )
        VersionEntry(
            "1.7.12",
            "Durable blocked-call logging and community-report backoff",
            changes =
                listOf(
                    "Blocked-call decisions persist pending log rows before the call response",
                    "A Hilt-backed retry worker flushes pending blocked-call rows without duplicate logs or notifications",
                    "Room v10 adds log keys and a pending-log queue with duplicate-suppression and retry coverage",
                    "Community report submissions now surface server retry delays after Worker rate limits",
                    "Security-sensitive phone digit extraction now uses a shared ASCII-only utility",
                ),
        )
        VersionEntry(
            "1.7.11",
            "Premium Compose refinement: cohesive actions, states, and trust surfaces",
            changes =
                listOf(
                    "Shared premium actions, compact actions, icon tiles, and state cards unify high-traffic app flows",
                    "Dashboard, lookup, details, logs, diagnostics, onboarding, and settings align",
                    "Trusted notification source controls now show clearer active, disabled, and not-installed states",
                    "Statistics now presents a calmer first-run empty state instead of zero-value charts",
                    "README, changelog, and roadmap notes now match the v1.7.11 product surface",
                ),
        )
        VersionEntry(
            "1.7.10",
            "Modern Android stack refresh: Compose, WorkManager, DataStore, and release integrity",
            changes =
                listOf(
                    "Compose, Material 3, WorkManager, DataStore, OkHttp, AGP, Kotlin, KSP, and Room were refreshed",
                    "Optional AbstractAPI keys moved to private no-backup storage with clearer saved-state copy",
                    "Release builds now produce SHA256 sidecars and local guards check reproducible-build inputs",
                    "Network hosts use centralized certificate pinning for data, reporting, URL safety, and enrichment",
                    "Configuration-aware Compose copy keeps snackbar, toast, semantic, validation, and count text fresh",
                ),
        )
        VersionEntry(
            "1.7.9",
            "WorkManager schedule contracts: background jobs made easier to trust",
            changes =
                listOf(
                    "Sync, manual refresh, hot-list, and digest workers moved to WorkManager 2.11.2",
                    "JVM tests now cover repeat intervals, network constraints, initial delay, and retry backoff",
                    "Background refresh behavior stays explicit as release prep and dependency updates continue",
                ),
        )
        VersionEntry(
            "1.7.8",
            "DataStore privacy hardening: local credentials stay out of backup scope",
            changes =
                listOf(
                    "Settings moved to DataStore Preferences 1.2.1",
                    "Optional local API credentials are stored in no-backup private storage",
                    "Database and public preferences remain restorable for normal device transfers",
                ),
        )
        VersionEntry(
            "1.7.5",
            "Stats and scan feedback polish: localized labels and calmer errors",
            changes =
                listOf(
                    "Statistics now uses localized weekday labels for the weekly activity chart",
                    "Detection-source labels in Statistics are routed through string resources instead of hardcoded English",
                    "Call-log and SMS scan permission failures now use consistent resource-backed recovery copy",
                    "Source legend counts now use a formatted string resource for cleaner localization",
                ),
        )
        VersionEntry(
            "1.7.4",
            "Settings trust polish: safer optional API-key handling",
            changes =
                listOf(
                    "Optional AbstractAPI key entry is now masked by default, with explicit show/hide control",
                    "Settings now show clear saved, unsaved, and not-configured states before changes are committed",
                    "The save action is disabled until the local value changes, reducing accidental credential churn",
                    "Advanced settings copy now reinforces that the key stays on-device and only powers optional carrier enrichment",
                ),
        )
        VersionEntry(
            "1.7.3",
            "Premium polish pass: calmer chrome, tighter states, clearer trust feedback",
            changes =
                listOf(
                    "Shared visual system tightened: modest 12dp surface radius, zero negative type tracking, and no selected navigation pill backdrop",
                    "Blocked Log empty and filtered states now explain what happened and offer a one-tap recovery path back to all activity",
                    "Trusted notification source picker now shows installed-source coverage and skeleton loading while app labels resolve",
                    "Lookup and Number Detail use rectangular status treatments for risk/type labels instead of default Material chip backdrops",
                    "Report actions now use clearer flag semantics, with filter chips and action buttons brought into the same shape rhythm",
                ),
        )
        VersionEntry(
            "1.7.2",
            "Hardening: spoof-proof normalization, DoS guards, atomic crash logs",
            changes =
                listOf(
                    "ASCII-only phone-number normalization blocks homoglyph caller-ID bypasses",
                    "SMS analysis and multipart reassembly are capped at 16 KB to protect hot paths",
                    "Wildcard regex validation rejects catastrophic backtracking patterns before compile",
                    "Notice gates are LRU bounded, PendingIntent request codes are separated, and crash logs write atomically",
                    "Text-bearing pill and oval backdrops were removed from status, progress, and count treatments",
                ),
        )
        VersionEntry(
            "1.4.0",
            "Smart labels, silent voicemail, FTC report, emergency contacts, block reasoning",
            changes =
                listOf(
                    "Smart call labels — Debt Collector / Political / Robocall / Scam / Phishing / Telemarketer / Wangiri / Survey / Business / Unknown, shown on the Number Detail hero and in the blocked log",
                    "Silent voicemail mode — blocked calls reach voicemail silently instead of hard-rejecting, so your phone doesn't ring. Off by default; opt-in from Settings → Detection",
                    "One-tap FTC fraud report — any Number Detail screen now has a \"Report to FTC\" button that copies the number and opens reportfraud.ftc.gov",
                    "Emergency contacts — whitelist entries can be flagged as emergency, bypassing blocklist, quiet hours, and aggressive mode with a distinct red badge in the Whitelist tab",
                    "\"Why was this blocked?\" — Number Detail now shows a plain-English narrative of which detection layer fired, what heuristic reasons contributed, and the model's confidence",
                ),
        )
        VersionEntry(
            "1.3.0",
            "Crash reporter, instrumented tests, benchmark ceilings",
            changes =
                listOf(
                    "Local crash reporter captures uncaught exceptions to filesDir/crashes/ — share the latest log via the new \"Share Last Crash Log\" Quick Link in More (no telemetry phoned home)",
                    "Instrumented test suite: Compose UI tests for PremiumCard/SectionHeader/accentGlow, end-to-end CrashReporter IO, and the DashboardStatusModel state machine",
                    "GitHub Actions emulator workflow runs connected tests on every PR + master push",
                    "Hot-path microbenchmarks as unit tests enforce regression ceilings on WildcardRule.matches, CampaignDetector record/check, SpamMLScorer.score, and SpamHeuristics pure checks",
                    "BuildConfig fields (VERSION_NAME, VERSION_CODE) re-enabled for the crash reporter header",
                ),
        )
        VersionEntry(
            "1.2.15",
            "Audit Round 9 — Contact cache perf",
            changes =
                listOf(
                    "Contact whitelist lookups are now cached for 60 seconds — eliminates up to 4 redundant ContactsContract queries per incoming call on large contact lists (10–200 ms saved per call)",
                ),
        )
        VersionEntry(
            "1.2.14",
            "Audit Round 8 — Stats drift, wildcard SMS match, trusted-sender perf",
            changes =
                listOf(
                    "Stats screen daily chart and monthly trend now recompute at midnight (was frozen until new blocks arrived)",
                    "Wildcard area-code rules (e.g. +1212*) now match SMS senders without the +1 prefix via multi-normalization",
                    "Trusted-sender SMS check uses SQL WHERE pre-filter — no longer scans the entire sent/inbox folder in memory",
                    "New unit tests for wildcard multi-normalization (glob + E.164 + raw 10-digit + wrong-area-code rejection)",
                ),
        )
        VersionEntry(
            "1.2.13",
            "Audit Round 7 — UI campaign pollution + backup rules",
            changes =
                listOf(
                    "All UI spam checks (Lookup, Number Detail, Protection Test, Recent Calls) now use realtimeCall=false — no longer poison the campaign burst detector or pop caller-ID overlays",
                    "Protection test no longer feeds synthetic test numbers into the campaign detector",
                    "Backup rules referenced in manifest and properly scoped — database + DataStore included, caches excluded",
                    "New data_extraction_rules.xml for API 31+ cloud backup and device transfer",
                ),
        )
        VersionEntry(
            "1.2.12",
            "Audit Round 6 — DB migration guard + call screener crash",
            changes =
                listOf(
                    "Database destructive migration restricted to legacy versions 1-4 only — future schema upgrades that lack explicit migrations now crash during development instead of silently wiping user data in production",
                    "Call screener role request wrapped in try-catch on all 3 launch sites (Dashboard, Settings, Onboarding) — prevents crash on OEM ROMs that remove ROLE_CALL_SCREENING",
                ),
        )
        VersionEntry(
            "1.2.11",
            "Audit Round 5 — OkHttp response leaks + cache cleanup collision",
            changes =
                listOf(
                    "All 5 remote lookup modules (ExternalLookup, UrlSafetyChecker, NumberTypeChecker, CommunityContributor, WebLookup) now wrap OkHttp execute() in .use { } — previously the Response was leaked on non-2xx paths",
                    "Log export cleanup now filters by filename prefix — no longer nukes in-flight blocklist exports that share the same cache directory",
                ),
        )
        VersionEntry(
            "1.2.10",
            "Audit Round 4 — Time windows, screener lifetime, scanner isolation",
            changes =
                listOf(
                    "Dashboard \"today / this week / last week\" counts now roll forward on a one-minute time anchor — windows no longer freeze at app start and drift as the process stays alive",
                    "After-call \"Was this spam?\" feedback notification now fires reliably — moved off the short-lived CallScreeningService handler onto a process-lifetime scope",
                    "Historical Call Log and SMS Inbox scans no longer poison the live campaign-burst detector or pop caller-ID overlays for calls that already happened",
                    "Contact whitelist lookup closes its cursor on exception paths (defensive correctness)",
                ),
        )
        VersionEntry(
            "1.2.9",
            "Audit Round 3 — Correctness + Compose Hygiene",
            changes =
                listOf(
                    "Campaign burst detector no longer learns from contacts/dialed/repeat callers (false-positive fix)",
                    "Backup restore closes the input stream properly (file descriptor leak fix)",
                    "Notifications honor the API 33+ POST_NOTIFICATIONS runtime permission instead of throwing SecurityException",
                    "Daily digest skips silently when notification permission is revoked",
                    "Recent Calls list now uses stable item keys — filter changes no longer scramble per-row animation state",
                    "Blocked Log grouped view now uses stable keys — no more scroll jumps or row-swap bugs",
                    "Global search results use stable keys — fixes reorder glitches when re-searching",
                ),
        )
        VersionEntry(
            "1.2.8",
            "ML Engine + Campaign Detection + Accessibility",
            changes =
                listOf(
                    "Gradient-boosted tree ML model (20 features, pure Kotlin inference)",
                    "Campaign burst detection: auto-blocks NPA-NXX prefixes with 5+ calls in 1 hour",
                    "After-call feedback: \"Was this spam?\" notification with one-tap Block/Whitelist",
                    "378 strings extracted to strings.xml for localization support",
                    "150 unit tests + GitHub Actions CI pipeline",
                    "Full accessibility pass: 100 content descriptions, semantic grouping, 48dp touch targets",
                    "Weekly bar chart + source donut chart + monthly trend in Statistics",
                    "Signing credentials moved to local.properties (security hardening)",
                    "Call log scanner fixed (was blocking Main thread)",
                    "Sync freshness updates immediately after sync",
                    "Onboarding shows grant status, notification/overlay permissions",
                    "ANSWER_PHONE_CALLS permission for Samsung/Xiaomi compatibility",
                ),
        )
        VersionEntry(
            "1.2.7",
            "Build Fix + Deprecation Cleanup",
            changes =
                listOf(
                    "Fixed BlocklistScreen tab indicator crash (removed deprecated tabIndicatorOffset API)",
                    "Migrated remaining deprecated icons to AutoMirrored variants (ViewList, TrendingUp/Down/Flat)",
                    "Zero compilation warnings",
                ),
        )
        VersionEntry(
            "1.2.6",
            "Premium Redesign + Audit",
            changes =
                listOf(
                    "Complete premium UI overhaul — PremiumCard, accent glows, gradient dividers, refined typography",
                    "12 bug fixes: race conditions, JSON injection, UI hangs, thread leaks, date grouping",
                    "Shimmer loading skeletons replace raw spinners",
                    "Haptic feedback on all toggles, block/unblock, profile switches, and scan buttons",
                    "Swipe-to-delete now supports undo via snackbar",
                    "Confirmation dialog before clearing blocked log",
                    "Snackbar feedback for all blocklist add/delete operations",
                    "Slide + fade tab transitions with direction awareness",
                    "Changelog redesigned as vertical timeline with connected rail",
                    "Protection test results with staggered entrance animations",
                    "Caller ID overlay: rounded bottom corners, accent line, refined palette",
                    "Widget: updated color palette, uppercase label, tighter typography",
                    "Cloudflare Worker: type validation, body size limit, filename collision fix, rate limit handling",
                    "Auto-clearing stale status messages (restore, contribute, import results)",
                    "Dashboard: sync prompt when database is empty, hero entrance animation",
                    "Stats: weekly chart now shows day labels aligned to actual calendar days with today highlighted",
                    "Settings: standardized button heights, accent borders, icon backdrops on toggles",
                ),
        )
        VersionEntry(
            "1.2.5",
            "Backup & Proguard",
            changes =
                listOf(
                    "Backup/restore now includes SMS keyword rules (was missing)",
                    "Backup format bumped to v2 for keyword rules support",
                    "Added proguard keep rules for GitHubDataSource JSON models",
                    "Added proguard keep rule for BackupKeyword data class",
                ),
        )
        VersionEntry(
            "1.2.4",
            "README + Testing + Polish",
            changes =
                listOf(
                    "Complete README rewrite for v1.2.x features",
                    "Protection test: ML scorer, hot list data, notification access checks",
                    "Detection icons for ML scorer, RCS, hot list, campaign ranges",
                    "StatsScreen: type breakdown colors for all new detection methods",
                    "Theme: added Catppuccin Teal and Lavender colors",
                ),
        )
        VersionEntry(
            "1.2.3",
            "UX Polish + Performance",
            changes =
                listOf(
                    "Onboarding: updated to reflect 15-layer detection + ML scorer",
                    "Onboarding: permission request button on detection page",
                    "Dashboard: engine count now includes ML scorer, RCS filter, repeat caller",
                    "Widget: replaced full record load with efficient count query",
                    "Recent Calls: batch spam checks per unique number (was 1 query per call)",
                    "Recent Calls: batch contact lookups per unique number",
                ),
        )
        VersionEntry(
            "1.2.2",
            "Audit Round 2",
            changes =
                listOf(
                    "Fix CommunityContributor JSON injection",
                    "Fix BootReceiver: schedule DigestWorker on boot",
                    "Fix HotListSyncWorker: one bad entry no longer breaks sync",
                    "Fix DigestWorker: database errors no longer crash worker",
                    "Migrate all deprecated Material icons to AutoMirrored variants",
                    "Fix extract_spam_domains.py double-slice",
                    "Fix generate_hot_list.py missing first_seen field",
                ),
        )
        VersionEntry(
            "1.2.1",
            "Audit Round 1",
            changes =
                listOf(
                    "Fix SmsContentAnalyzer regex crash + URL loop early-exit",
                    "Fix CallerIdOverlayService handler posts after destroy",
                    "Fix CallShieldTileService runBlocking ANR",
                    "Fix UrlSafetyChecker JSON injection",
                    "Fix LogExporter CSV corruption",
                    "Fix SpamMLScorer thread safety",
                    "Fix NumberDetailScreen coroutine leak",
                    "Fix BlocklistScreen: validate regex before adding",
                    "Fix merge_community_reports.py data loss on exception",
                ),
        )
        VersionEntry(
            "1.2.0",
            "ML Scorer + RCS + Hot List",
            changes =
                listOf(
                    "On-device 15-feature ML spam scorer (logistic regression, threshold 0.7)",
                    "RCS notification filter via NotificationListenerService",
                    "30-minute hot list sync: trending numbers, campaign ranges, spam domains",
                    "SIT tone player for anti-autodialer during caller ID overlay",
                    "URL safety checker (URLhaus) — phishing/malware notifications",
                    "SMS context trust — allow known conversations automatically",
                    "AbstractAPI carrier/line-type enrichment (optional key)",
                    "OpenCNAM caller name lookup in overlay",
                    "Hot campaign range detection in heuristic engine",
                    "Spam domain blocklist in SMS content analysis",
                    "Weekly ML model retraining + domain extraction in CI",
                    "30-minute hot list refresh in merge-reports workflow",
                ),
        )
        VersionEntry(
            "1.1.0",
            "Live Caller ID + Community Database",
            changes =
                listOf(
                    "Live multi-source caller ID overlay (SkipCalls, PhoneBlock, WhoCalledMe)",
                    "Real-time spam score with parallel lookups",
                    "Anonymous community spam reporting via Cloudflare Worker",
                    "FCC database expanded to 32,933 confirmed spam numbers",
                    "Expandable action buttons on log and recent entries",
                    "False positive reporting to community database",
                ),
        )
        VersionEntry(
            "1.0.0",
            "Initial Release",
            isLast = true,
            changes =
                listOf(
                    "11-layer detection engine with confidence scoring",
                    "Number Lookup with animated spam score gauge",
                    "Caller ID overlay for all incoming non-contact calls",
                    "Smart suggestions — auto-detect area code spam patterns",
                    "Blocking profiles: Work, Personal, Sleep, Maximum, Off",
                    "Callback detection — don't block numbers you recently called",
                    "Repeated call allow-through — urgent callers get through",
                    "330+ US/CA area code lookup with city/state",
                    "Custom SMS keyword blocking rules",
                    "Wildcard and regex number blocking",
                    "Time-based quiet hours with configurable schedule",
                    "Frequency auto-escalation (3+ calls = auto-block)",
                    "STIR/SHAKEN carrier verification (Android 11+)",
                    "Heuristic engine: VoIP ranges, wangiri, neighbor spoof",
                    "30+ SMS content analysis regex patterns",
                    "Recent calls with contact names and risk indicators",
                    "Swipe-to-dismiss blocked log with grouping",
                    "Call log and SMS inbox scanners",
                    "Full backup/restore as JSON",
                    "CSV log export for analysis",
                    "Daily digest notification",
                    "Auto-cleanup with configurable retention",
                    "Quick Settings tile and app shortcuts",
                    "Home screen widget",
                    "After-call spam rating notifications",
                    "Community reporting via GitHub Issues",
                    "Reverse phone lookup via web scraping",
                    "FTC Do Not Call complaint filing",
                    "Statistics: weekly chart, type breakdown, top offenders, area code heatmap, hourly heatmap",
                    "Protection test — validates all layers and permissions",
                    "Privacy-first: all detection runs on-device",
                    "AMOLED black theme with Catppuccin Mocha accents",
                ),
        )
    }
}

@Composable
fun VersionEntry(
    version: String,
    title: String = "",
    isLatest: Boolean = false,
    isLast: Boolean = false,
    changes: List<String>,
) {
    val accentColor = if (isLatest) CatGreen else CatSubtext

    Row(modifier = Modifier.fillMaxWidth()) {
        // Timeline rail
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(32.dp),
        ) {
            // Dot
            Box(
                modifier =
                    Modifier
                        .size(if (isLatest) 14.dp else 10.dp)
                        .clip(CircleShape)
                        .background(accentColor),
            )
            // Vertical line
            if (!isLast) {
                Box(
                    modifier =
                        Modifier
                            .width(2.dp)
                            .weight(1f, fill = true)
                            .background(CatMuted.copy(alpha = 0.3f)),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // Content card
        PremiumCard(
            modifier = Modifier.weight(1f).padding(bottom = 12.dp),
            accentColor = if (isLatest) CatGreen else null,
            cornerRadius = ShapeLg,
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "v$version",
                        style =
                            MaterialTheme.typography.titleMedium.copy(
                                letterSpacing = 0.sp,
                            ),
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                    )
                    if (isLatest) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = CatGreen.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                stringResource(R.string.changelog_latest),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style =
                                    MaterialTheme.typography.labelSmall.copy(
                                        letterSpacing = 0.sp,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                color = CatGreen,
                            )
                        }
                    }
                }
                if (title.isNotEmpty()) {
                    Text(title, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                }
                Spacer(Modifier.height(8.dp))
                GradientDivider(color = accentColor)
                Spacer(Modifier.height(8.dp))
                changes.forEach { change ->
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Box(
                            modifier =
                                Modifier
                                    .padding(top = 6.dp)
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(CatMuted),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(change, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                    }
                }
            }
        }
    }
}
