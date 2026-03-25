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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sysadmindoc.callshield.ui.theme.*

@Composable
fun ChangelogScreen() {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        VersionEntry("1.2.7", "Build Fix + Deprecation Cleanup", isLatest = true, changes = listOf(
            "Fixed BlocklistScreen tab indicator crash (removed deprecated tabIndicatorOffset API)",
            "Migrated remaining deprecated icons to AutoMirrored variants (ViewList, TrendingUp/Down/Flat)",
            "Zero compilation warnings",
        ))
        VersionEntry("1.2.6", "Premium Redesign + Audit", changes = listOf(
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
        ))
        VersionEntry("1.2.5", "Backup & Proguard", changes = listOf(
            "Backup/restore now includes SMS keyword rules (was missing)",
            "Backup format bumped to v2 for keyword rules support",
            "Added proguard keep rules for GitHubDataSource JSON models",
            "Added proguard keep rule for BackupKeyword data class",
        ))
        VersionEntry("1.2.4", "README + Testing + Polish", changes = listOf(
            "Complete README rewrite for v1.2.x features",
            "Protection test: ML scorer, hot list data, notification access checks",
            "Detection icons for ML scorer, RCS, hot list, campaign ranges",
            "StatsScreen: type breakdown colors for all new detection methods",
            "Theme: added Catppuccin Teal and Lavender colors",
        ))
        VersionEntry("1.2.3", "UX Polish + Performance", changes = listOf(
            "Onboarding: updated to reflect 15-layer detection + ML scorer",
            "Onboarding: permission request button on detection page",
            "Dashboard: engine count now includes ML scorer, RCS filter, repeat caller",
            "Widget: replaced full record load with efficient count query",
            "Recent Calls: batch spam checks per unique number (was 1 query per call)",
            "Recent Calls: batch contact lookups per unique number",
        ))
        VersionEntry("1.2.2", "Audit Round 2", changes = listOf(
            "Fix CommunityContributor JSON injection",
            "Fix BootReceiver: schedule DigestWorker on boot",
            "Fix HotListSyncWorker: one bad entry no longer breaks sync",
            "Fix DigestWorker: database errors no longer crash worker",
            "Migrate all deprecated Material icons to AutoMirrored variants",
            "Fix extract_spam_domains.py double-slice",
            "Fix generate_hot_list.py missing first_seen field",
        ))
        VersionEntry("1.2.1", "Audit Round 1", changes = listOf(
            "Fix SmsContentAnalyzer regex crash + URL loop early-exit",
            "Fix CallerIdOverlayService handler posts after destroy",
            "Fix CallShieldTileService runBlocking ANR",
            "Fix UrlSafetyChecker JSON injection",
            "Fix LogExporter CSV corruption",
            "Fix SpamMLScorer thread safety",
            "Fix NumberDetailScreen coroutine leak",
            "Fix BlocklistScreen: validate regex before adding",
            "Fix merge_community_reports.py data loss on exception",
        ))
        VersionEntry("1.2.0", "ML Scorer + RCS + Hot List", changes = listOf(
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
        ))
        VersionEntry("1.1.0", "Live Caller ID + Community Database", changes = listOf(
            "Live multi-source caller ID overlay (SkipCalls, PhoneBlock, WhoCalledMe)",
            "Real-time spam score with parallel lookups",
            "Anonymous community spam reporting via Cloudflare Worker",
            "FCC database expanded to 32,933 confirmed spam numbers",
            "Expandable action buttons on log and recent entries",
            "False positive reporting to community database",
        ))
        VersionEntry("1.0.0", "Initial Release", isLast = true, changes = listOf(
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
        ))
    }
}

@Composable
fun VersionEntry(
    version: String,
    title: String = "",
    isLatest: Boolean = false,
    isLast: Boolean = false,
    changes: List<String>
) {
    val accentColor = if (isLatest) CatGreen else CatSubtext

    Row(modifier = Modifier.fillMaxWidth()) {
        // Timeline rail
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(32.dp)
        ) {
            // Dot
            Box(
                modifier = Modifier
                    .size(if (isLatest) 14.dp else 10.dp)
                    .clip(CircleShape)
                    .background(accentColor)
            )
            // Vertical line
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f, fill = true)
                        .background(CatMuted.copy(alpha = 0.3f))
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // Content card
        PremiumCard(
            modifier = Modifier.weight(1f).padding(bottom = 16.dp),
            accentColor = if (isLatest) CatGreen else null,
            cornerRadius = 16.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "v$version",
                        style = MaterialTheme.typography.titleMedium.copy(
                            letterSpacing = (-0.2).sp
                        ),
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                    if (isLatest) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = CatGreen.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                "LATEST",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    letterSpacing = 1.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = CatGreen
                            )
                        }
                    }
                }
                if (title.isNotEmpty()) {
                    Text(title, style = MaterialTheme.typography.bodySmall, color = CatOverlay)
                }
                Spacer(Modifier.height(10.dp))
                GradientDivider(color = accentColor)
                Spacer(Modifier.height(10.dp))
                changes.forEach { change ->
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Box(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(CatMuted)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(change, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                    }
                }
            }
        }
    }
}
