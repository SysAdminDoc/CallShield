@file:Suppress("TooManyFunctions", "ktlint:standard:function-naming")

package com.sysadmindoc.callshield.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PhoneCallback
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.ui.theme.*
import com.sysadmindoc.callshield.util.startActivitySafely
import java.text.NumberFormat

/**
 * Setup progress and the grants CallShield needs. It sits on Advanced, and on
 * Basic too while anything required is missing.
 */
@Composable
internal fun PermissionsAccessCard(
    setupReadyCount: Int,
    setupTotal: Int,
    corePermissionsGranted: Boolean,
    screenerReadyForCurrentMode: Boolean,
    blockCalls: Boolean,
    overlayGranted: Boolean,
    notificationsGranted: Boolean,
    onRunSetupAgain: () -> Unit,
    onGrantCore: () -> Unit,
    onEnableScreening: () -> Unit,
    onEnableNotifications: () -> Unit,
) {
    val context = LocalContext.current
    val numberFormatter = remember { NumberFormat.getIntegerInstance() }
    Column(modifier = Modifier.fillMaxWidth()) {
        val setupSummary =
            if (setupReadyCount == setupTotal) {
                stringResource(R.string.settings_setup_ready_summary)
            } else {
                stringResource(R.string.settings_setup_attention_summary)
            }
        val setupColor = if (setupReadyCount == setupTotal) CatGreen else CatYellow
        SectionHeader(stringResource(R.string.settings_permissions_access), setupColor)
        Spacer(Modifier.height(12.dp))
        Text(
            setupSummary,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = CatText,
        )
        Text(
            stringResource(
                R.string.settings_setup_progress,
                numberFormatter.format(setupReadyCount),
                numberFormatter.format(setupTotal),
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = CatSubtext,
        )
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { setupReadyCount / setupTotal.toFloat() },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = setupColor,
            trackColor = CatMuted.copy(alpha = 0.32f),
            strokeCap = StrokeCap.Butt,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Spacer(Modifier.height(12.dp))
        SettingsLinkRow(
            title = stringResource(R.string.settings_run_setup_again),
            value = stringResource(R.string.settings_run_setup_again_detail),
            icon = Icons.Default.Tune,
            stackValue = true,
            onClick = onRunSetupAgain,
        )
        Spacer(Modifier.height(12.dp))
        // The side-by-side summary is only labels, so anything missing gets
        // the rows with Grant and Enable buttons instead, at any text size.
        if (LocalDensity.current.fontScale < 1.5f && setupReadyCount == setupTotal) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccessSnapshotMetric(
                    title = stringResource(R.string.settings_access_calls_messages),
                    icon = Icons.Default.Security,
                    ready = corePermissionsGranted,
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.width(1.dp).height(54.dp).background(CatMuted))
                AccessSnapshotMetric(
                    title = stringResource(R.string.settings_access_call_screening),
                    icon = Icons.AutoMirrored.Filled.PhoneCallback,
                    ready = screenerReadyForCurrentMode,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            PermissionAccessRow(
                title = stringResource(R.string.settings_access_calls_messages),
                icon = Icons.Default.Security,
                ready = corePermissionsGranted,
                readyLabel = stringResource(R.string.settings_access_ready),
                actionLabel = stringResource(R.string.settings_access_grant),
                onAction = onGrantCore,
            )
            GradientDivider()
            PermissionAccessRow(
                title = stringResource(R.string.settings_access_call_screening),
                icon = Icons.AutoMirrored.Filled.PhoneCallback,
                ready = screenerReadyForCurrentMode,
                readyLabel =
                    stringResource(
                        if (blockCalls) R.string.settings_access_ready else R.string.settings_access_optional,
                    ),
                actionLabel = stringResource(R.string.settings_access_enable),
                onAction = onEnableScreening,
            )
        }
        Spacer(Modifier.height(10.dp))
        GradientDivider()
        if (overlayGranted && notificationsGranted) {
            SettingsLinkRow(
                title = stringResource(R.string.settings_access_optional_title),
                value = stringResource(R.string.settings_access_optional_ready),
                icon = Icons.Default.Layers,
                stackValue = true,
                onClick = {
                    context.startActivitySafely(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                    )
                },
            )
        } else {
            if (!overlayGranted) {
                PermissionAccessRow(
                    title = stringResource(R.string.settings_access_caller_id),
                    icon = Icons.Default.Layers,
                    ready = false,
                    readyLabel = stringResource(R.string.settings_access_ready),
                    actionLabel = stringResource(R.string.settings_access_enable),
                    onAction = {
                        context.startActivitySafely(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                            onFailure = {
                                context.startActivitySafely(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            },
                        )
                    },
                )
            }
            if (!notificationsGranted) {
                if (!overlayGranted) GradientDivider()
                PermissionAccessRow(
                    title = stringResource(R.string.settings_notifications),
                    icon = Icons.Default.Notifications,
                    ready = false,
                    readyLabel = stringResource(R.string.settings_access_ready),
                    actionLabel = stringResource(R.string.settings_access_enable),
                    onAction = onEnableNotifications,
                )
            }
        }
        GradientDivider(modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
internal fun AccessSnapshotMetric(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    ready: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (ready) CatGreen else CatYellow,
            modifier = Modifier.size(30.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = CatText,
                maxLines = 2,
            )
            Text(
                stringResource(if (ready) R.string.settings_access_ready else R.string.settings_access_required),
                style = MaterialTheme.typography.bodySmall,
                color = if (ready) CatGreen else CatYellow,
            )
        }
    }
}

@Suppress("LongParameterList")
@Composable
internal fun PermissionAccessRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    ready: Boolean,
    readyLabel: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PremiumIconTile(
            icon = icon,
            color = if (ready) CatGreen else CatSubtext,
            size = 36.dp,
            iconSize = 19.dp,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = CatText,
        )
        if (ready) {
            val badgeColor =
                if (readyLabel == stringResource(R.string.settings_access_optional)) {
                    CatOverlay
                } else {
                    CatGreen
                }
            StatusPill(readyLabel, badgeColor)
        } else {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) {
                Text(actionLabel, color = CatGreen, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
