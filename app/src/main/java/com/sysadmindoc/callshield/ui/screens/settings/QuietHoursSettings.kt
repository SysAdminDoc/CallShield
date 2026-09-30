@file:Suppress("TooManyFunctions", "ktlint:standard:function-naming")

package com.sysadmindoc.callshield.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.ui.DurationTtsText
import com.sysadmindoc.callshield.ui.theme.*

internal const val HOURS_PER_DAY = 24
internal const val SECONDS_PER_HOUR = 3_600

@Composable
internal fun QuietHoursSettings(
    enabled: Boolean,
    startHour: Int,
    endHour: Int,
    onEnabledChange: (Boolean) -> Unit,
    onStartChange: (Int) -> Unit,
    onEndChange: (Int) -> Unit,
) {
    SettingsCard(stringResource(R.string.settings_quiet_hours)) {
        SettingsToggle(
            stringResource(R.string.settings_quiet_hours_toggle),
            stringResource(R.string.settings_quiet_hours_desc),
            Icons.Default.Bedtime,
            enabled,
            toggleTag = SETTINGS_QUIET_HOURS_TOGGLE_TAG,
            onCheckedChange = onEnabledChange,
        )
        if (enabled) {
            val durationHours =
                if (startHour == endHour) {
                    HOURS_PER_DAY
                } else {
                    (endHour - startHour + HOURS_PER_DAY) % HOURS_PER_DAY
                }
            val durationText = pluralStringResource(R.plurals.duration_hours, durationHours, durationHours)
            val quietPeriodText = stringResource(R.string.settings_quiet_period_duration, durationText)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_time_start), style = MaterialTheme.typography.labelMedium, color = CatSubtext)
                    HourPicker(startHour, onSelect = onStartChange)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_time_end), style = MaterialTheme.typography.labelMedium, color = CatSubtext)
                    HourPicker(endHour, onSelect = onEndChange)
                }
            }
            Spacer(Modifier.height(8.dp))
            DurationTtsText(
                text = quietPeriodText,
                durationText = durationText,
                durationSeconds = durationHours * SECONDS_PER_HOUR,
                style = MaterialTheme.typography.bodySmall,
                color = CatSubtext,
            )
            if (startHour == endHour) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_quiet_hours_all_day_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = CatYellow,
                )
            }
        }
    }
}

@Composable
fun HourPicker(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val use24Hour =
        android.text.format.DateFormat
            .is24HourFormat(LocalContext.current)
    val label = formatHourLabel(selected, use24Hour)

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = SurfaceBright),
        ) {
            Text(label, color = CatText)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (h in 0..23) {
                val l = formatHourLabel(h, use24Hour)
                DropdownMenuItem(text = { Text(l) }, onClick = {
                    onSelect(h)
                    expanded = false
                })
            }
        }
    }
}

internal fun formatHourLabel(
    hour: Int,
    use24Hour: Boolean = false,
    locale: java.util.Locale = java.util.Locale.getDefault(),
): String =
    java.time.LocalTime
        .of(hour, 0)
        .format(
            java.time.format.DateTimeFormatter
                .ofPattern(if (use24Hour) "HH:mm" else "h a", locale),
        )
