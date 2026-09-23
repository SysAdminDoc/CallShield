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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.VideoCall
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.MeetingModeRegistry
import com.sysadmindoc.callshield.ui.theme.CatBlue
import com.sysadmindoc.callshield.ui.theme.CatGreen
import com.sysadmindoc.callshield.ui.theme.CatOverlay
import com.sysadmindoc.callshield.ui.theme.CatSubtext
import com.sysadmindoc.callshield.ui.theme.CatText
import com.sysadmindoc.callshield.ui.theme.PremiumActionButton
import com.sysadmindoc.callshield.ui.theme.PremiumIconTile
import com.sysadmindoc.callshield.ui.theme.SkeletonListItem
import com.sysadmindoc.callshield.ui.theme.StatusPill
import com.sysadmindoc.callshield.ui.theme.SurfaceBright

internal const val MEETING_APP_TAG_PREFIX = "meeting_app:"

internal data class MeetingAppUi(
    val packageName: String,
    val label: String,
    val installed: Boolean,
)

/**
 * Picker for the apps whose ongoing call or meeting notification turns
 * meeting mode on. Apps that aren't installed are listed but can't be picked.
 * A pill marks any app that has a call in progress right now, so a test call
 * shows whether detection works.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingAppsSheet(
    selectedPackages: Set<String>,
    onToggle: (packageName: String, selected: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val inCallNow = remember { MeetingModeRegistry.activePackages() }
    var apps by remember { mutableStateOf<List<MeetingAppUi>>(emptyList()) }
    LaunchedEffect(Unit) {
        val packageManager = context.packageManager
        apps =
            MeetingModeRegistry.MEETING_APPS
                .map { (packageName, fallbackName) ->
                    try {
                        val info = packageManager.getApplicationInfo(packageName, 0)
                        MeetingAppUi(packageName, packageManager.getApplicationLabel(info).toString(), installed = true)
                    } catch (_: PackageManager.NameNotFoundException) {
                        MeetingAppUi(packageName, fallbackName, installed = false)
                    }
                }.sortedWith(compareByDescending<MeetingAppUi> { it.installed }.thenBy { it.label.lowercase() })
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceBright,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.meeting_apps_title),
                style = MaterialTheme.typography.titleMedium,
                color = CatText,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.meeting_apps_body),
                style = MaterialTheme.typography.bodySmall,
                color = CatSubtext,
            )
            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp).heightIn(max = 420.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (apps.isEmpty()) {
                items(4) { SkeletonListItem(modifier = Modifier.fillMaxWidth()) }
            } else {
                items(apps, key = { it.packageName }) { app ->
                    MeetingAppRow(
                        app = app,
                        selected = app.packageName in selectedPackages,
                        inCallNow = app.packageName in inCallNow,
                        onToggle = { selected -> onToggle(app.packageName, selected) },
                    )
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            PremiumActionButton(
                label = stringResource(R.string.meeting_apps_done),
                icon = Icons.Default.Check,
                color = CatGreen,
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MeetingAppRow(
    app: MeetingAppUi,
    selected: Boolean,
    inCallNow: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val stateColor =
        when {
            !app.installed -> CatOverlay
            selected -> CatGreen
            else -> CatBlue
        }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = stateColor.copy(alpha = if (app.installed) 0.06f else 0.03f),
        border = BorderStroke(1.dp, stateColor.copy(alpha = if (app.installed) 0.18f else 0.10f)),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("$MEETING_APP_TAG_PREFIX${app.packageName}")
                    // One node for TalkBack: app name, status and switch state.
                    .toggleable(
                        value = selected,
                        enabled = app.installed,
                        role = Role.Switch,
                        onValueChange = onToggle,
                    ).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PremiumIconTile(icon = Icons.Default.VideoCall, color = stateColor, size = 40.dp, iconSize = 19.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (app.installed) CatText else CatSubtext,
                    fontWeight = FontWeight.SemiBold,
                )
                when {
                    !app.installed -> {
                        Spacer(Modifier.height(6.dp))
                        StatusPill(
                            text = stringResource(R.string.meeting_apps_not_installed),
                            color = stateColor,
                            horizontalPadding = 8.dp,
                            verticalPadding = 4.dp,
                            textStyle = MaterialTheme.typography.labelSmall,
                        )
                    }

                    inCallNow -> {
                        Spacer(Modifier.height(6.dp))
                        StatusPill(
                            text = stringResource(R.string.meeting_apps_in_call),
                            color = CatGreen,
                            horizontalPadding = 8.dp,
                            verticalPadding = 4.dp,
                            textStyle = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            Switch(
                checked = selected,
                onCheckedChange = null,
                enabled = app.installed,
                colors = SwitchDefaults.colors(checkedTrackColor = CatBlue),
            )
        }
    }
}
