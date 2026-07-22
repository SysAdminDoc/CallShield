package com.sysadmindoc.callshield.ui.screens.more

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
        1 -> {
            Column(Modifier.fillMaxSize()) {
                MoreTopBar(stringResource(R.string.more_statistics)) { currentView = 0 }
                StatsScreen(viewModel)
            }
        }

        2 -> {
            Column(Modifier.fillMaxSize()) {
                MoreTopBar(stringResource(R.string.more_settings)) { currentView = 0 }
                SettingsScreen(viewModel)
            }
        }

        3 -> {
            Column(Modifier.fillMaxSize()) {
                MoreTopBar(stringResource(R.string.more_whats_new)) { currentView = 0 }
                ChangelogScreen()
            }
        }

        4 -> {
            Column(Modifier.fillMaxSize()) {
                MoreTopBar(stringResource(R.string.more_protection_test)) { currentView = 0 }
                ProtectionTestScreen()
            }
        }

        else -> {
            MoreHub(
                viewModel = viewModel,
                onStats = { currentView = 1 },
                onSettings = { currentView = 2 },
                onChangelog = { currentView = 3 },
                onTest = { currentView = 4 },
            )
        }
    }
}

@Composable
fun MoreTopBar(
    title: String,
    onBack: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                    tint = CatSubtext,
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 0.sp),
                fontWeight = FontWeight.SemiBold,
            )
        }
        GradientDivider()
    }
}

@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
fun MoreHub(
    viewModel: MainViewModel,
    onStats: () -> Unit,
    onSettings: () -> Unit,
    onChangelog: () -> Unit,
    onTest: () -> Unit,
) {
    val context = LocalContext.current
    val spamCount by viewModel.spamCount.collectAsStateWithLifecycle()
    val blockedToday by viewModel.blockedToday.collectAsStateWithLifecycle()
    val blockCallsEnabled by viewModel.blockCallsEnabled.collectAsStateWithLifecycle()
    val blockSmsEnabled by viewModel.blockSmsEnabled.collectAsStateWithLifecycle()
    val lastSync by viewModel.lastSyncTimestamp.collectAsStateWithLifecycle()
    val appVersion = "v${BuildConfig.VERSION_NAME}"
    val localizedSpamCount =
        remember(spamCount) {
            NumberFormat.getIntegerInstance().format(spamCount)
        }
    val localizedBlockedToday =
        remember(blockedToday) {
            NumberFormat.getIntegerInstance().format(blockedToday)
        }
    val protectionLabel =
        when {
            blockCallsEnabled && blockSmsEnabled -> stringResource(R.string.more_snapshot_calls_texts)
            blockCallsEnabled -> stringResource(R.string.more_snapshot_calls)
            blockSmsEnabled -> stringResource(R.string.more_snapshot_texts)
            else -> stringResource(R.string.more_snapshot_paused)
        }
    val syncLabel =
        when {
            lastSync <= 0L -> {
                stringResource(R.string.more_snapshot_never_synced)
            }

            else -> {
                val ago = System.currentTimeMillis() - lastSync
                when {
                    ago < 60_000 -> {
                        stringResource(R.string.dashboard_synced_just_now)
                    }

                    ago < 3_600_000 -> {
                        stringResource(R.string.dashboard_synced_minutes_ago, (ago / 60_000).toInt())
                    }

                    ago < 86_400_000 -> {
                        stringResource(R.string.dashboard_synced_hours_ago, (ago / 3_600_000).toInt())
                    }

                    else -> {
                        stringResource(R.string.dashboard_synced_days_ago, (ago / 86_400_000).toInt())
                    }
                }
            }
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionHeader(stringResource(R.string.more_snapshot_title))
            Text(
                protectionLabel,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = CatText,
            )
            Text(syncLabel, style = MaterialTheme.typography.bodyMedium, color = CatSubtext)
            Text(
                stringResource(R.string.more_trust_summary),
                style = MaterialTheme.typography.bodySmall,
                color = CatOverlay,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MoreMetric(
                    localizedSpamCount,
                    stringResource(R.string.more_snapshot_database),
                    Modifier.weight(1f),
                )
                MoreMetricDivider()
                MoreMetric(
                    localizedBlockedToday,
                    stringResource(R.string.more_snapshot_today),
                    Modifier.weight(1f),
                )
                MoreMetricDivider()
                MoreMetric(appVersion, stringResource(R.string.more_snapshot_version), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            GradientDivider()
        }

        MoreSection(stringResource(R.string.more_section_tools)) {
            MoreNavCard(
                Icons.Default.BarChart,
                stringResource(R.string.more_statistics),
                stringResource(R.string.more_statistics_subtitle),
                CatGreen,
                onStats,
            )
            GradientDivider()
            MoreNavCard(
                Icons.Default.Verified,
                stringResource(R.string.more_protection_test),
                stringResource(R.string.more_protection_test_subtitle),
                CatGreen,
                onTest,
            )
            GradientDivider()
            MoreNavCard(
                Icons.Default.Settings,
                stringResource(R.string.more_settings),
                stringResource(R.string.more_settings_subtitle),
                CatGreen,
                onSettings,
            )
        }

        MoreSection(stringResource(R.string.more_section_release)) {
            MoreNavCard(
                Icons.Default.NewReleases,
                stringResource(R.string.more_whats_new),
                stringResource(R.string.more_whats_new_subtitle),
                CatSubtext,
                onChangelog,
            )
            GradientDivider()
            QuickLink(
                icon = Icons.Default.Description,
                label = stringResource(R.string.more_share_crash_log),
                subtitle = stringResource(R.string.more_share_crash_log_subtitle),
                color = CatSubtext,
                external = false,
            ) {
                val intent = CrashReporter.shareLatestCrashIntent(context)
                if (intent != null) {
                    context.startActivity(
                        Intent.createChooser(intent, null).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                } else {
                    Toast.makeText(context, R.string.more_no_crash_logs, Toast.LENGTH_SHORT).show()
                }
            }
        }

        MoreSection(stringResource(R.string.more_section_support)) {
            QuickLink(
                Icons.Default.Code,
                stringResource(R.string.more_github_repo),
                stringResource(R.string.more_github_repo_subtitle),
                CatSubtext,
            ) {
                launchExternalLink(context, "https://github.com/SysAdminDoc/CallShield")
            }
            GradientDivider()
            QuickLink(
                Icons.Default.BugReport,
                stringResource(R.string.more_report_bug),
                stringResource(R.string.more_report_bug_subtitle),
                CatSubtext,
            ) {
                launchExternalLink(context, "https://github.com/SysAdminDoc/CallShield/issues/new")
            }
            GradientDivider()
            QuickLink(
                Icons.Default.Star,
                stringResource(R.string.more_star_github),
                stringResource(R.string.more_star_github_subtitle),
                CatSubtext,
            ) {
                launchExternalLink(context, "https://github.com/SysAdminDoc/CallShield")
            }
            GradientDivider()
            QuickLink(
                Icons.Default.Flag,
                stringResource(R.string.more_report_spam_number),
                stringResource(R.string.more_report_spam_number_subtitle),
                CatSubtext,
            ) {
                launchExternalLink(
                    context,
                    "https://github.com/SysAdminDoc/CallShield/issues/new?template=spam_report.md",
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    color = CatText,
                )
                Text(
                    "$appVersion · ${stringResource(R.string.more_license)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = CatSubtext,
                )
            }
            StatusPill(stringResource(R.string.more_trust_on_device), CatGreen)
        }
    }
}

@Composable
private fun MoreSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title)
        Spacer(Modifier.height(6.dp))
        content()
        Spacer(Modifier.height(8.dp))
        GradientDivider()
    }
}

@Composable
private fun RowScope.MoreMetric(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = CatText)
        Text(label, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
    }
}

@Composable
private fun MoreMetricDivider() {
    Box(Modifier.width(1.dp).height(44.dp).background(DividerColor))
}

@Composable
fun MoreNavCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 62.dp),
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp),
    ) {
        PremiumIconTile(icon = icon, color = color, size = 36.dp, iconSize = 21.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = CatText)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = CatSubtext,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = stringResource(R.string.cd_chevron_right),
            tint = CatOverlay,
        )
    }
}

@Suppress("LongParameterList")
@Composable
fun QuickLink(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    subtitle: String,
    color: androidx.compose.ui.graphics.Color,
    external: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 5.dp),
    ) {
        PremiumIconTile(icon = icon, color = color, size = 36.dp, iconSize = 19.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(label, color = CatText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                color = CatSubtext,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            if (external) Icons.AutoMirrored.Filled.OpenInNew else Icons.Default.ChevronRight,
            contentDescription = if (external) stringResource(R.string.cd_open_external) else stringResource(R.string.cd_chevron_right),
            tint = CatOverlay,
            modifier = Modifier.size(16.dp),
        )
    }
}

private fun launchExternalLink(
    context: Context,
    url: String,
) {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
}
