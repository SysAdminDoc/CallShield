package com.sysadmindoc.callshield.ui.screens.settings

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.sysadmindoc.callshield.ui.theme.CatBlue
import com.sysadmindoc.callshield.ui.theme.CatSubtext
import com.sysadmindoc.callshield.ui.theme.CatText
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
        sources = PushAlertRegistry.ALERT_SOURCE_PACKAGES.map { pkg ->
            val (label, installed) = try {
                val info = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(info).toString() to true
            } catch (_: PackageManager.NameNotFoundException) {
                prettyFallbackLabel(pkg) to false
            }
            PushAlertSource(pkg, label, installed)
        }.sortedWith(
            compareByDescending<PushAlertSource> { it.installed }
                .thenBy { it.label.lowercase() }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceBright,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .height(420.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(sources, key = { it.packageName }) { source ->
                SourceRow(
                    source = source,
                    allowed = source.packageName !in disabledPackages,
                    onToggle = { allowed -> onToggle(source.packageName, allowed) },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onReset) {
                Text(stringResource(R.string.push_alert_sources_reset), color = CatBlue)
            }
            Spacer(Modifier.widthIn(min = 8.dp))
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.push_alert_sources_done), color = CatText)
            }
        }
    }
}

@Composable
private fun SourceRow(
    source: PushAlertSource,
    allowed: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                source.label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (source.installed) CatText else CatSubtext,
            )
            Text(
                if (source.installed) source.packageName
                else "${source.packageName} · " + stringResource(R.string.push_alert_sources_not_installed),
                style = MaterialTheme.typography.labelSmall,
                color = CatSubtext,
            )
        }
        Switch(
            checked = allowed,
            onCheckedChange = onToggle,
            enabled = source.installed,
            colors = SwitchDefaults.colors(checkedTrackColor = CatBlue),
        )
    }
}

/**
 * Fallback label for packages the device doesn't have installed. Turns
 * "com.ubercab.driver" into "ubercab driver" — ugly but readable. Users
 * see this for apps they haven't installed yet but may install later.
 */
private fun prettyFallbackLabel(pkg: String): String =
    pkg.substringAfterLast('.')
        .replace('_', ' ')
        .replace('-', ' ')
