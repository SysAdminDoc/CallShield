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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sysadmindoc.callshield.BuildConfig
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.service.CrashReporter
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.screens.main.rememberNowTick
import com.sysadmindoc.callshield.ui.screens.settings.SettingsScreen
import com.sysadmindoc.callshield.ui.screens.stats.StatsScreen
import com.sysadmindoc.callshield.ui.theme.*
import java.text.NumberFormat

@Composable
fun MoreScreen(
    viewModel: MainViewModel,
    currentView: Int,
    onViewChange: (Int) -> Unit,
) {
    if (currentView != 0) BackHandler { onViewChange(0) }

    when (currentView) {
        1 -> {
            StatsScreen(viewModel)
        }

        2 -> {
            SettingsScreen(viewModel)
        }

        3 -> {
            ChangelogScreen()
        }

        4 -> {
            ProtectionTestScreen()
        }

        else -> {
            MoreHub(
                viewModel = viewModel,
                onStats = { onViewChange(1) },
                onSettings = { onViewChange(2) },
                onChangelog = { onViewChange(3) },
                onTest = { onViewChange(4) },
            )
        }
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
    val appTheme by viewModel.appTheme.collectAsStateWithLifecycle()
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
    // Re-emits every minute so "Synced Xm ago" advances while the screen is open,
    // matching the Dashboard's rolling clock instead of freezing at composition.
    val now = rememberNowTick()
    val syncLabel =
        when {
            lastSync <= 0L -> {
                stringResource(R.string.more_snapshot_never_synced)
            }

            else -> {
                val ago = now - lastSync
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
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                protectionLabel,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = CatText,
            )
            Text(syncLabel, style = MaterialTheme.typography.bodyMedium, color = CatSubtext)
            Spacer(Modifier.height(6.dp))
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
            Spacer(Modifier.height(6.dp))
            GradientDivider()
        }

        AppearanceRow(theme = appTheme, onClick = onSettings)
        GradientDivider()

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
                painter = painterResource(R.drawable.ic_callshield_brand_art),
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
        }
    }
}

@Composable
private fun AppearanceRow(
    theme: AppThemeMode,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
    ) {
        PremiumIconTile(icon = Icons.Default.Palette, color = CatSubtext, size = 34.dp, iconSize = 19.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(
                stringResource(R.string.more_appearance),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = CatText,
            )
            Text(
                stringResource(theme.labelResource()),
                style = MaterialTheme.typography.bodySmall,
                color = CatSubtext,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ThemeSwatch(Color(0xFF83908B), theme == AppThemeMode.System)
            ThemeSwatch(Color(0xFFE5EAE5), theme == AppThemeMode.Light)
            ThemeSwatch(Color(0xFF29333E), theme == AppThemeMode.Graphite)
            ThemeSwatch(Color.Black, theme == AppThemeMode.Amoled)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = CatOverlay)
    }
}

@Composable
private fun ThemeSwatch(
    color: Color,
    selected: Boolean,
) {
    Box(
        modifier =
            Modifier
                .size(if (selected) 20.dp else 14.dp)
                .background(if (selected) CatGreen.copy(alpha = 0.16f) else Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(12.dp).background(color, CircleShape))
    }
}

private fun AppThemeMode.labelResource(): Int =
    when (this) {
        AppThemeMode.System -> R.string.settings_theme_system
        AppThemeMode.Light -> R.string.settings_theme_light
        AppThemeMode.Graphite -> R.string.settings_theme_graphite
        AppThemeMode.Amoled -> R.string.settings_theme_amoled
    }

@Composable
private fun MoreSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title)
        Spacer(Modifier.height(3.dp))
        content()
        Spacer(Modifier.height(4.dp))
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
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
    ) {
        PremiumIconTile(icon = icon, color = color, size = 34.dp, iconSize = 19.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = CatText)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = CatSubtext,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            // Decorative: the row's title and subtitle already name the target,
            // and TalkBack merges descendants — a description here just appends
            // "View details" to every row.
            contentDescription = null,
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
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
    ) {
        PremiumIconTile(icon = icon, color = color, size = 34.dp, iconSize = 18.dp)
        Spacer(Modifier.width(10.dp))
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
            if (external) Icons.AutoMirrored.Filled.OpenInNew else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            // Only the external-link variant carries information; the plain
            // forward chevron is decorative next to the visible label.
            contentDescription = if (external) stringResource(R.string.cd_open_external) else null,
            tint = CatOverlay,
            modifier = Modifier.size(16.dp),
        )
    }
}

private fun launchExternalLink(
    context: Context,
    url: String,
) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    } catch (_: android.content.ActivityNotFoundException) {
        // Browserless devices (managed profiles, minimal AOSP builds) must not
        // crash on a Quick Link tap — the FTC report path already handles this.
        android.widget.Toast
            .makeText(context, context.getString(R.string.link_no_browser), android.widget.Toast.LENGTH_SHORT)
            .show()
    }
}
