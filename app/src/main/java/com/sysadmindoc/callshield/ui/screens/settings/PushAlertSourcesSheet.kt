package com.sysadmindoc.callshield.ui.screens.settings

import android.content.pm.PackageManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.PushAlertRegistry
import com.sysadmindoc.callshield.data.pushAlertSourceDisplayName
import com.sysadmindoc.callshield.ui.theme.CatBlue
import com.sysadmindoc.callshield.ui.theme.CatGreen
import com.sysadmindoc.callshield.ui.theme.CatOverlay
import com.sysadmindoc.callshield.ui.theme.CatRed
import com.sysadmindoc.callshield.ui.theme.CatSubtext
import com.sysadmindoc.callshield.ui.theme.CatText
import com.sysadmindoc.callshield.ui.theme.CatYellow
import com.sysadmindoc.callshield.ui.theme.PremiumActionButton
import com.sysadmindoc.callshield.ui.theme.PremiumIconTile
import com.sysadmindoc.callshield.ui.theme.SkeletonListItem
import com.sysadmindoc.callshield.ui.theme.StatusPill
import com.sysadmindoc.callshield.ui.theme.SurfaceBright

/**
 * Pretty-formatted package row for display in the source picker. The
 * label is resolved via [PackageManager] once per sheet open — re-doing
 * the lookup on every recomposition would be wasteful.
 */
internal data class PushAlertSource(
    val packageName: String,
    val label: String,
    val installed: Boolean,
)

/**
 * Modal bottom sheet for the A3 allowlist editor.
 *
 * Shows every package in [PushAlertRegistry.ALERT_SOURCE_PACKAGES] with
 * a switch. Flipping a switch persists the opt-out through the
 * ViewModel/repo; the notification listener's background observer picks
 * up the change and updates the registry plus prunes any cached alerts
 * from that package.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PushAlertSourcesSheet(
    disabledPackages: Set<String>,
    onToggle: (pkg: String, allowed: Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Resolve labels once per sheet open. PackageManager lookups cost
    // ~1 ms apiece; 24 entries stays well under 50 ms even on cold caches.
    var sources by remember { mutableStateOf<List<PushAlertSource>>(emptyList()) }
    LaunchedEffect(Unit) {
        val pm = context.packageManager
        sources =
            PushAlertRegistry.ALERT_SOURCE_PACKAGES
                .map { pkg ->
                    val (label, installed) =
                        try {
                            val info = pm.getApplicationInfo(pkg, 0)
                            pm.getApplicationLabel(info).toString() to true
                        } catch (_: PackageManager.NameNotFoundException) {
                            pushAlertSourceDisplayName(pkg) to false
                        }
                    PushAlertSource(pkg, label, installed)
                }.sortedWith(
                    compareByDescending<PushAlertSource> { it.installed }
                        .thenBy { it.label.lowercase() },
                )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceBright,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val installedCount = sources.count { it.installed }
            val activeInstalledCount = sources.count { it.installed && it.packageName !in disabledPackages }
            Text(
                stringResource(R.string.push_alert_sources_title),
                style = MaterialTheme.typography.titleMedium,
                color = CatText,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.push_alert_sources_body),
                style = MaterialTheme.typography.bodySmall,
                color = CatSubtext,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill(
                    text =
                        if (sources.isEmpty()) {
                            stringResource(R.string.push_alert_sources_loading)
                        } else {
                            stringResource(
                                R.string.push_alert_sources_installed_count,
                                activeInstalledCount,
                                installedCount,
                            )
                        },
                    color =
                        when {
                            sources.isEmpty() || installedCount == 0 -> CatOverlay
                            activeInstalledCount == installedCount -> CatGreen
                            else -> CatYellow
                        },
                    modifier = Modifier.weight(1f),
                    horizontalPadding = 10.dp,
                    verticalPadding = 6.dp,
                    textStyle = MaterialTheme.typography.labelSmall,
                )
                StatusPill(
                    text =
                        stringResource(
                            R.string.push_alert_sources_catalog_count,
                            PushAlertRegistry.ALERT_SOURCE_PACKAGES.size,
                        ),
                    color = CatBlue,
                    modifier = Modifier.weight(1f),
                    horizontalPadding = 10.dp,
                    verticalPadding = 6.dp,
                    textStyle = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
        }

        LazyColumn(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .height(420.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (sources.isEmpty()) {
                items(4) {
                    SkeletonListItem(modifier = Modifier.fillMaxWidth())
                }
            } else {
                items(sources, key = { it.packageName }) { source ->
                    SourceRow(
                        source = source,
                        allowed = source.packageName !in disabledPackages,
                        onToggle = { allowed -> onToggle(source.packageName, allowed) },
                    )
                }
            }
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PremiumActionButton(
                label = stringResource(R.string.push_alert_sources_reset),
                icon = Icons.Default.Refresh,
                color = CatBlue,
                onClick = onReset,
                modifier = Modifier.weight(1f),
                enabled = sources.isNotEmpty(),
                outlined = true,
            )
            PremiumActionButton(
                label = stringResource(R.string.push_alert_sources_done),
                icon = Icons.Default.Check,
                color = CatGreen,
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SourceRow(
    source: PushAlertSource,
    allowed: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val stateColor =
        when {
            !source.installed -> CatOverlay
            allowed -> CatGreen
            else -> CatRed
        }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = stateColor.copy(alpha = if (source.installed) 0.06f else 0.03f),
        border = BorderStroke(1.dp, stateColor.copy(alpha = if (source.installed) 0.18f else 0.10f)),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PremiumIconTile(
                icon = if (source.installed && allowed) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                color = stateColor,
                size = 40.dp,
                iconSize = 19.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    source.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (source.installed) CatText else CatSubtext,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    source.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = CatSubtext,
                )
                Spacer(Modifier.height(6.dp))
                StatusPill(
                    text =
                        when {
                            !source.installed -> stringResource(R.string.push_alert_sources_not_installed)
                            allowed -> stringResource(R.string.push_alert_sources_active)
                            else -> stringResource(R.string.push_alert_sources_disabled)
                        },
                    color = stateColor,
                    horizontalPadding = 8.dp,
                    verticalPadding = 4.dp,
                    textStyle = MaterialTheme.typography.labelSmall,
                )
            }
            Switch(
                modifier = Modifier.size(width = 48.dp, height = 32.dp),
                checked = allowed,
                onCheckedChange = onToggle,
                enabled = source.installed,
                colors = SwitchDefaults.colors(checkedTrackColor = CatBlue),
            )
        }
    }
}
