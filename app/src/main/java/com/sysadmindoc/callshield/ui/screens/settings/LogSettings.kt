@file:Suppress("TooManyFunctions", "ktlint:standard:function-naming")

package com.sysadmindoc.callshield.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.*

/** How long the log keeps entries, and exporting it. */
@Composable
internal fun LogSettings(viewModel: MainViewModel) {
    val context = LocalContext.current
    val autoCleanup by viewModel.autoCleanupEnabled.collectAsStateWithLifecycle()
    val cleanupDays by viewModel.cleanupDays.collectAsStateWithLifecycle()
    var showRawSmsExportDialog by rememberSaveable { mutableStateOf(false) }
    SettingsCard(stringResource(R.string.settings_log_cleanup)) {
        SettingsToggle(stringResource(R.string.settings_auto_cleanup), stringResource(R.string.settings_auto_cleanup_desc), Icons.Default.AutoDelete, autoCleanup) { viewModel.setAutoCleanup(it) }
        if (autoCleanup) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_keep_for), style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                listOf(7, 14, 30, 90).forEach { days ->
                    FilterChip(
                        selected = cleanupDays == days,
                        onClick = { viewModel.setCleanupDays(days) },
                        label = { Text(stringResource(R.string.settings_days, days)) },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, if (cleanupDays == days) CatGreen.copy(alpha = 0.3f) else CatMuted.copy(alpha = 0.3f)),
                        // The tint marks the selection; green text on it fell to 4.33:1 in Light.
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CatGreen.copy(alpha = 0.2f), selectedLabelColor = CatText),
                    )
                }
            }
        }
    }

    SettingsCard(stringResource(R.string.settings_export)) {
        PremiumActionButton(
            label = stringResource(R.string.settings_export_csv),
            icon = Icons.Default.FileDownload,
            color = CatBlue,
            onClick = {
                hapticTick(context)
                viewModel.exportLog()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.settings_export_csv_desc), style = MaterialTheme.typography.labelSmall, color = CatSubtext)
        Spacer(Modifier.height(8.dp))
        PremiumActionButton(
            label = stringResource(R.string.settings_export_redress_csv),
            icon = Icons.AutoMirrored.Filled.Assignment,
            color = CatGreen,
            onClick = {
                hapticTick(context)
                viewModel.exportRedressLog()
            },
            modifier = Modifier.fillMaxWidth(),
            outlined = true,
        )
        Text(
            stringResource(R.string.settings_export_redress_csv_desc),
            style = MaterialTheme.typography.labelSmall,
            color = CatSubtext,
        )
        Spacer(Modifier.height(8.dp))
        PremiumActionButton(
            label = stringResource(R.string.settings_export_raw_sms_csv),
            icon = Icons.Default.Warning,
            color = CatPeach,
            onClick = {
                hapticTick(context)
                showRawSmsExportDialog = true
            },
            modifier = Modifier.fillMaxWidth(),
            outlined = true,
        )
        Text(
            stringResource(R.string.settings_export_raw_sms_csv_desc),
            style = MaterialTheme.typography.labelSmall,
            color = CatSubtext,
        )
    }

    if (showRawSmsExportDialog) {
        AlertDialog(
            onDismissRequest = { showRawSmsExportDialog = false },
            containerColor = SurfaceBright,
            icon = {
                Icon(
                    Icons.Default.Warning,
                    null,
                    tint = CatPeach,
                    modifier = Modifier.size(32.dp),
                )
            },
            title = { Text(stringResource(R.string.settings_export_raw_sms_confirm_title)) },
            text = { Text(stringResource(R.string.settings_export_raw_sms_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRawSmsExportDialog = false
                        viewModel.exportLog(includeRawSmsBodies = true)
                    },
                ) {
                    Text(stringResource(R.string.settings_export_raw_sms_confirm_action), color = CatPeach)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRawSmsExportDialog = false }) {
                    Text(
                        stringResource(R.string.settings_export_raw_sms_cancel),
                        color = CatSubtext,
                    )
                }
            },
        )
    }
}
