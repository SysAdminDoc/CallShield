package com.sysadmindoc.callshield.ui.screens.more

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
        VersionEntry("3.0.0", listOf(
            "Smart suggestions — auto-detect area code patterns, one-tap block",
            "Caller ID overlay for ALL non-contact calls with location",
            "Auto-paste clipboard in Number Lookup",
            "Notification tap opens number detail directly",
        ))
        VersionEntry("2.9.0", listOf(
            "More hub with Stats, Settings, Quick Links, About",
            "Contact names in Recent Calls",
            "Logo in README",
            "Weekly stats card on dashboard",
        ))
        VersionEntry("2.8.0", listOf(
            "Reverse phone lookup via web scraping",
            "Sync freshness indicator on dashboard",
            "Permission check warning banner",
        ))
        VersionEntry("2.7.0", listOf(
            "App shortcuts (long-press icon)",
            "Blocking profiles: Work/Personal/Sleep/Maximum/Off",
            "Share number as spam warning",
            "Notification grouping and rate limiting",
        ))
        VersionEntry("2.6.0", listOf(
            "Quick Settings tile",
            "SMS inbox scanner",
            "Area code spam heatmap in Stats",
        ))
        VersionEntry("2.5.0", listOf(
            "Number Lookup with spam score gauge",
            "Detection method icons",
            "Haptic feedback",
            "CSV log export",
            "Contact name in number detail",
        ))
        VersionEntry("2.4.0", listOf(
            "Swipe-to-dismiss in blocked log",
            "Global search bar",
            "Deep link handling (tel: intents)",
            "Log grouping by number",
            "Auto-cleanup old log entries",
        ))
        VersionEntry("2.3.0", listOf(
            "Custom SMS keyword blocking rules",
            "Whitelist management UI",
            "Animated dashboard (pulsing shield, counter rollup)",
        ))
        VersionEntry("2.0.0", listOf(
            "11-layer detection engine",
            "Caller ID overlay, home screen widget",
            "Wildcard/regex rules, quiet hours",
            "Call log scanner, after-call rating",
            "Notification quick actions, community reporting",
            "Export/import blocklist, backup/restore",
        ))
        VersionEntry("1.0.0", listOf(
            "Initial release",
            "GitHub-hosted spam database with FTC data",
            "Call screening + SMS filtering",
            "STIR/SHAKEN verification",
            "AMOLED dark theme with Catppuccin Mocha",
        ))
    }
}

@Composable
fun VersionEntry(version: String, changes: List<String>) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("v$version", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = CatGreen)
            Spacer(Modifier.height(6.dp))
            changes.forEach { change ->
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text("  -  ", color = CatOverlay, style = MaterialTheme.typography.bodySmall)
                    Text(change, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                }
            }
        }
    }
}
