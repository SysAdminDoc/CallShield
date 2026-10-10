@file:Suppress("TooManyFunctions", "ktlint:standard:function-naming")

package com.sysadmindoc.callshield.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.MessageCapabilityState
import com.sysadmindoc.callshield.data.MessageCapabilityStatus
import com.sysadmindoc.callshield.ui.theme.*

@Composable
internal fun MessageCapabilityStatusRow(
    title: String,
    status: MessageCapabilityStatus,
) {
    val detailRes =
        when (status.state) {
            MessageCapabilityState.NOT_OBSERVED -> {
                R.string.settings_capability_not_observed
            }

            MessageCapabilityState.FULL_CONTENT -> {
                if (status.smsOrderingAdvisory) {
                    R.string.settings_capability_full_sms_advisory
                } else {
                    R.string.settings_capability_full
                }
            }

            MessageCapabilityState.SENDER_ONLY -> {
                R.string.settings_capability_sender_only
            }

            MessageCapabilityState.BODY_REDACTED -> {
                R.string.settings_capability_redacted
            }

            MessageCapabilityState.DELAYED -> {
                R.string.settings_capability_delayed
            }

            MessageCapabilityState.UNSUPPORTED -> {
                R.string.settings_capability_unsupported
            }
        }
    val tint =
        when {
            status.isDegraded -> CatPeach
            status.state == MessageCapabilityState.FULL_CONTENT -> CatGreen
            else -> CatSubtext
        }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PremiumIconTile(
            icon = if (status.isDegraded) Icons.Default.Warning else Icons.Default.Info,
            color = tint,
            size = 30.dp,
            iconSize = 16.dp,
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(detailRes),
                style = MaterialTheme.typography.labelSmall,
                color = tint,
            )
        }
    }
}

@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun SettingsNumberStepper(
    label: String,
    valueText: String,
    value: Int,
    minValue: Int,
    maxValue: Int,
    step: Int = 1,
    onValueChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = CatSubtext)
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = CatText)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(
                onClick = { onValueChange((value - step).coerceAtLeast(minValue)) },
                enabled = value > minValue,
            ) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = stringResource(R.string.settings_decrease_value),
                    tint = if (value > minValue) CatText else CatOverlay,
                )
            }
            IconButton(
                onClick = { onValueChange((value + step).coerceAtMost(maxValue)) },
                enabled = value < maxValue,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.settings_increase_value),
                    tint = if (value < maxValue) CatText else CatOverlay,
                )
            }
        }
    }
}

@Composable
fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    PremiumCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            SectionHeader(title)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun SettingsLinkRow(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    // Neutral like the toggles' icons; an accent tint is for rows whose state it names.
    tintColor: androidx.compose.ui.graphics.Color = CatSubtext,
    modifier: Modifier = Modifier,
    stackValue: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PremiumIconTile(icon = icon, color = tintColor, size = 34.dp, iconSize = 18.dp)
        Spacer(Modifier.width(10.dp))
        if (stackValue || LocalDensity.current.fontScale >= 1.5f) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(value, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
            }
        } else {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = CatOverlay,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
internal fun SettingsViewTab(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.testTag(tag).selectable(selected = selected, role = Role.Tab, onClick = onClick),
        shape = RoundedCornerShape(ShapeLg),
        color = if (selected) CatGreen.copy(alpha = 0.2f) else androidx.compose.ui.graphics.Color.Transparent,
        border = BorderStroke(1.dp, if (selected) CatGreen else CatMuted),
    ) {
        Box(
            modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = CatText,
            )
        }
    }
}

@Composable
fun SettingsToggle(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    tintColor: androidx.compose.ui.graphics.Color = CatSubtext,
    toggleTag: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    // Row-level toggleable + onCheckedChange = null on the Switch: the whole
    // row is tappable and TalkBack reads title, subtitle, and switch state as
    // ONE node instead of inert text plus an unlabeled switch. Same pattern
    // as ContactGroupPickerSheet / BackupProtectionControls.
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = {
                        hapticTick(context)
                        onCheckedChange(it)
                    },
                ).let { if (toggleTag != null) it.testTag(toggleTag) else it }
                .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PremiumIconTile(icon = icon, color = tintColor, size = 34.dp, iconSize = 18.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) {
                // No line cap: these descriptions carry safety and privacy caveats,
                // and any cap cut some of them off on 360-384dp phones.
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = CatSubtext,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors =
                SwitchDefaults.colors(
                    checkedTrackColor = CatGreen,
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                ),
        )
    }
}
