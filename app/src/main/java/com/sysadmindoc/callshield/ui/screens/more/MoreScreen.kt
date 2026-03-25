package com.sysadmindoc.callshield.ui.screens.more

import androidx.activity.compose.BackHandler
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.screens.settings.SettingsScreen
import com.sysadmindoc.callshield.ui.screens.stats.StatsScreen
import com.sysadmindoc.callshield.ui.theme.*

@Composable
fun MoreScreen(viewModel: MainViewModel) {
    var currentView by rememberSaveable { mutableIntStateOf(0) }

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
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = CatSubtext) }
            Spacer(Modifier.width(4.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleLarge.copy(letterSpacing = (-0.3).sp),
                fontWeight = FontWeight.Bold
            )
        }
        GradientDivider()
    }
}

@Composable
fun MoreHub(onStats: () -> Unit, onSettings: () -> Unit, onChangelog: () -> Unit, onTest: () -> Unit) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
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

        Spacer(Modifier.height(4.dp))

        // Quick links
        PremiumCard {
            Column(modifier = Modifier.padding(16.dp)) {
                SectionHeader("Quick Links", CatBlue)
                Spacer(Modifier.height(8.dp))
                QuickLink(Icons.Default.Code, "GitHub Repository", CatGreen) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/SysAdminDoc/CallShield")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
                GradientDivider()
                QuickLink(Icons.Default.BugReport, "Report a Bug", CatPeach) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/SysAdminDoc/CallShield/issues/new")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
                GradientDivider()
                QuickLink(Icons.Default.Star, "Star on GitHub", CatYellow) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/SysAdminDoc/CallShield")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
                GradientDivider()
                QuickLink(Icons.Default.Flag, "Report Spam Number", CatRed) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/SysAdminDoc/CallShield/issues/new?template=spam_report.md")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
            }
        }

        // About
        PremiumCard(accentColor = CatGreen) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "CallShield",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = CatGreen,
                    modifier = Modifier.accentGlow(CatGreen, 300f, 0.04f)
                )
                Spacer(Modifier.height(4.dp))
                Text("v1.2.7", style = MaterialTheme.typography.bodyMedium, color = CatSubtext)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Open-source spam call & text blocker with 15-layer detection engine + ML scorer. " +
                    "No API keys, no accounts, no tracking. Everything runs on-device.",
                    style = MaterialTheme.typography.bodySmall, color = CatOverlay,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    AboutStat("15", "Layers", CatGreen)
                    AboutStat("9K+", "Lines", CatPeach)
                    AboutStat("16", "Releases", CatYellow)
                }
                Spacer(Modifier.height(12.dp))
                GradientDivider()
                Spacer(Modifier.height(12.dp))
                Text("Made by SysAdminDoc", style = MaterialTheme.typography.labelSmall, color = CatSubtext)
                Text("MIT License", style = MaterialTheme.typography.labelSmall, color = CatOverlay)
                Spacer(Modifier.height(10.dp))
                GradientDivider()
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, tint = CatGreen, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("All detection runs on-device. No data sent to any server.", style = MaterialTheme.typography.labelSmall, color = CatOverlay)
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
    PremiumCard(onClick = onClick, accentColor = color) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(color.copy(alpha = 0.08f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
            }
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
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
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
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(4.dp)
                .background(CatMuted.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
        )
    }
}
