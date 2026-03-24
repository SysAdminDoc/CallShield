package com.sysadmindoc.callshield.ui.screens.more

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.ui.theme.*

@Composable
fun ChangelogScreen() {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        VersionEntry("1.2.0", "ML Scorer + RCS + Hot List", listOf(
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
        VersionEntry("1.1.0", "Live Caller ID + Community Database", listOf(
            "Live multi-source caller ID overlay (SkipCalls, PhoneBlock, WhoCalledMe)",
            "Real-time spam score with parallel lookups",
            "Anonymous community spam reporting via Cloudflare Worker",
            "FCC database expanded to 32,933 confirmed spam numbers",
            "Expandable action buttons on log and recent entries",
            "False positive reporting to community database",
        ))
        VersionEntry("1.0.0", "Initial Release", listOf(
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
fun VersionEntry(version: String, title: String = "", changes: List<String>) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row {
                Text("v$version", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = CatGreen)
                if (title.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text("— $title", style = MaterialTheme.typography.titleMedium, color = CatSubtext)
                }
            }
            Spacer(Modifier.height(6.dp))
            changes.forEach { change ->
                Row(modifier = Modifier.padding(vertical = 1.dp)) {
                    Text("  -  ", color = CatOverlay, style = MaterialTheme.typography.bodySmall)
                    Text(change, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                }
            }
        }
    }
}
