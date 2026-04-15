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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.sysadmindoc.callshield.BuildConfig
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.service.CrashReporter
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.screens.settings.SettingsScreen
import com.sysadmindoc.callshield.ui.screens.stats.StatsScreen
import com.sysadmindoc.callshield.ui.theme.*
import java.text.NumberFormat

@Composable
fun MoreScreen(viewModel: MainViewModel) {
    var currentView by rememberSaveable { mutableIntStateOf(0) }

    if (currentView != 0) BackHandler { currentView = 0 }

    when (currentView) {
        1 -> { Column(Modifier.fillMaxSize()) { MoreTopBar(stringResource(R.string.more_statistics)) { currentView = 0 }; StatsScreen(viewModel) } }
        2 -> { Column(Modifier.fillMaxSize()) { MoreTopBar(stringResource(R.string.more_settings)) { currentView = 0 }; SettingsScreen(viewModel) } }
        3 -> { Column(Modifier.fillMaxSize()) { MoreTopBar(stringResource(R.string.more_whats_new)) { currentView = 0 }; ChangelogScreen() } }
        4 -> { Column(Modifier.fillMaxSize()) { MoreTopBar(stringResource(R.string.more_protection_test)) { currentView = 0 }; ProtectionTestScreen() } }
        else -> MoreHub(
            viewModel = viewModel,
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
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = CatSubtext) }
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
fun MoreHub(viewModel: MainViewModel, onStats: () -> Unit, onSettings: () -> Unit, onChangelog: () -> Unit, onTest: () -> Unit) {
    val context = LocalContext.current
    val spamCount by viewModel.spamCount.collectAsState()
    val blockedToday by viewModel.blockedToday.collectAsState()
    val blockCallsEnabled by viewModel.blockCallsEnabled.collectAsState()
    val blockSmsEnabled by viewModel.blockSmsEnabled.collectAsState()
    val lastSync by viewModel.lastSyncTimestamp.collectAsState()
    val protectionAccent = when {
        blockCallsEnabled || blockSmsEnabled -> CatGreen
        else -> CatPeach
    }
    val appVersion = "v${BuildConfig.VERSION_NAME}"
    val localizedSpamCount = remember(spamCount) { NumberFormat.getIntegerInstance().format(spamCount) }
    val localizedBlockedToday = remember(blockedToday) { NumberFormat.getIntegerInstance().format(blockedToday) }
    val protectionLabel = when {
        blockCallsEnabled && blockSmsEnabled -> stringResource(R.string.more_snapshot_calls_texts)
        blockCallsEnabled -> stringResource(R.string.more_snapshot_calls)
        blockSmsEnabled -> stringResource(R.string.more_snapshot_texts)
        else -> stringResource(R.string.more_snapshot_paused)
    }
    val syncLabel = when {
        lastSync <= 0L -> stringResource(R.string.more_snapshot_never_synced)
        else -> {
            val ago = System.currentTimeMillis() - lastSync
            when {
                ago < 60_000 -> stringResource(R.string.dashboard_synced_just_now)
                ago < 3_600_000 -> stringResource(R.string.dashboard_synced_minutes_ago, (ago / 60_000).toInt())
                ago < 86_400_000 -> stringResource(R.string.dashboard_synced_hours_ago, (ago / 3_600_000).toInt())
                else -> stringResource(R.string.dashboard_synced_days_ago, (ago / 86_400_000).toInt())
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PremiumCard(accentColor = protectionAccent) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(stringResource(R.string.more_snapshot_title), protectionAccent)
                Text(
                    protectionLabel,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = protectionAccent
                )
                Text(
                    syncLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = CatSubtext
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusPill(
                        text = stringResource(R.string.more_trust_on_device),
                        color = CatGreen
                    )
                    StatusPill(
                        text = stringResource(R.string.more_trust_open_source),
                        color = CatBlue
                    )
                    StatusPill(
                        text = stringResource(R.string.more_trust_no_account),
                        color = CatPeach
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    AboutStat(localizedSpamCount, stringResource(R.string.more_snapshot_database), CatGreen)
                    AboutStat(localizedBlockedToday, stringResource(R.string.more_snapshot_today), CatPeach)
                    AboutStat(appVersion, stringResource(R.string.more_snapshot_version), CatYellow)
                }
            }
        }

        PremiumCard(accentColor = CatYellow) {
            Column(modifier = Modifier.padding(16.dp)) {
                SectionHeader(stringResource(R.string.more_section_tools), CatYellow)
                Spacer(Modifier.height(8.dp))
                MoreNavCard(
                    icon = Icons.Default.BarChart,
                    title = stringResource(R.string.more_statistics),
                    subtitle = stringResource(R.string.more_statistics_subtitle),
                    color = CatYellow,
                    onClick = onStats
                )
                GradientDivider()
                MoreNavCard(
                    icon = Icons.Default.Verified,
                    title = stringResource(R.string.more_protection_test),
                    subtitle = stringResource(R.string.more_protection_test_subtitle),
                    color = CatGreen,
                    onClick = onTest
                )
                GradientDivider()
                MoreNavCard(
                    icon = Icons.Default.Settings,
                    title = stringResource(R.string.more_settings),
                    subtitle = stringResource(R.string.more_settings_subtitle),
                    color = CatMauve,
                    onClick = onSettings
                )
            }
        }

        PremiumCard(accentColor = CatPeach) {
            Column(modifier = Modifier.padding(16.dp)) {
                SectionHeader(stringResource(R.string.more_section_release), CatPeach)
                Spacer(Modifier.height(8.dp))
                MoreNavCard(
                    icon = Icons.Default.NewReleases,
                    title = stringResource(R.string.more_whats_new),
                    subtitle = stringResource(R.string.more_whats_new_subtitle),
                    color = CatPeach,
                    onClick = onChangelog
                )
                GradientDivider()
                QuickLink(
                    icon = Icons.Default.Description,
                    label = stringResource(R.string.more_share_crash_log),
                    subtitle = stringResource(R.string.more_share_crash_log_subtitle),
                    color = CatMauve,
                    external = false
                ) {
                    val intent = CrashReporter.shareLatestCrashIntent(context)
                    if (intent != null) {
                        context.startActivity(Intent.createChooser(intent, null).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } else {
                        Toast.makeText(context, R.string.more_no_crash_logs, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        PremiumCard(accentColor = CatBlue) {
            Column(modifier = Modifier.padding(16.dp)) {
                SectionHeader(stringResource(R.string.more_section_support), CatBlue)
                Spacer(Modifier.height(8.dp))
                QuickLink(
                    icon = Icons.Default.Code,
                    label = stringResource(R.string.more_github_repo),
                    subtitle = stringResource(R.string.more_github_repo_subtitle),
                    color = CatGreen
                ) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/SysAdminDoc/CallShield")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
                GradientDivider()
                QuickLink(
                    icon = Icons.Default.BugReport,
                    label = stringResource(R.string.more_report_bug),
                    subtitle = stringResource(R.string.more_report_bug_subtitle),
                    color = CatPeach
                ) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/SysAdminDoc/CallShield/issues/new")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
                GradientDivider()
                QuickLink(
                    icon = Icons.Default.Star,
                    label = stringResource(R.string.more_star_github),
                    subtitle = stringResource(R.string.more_star_github_subtitle),
                    color = CatYellow
                ) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/SysAdminDoc/CallShield")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
                GradientDivider()
                QuickLink(
                    icon = Icons.Default.Flag,
                    label = stringResource(R.string.more_report_spam_number),
                    subtitle = stringResource(R.string.more_report_spam_number_subtitle),
                    color = CatRed
                ) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/SysAdminDoc/CallShield/issues/new?template=spam_report.md")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
            }
        }

        // About
        PremiumCard(accentColor = CatGreen) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = CatGreen,
                    modifier = Modifier.accentGlow(CatGreen, 300f, 0.04f)
                )
                Spacer(Modifier.height(4.dp))
                Text(appVersion, style = MaterialTheme.typography.bodyMedium, color = CatSubtext)
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.more_about_description),
                    style = MaterialTheme.typography.bodySmall, color = CatOverlay,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    AboutStat(stringResource(R.string.more_about_layers), stringResource(R.string.more_about_layers_label), CatGreen)
                    AboutStat(stringResource(R.string.more_about_lines), stringResource(R.string.more_about_lines_label), CatPeach)
                    AboutStat(stringResource(R.string.more_about_releases), stringResource(R.string.more_about_releases_label), CatYellow)
                }
                Spacer(Modifier.height(12.dp))
                GradientDivider()
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.more_made_by), style = MaterialTheme.typography.labelSmall, color = CatSubtext)
                Text(stringResource(R.string.more_license), style = MaterialTheme.typography.labelSmall, color = CatOverlay)
                Spacer(Modifier.height(10.dp))
                GradientDivider()
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = stringResource(R.string.cd_privacy), tint = CatGreen, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.more_privacy_note), style = MaterialTheme.typography.labelSmall, color = CatOverlay)
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
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(color.copy(alpha = 0.10f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = CatText)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.cd_chevron_right), tint = CatOverlay)
    }
}

@Composable
fun QuickLink(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    subtitle: String,
    color: androidx.compose.ui.graphics.Color,
    external: Boolean = true,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(color.copy(alpha = 0.10f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(label, color = CatText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = CatSubtext, style = MaterialTheme.typography.bodySmall)
        }
        Icon(
            if (external) Icons.AutoMirrored.Filled.OpenInNew else Icons.Default.ChevronRight,
            contentDescription = if (external) stringResource(R.string.cd_open_external) else stringResource(R.string.cd_chevron_right),
            tint = CatOverlay,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
fun AboutStat(value: String, label: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = CatSubtext)
    }
}
