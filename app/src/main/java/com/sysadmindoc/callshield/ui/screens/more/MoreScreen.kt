package com.sysadmindoc.callshield.ui.screens.more

import androidx.activity.compose.BackHandler
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.screens.settings.SettingsScreen
import com.sysadmindoc.callshield.ui.screens.stats.StatsScreen
import com.sysadmindoc.callshield.ui.theme.*

@Composable
fun MoreScreen(viewModel: MainViewModel) {
    var currentView by remember { mutableIntStateOf(0) }

    if (currentView != 0) BackHandler { currentView = 0 }

    when (currentView) {
        1 -> { Column(Modifier.fillMaxSize()) { MoreTopBar("Statistics") { currentView = 0 }; StatsScreen(viewModel) } }
        2 -> { Column(Modifier.fillMaxSize()) { MoreTopBar("Settings") { currentView = 0 }; SettingsScreen(viewModel) } }
        3 -> { Column(Modifier.fillMaxSize()) { MoreTopBar("What's New") { currentView = 0 }; ChangelogScreen() } }
        4 -> { Column(Modifier.fillMaxSize()) { MoreTopBar("Protection Test") { currentView = 0 }; ProtectionTestScreen() } }
        else -> MoreHub(
            onStats = { currentView = 1 },
            onSettings = { currentView = 2 },
            onChangelog = { currentView = 3 },
            onTest = { currentView = 4 }
        )
    }
}

@Composable
fun MoreTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = CatSubtext) }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun MoreHub(onStats: () -> Unit, onSettings: () -> Unit, onChangelog: () -> Unit, onTest: () -> Unit) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Navigation cards
        MoreNavCard(
            icon = Icons.Default.BarChart, title = "Statistics",
            subtitle = "Spam trends, top offenders, area code heatmap",
            color = CatYellow, onClick = onStats
        )
        MoreNavCard(
            icon = Icons.Default.Settings, title = "Settings",
            subtitle = "Detection engines, quiet hours, backup, cleanup",
            color = CatMauve, onClick = onSettings
        )
        MoreNavCard(
            icon = Icons.Default.Verified, title = "Protection Test",
            subtitle = "Verify all detection layers and permissions work",
            color = CatGreen, onClick = onTest
        )
        MoreNavCard(
            icon = Icons.Default.NewReleases, title = "What's New",
            subtitle = "Changelog and version history",
            color = CatPeach, onClick = onChangelog
        )

        Spacer(Modifier.height(8.dp))

        // Quick links
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Quick Links", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                QuickLink(Icons.Default.Code, "GitHub Repository", CatGreen) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/SysAdminDoc/CallShield")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
                HorizontalDivider(color = CatOverlay.copy(alpha = 0.15f))
                QuickLink(Icons.Default.BugReport, "Report a Bug", CatPeach) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/SysAdminDoc/CallShield/issues/new")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
                HorizontalDivider(color = CatOverlay.copy(alpha = 0.15f))
                QuickLink(Icons.Default.Star, "Star on GitHub", CatYellow) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/SysAdminDoc/CallShield")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
                HorizontalDivider(color = CatOverlay.copy(alpha = 0.15f))
                QuickLink(Icons.Default.Flag, "Report Spam Number", CatRed) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/SysAdminDoc/CallShield/issues/new?template=spam_report.md")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
            }
        }

        // About
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("CallShield", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = CatGreen)
                Text("v1.2.4", style = MaterialTheme.typography.bodyMedium, color = CatSubtext)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Open-source spam call & text blocker with 15-layer detection engine + ML scorer. " +
                    "No API keys, no accounts, no tracking. Everything runs on-device.",
                    style = MaterialTheme.typography.bodySmall, color = CatOverlay,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AboutStat("15", "Layers", CatGreen)
                    AboutStat("56", "Files", CatBlue)
                    AboutStat("8K+", "Lines", CatPeach)
                    AboutStat("15", "Releases", CatYellow)
                }
                Spacer(Modifier.height(8.dp))
                Text("Made by SysAdminDoc", style = MaterialTheme.typography.labelSmall, color = CatOverlay)
                Text("MIT License", style = MaterialTheme.typography.labelSmall, color = CatOverlay)
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = CatOverlay.copy(alpha = 0.2f))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, tint = CatGreen, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Privacy: All detection runs on-device. No data is sent to any server. The only network request is syncing the spam database from GitHub.", style = MaterialTheme.typography.labelSmall, color = CatOverlay)
                }
            }
        }
    }
}

@Composable
fun MoreNavCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String, subtitle: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
            }
            Icon(Icons.Default.ChevronRight, null, tint = CatOverlay)
        }
    }
}

@Composable
fun QuickLink(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = CatText, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = CatOverlay, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun AboutStat(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = CatSubtext)
    }
}
