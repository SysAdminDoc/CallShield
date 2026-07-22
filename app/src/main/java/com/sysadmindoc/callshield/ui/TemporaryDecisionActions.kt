@file:Suppress("FunctionNaming", "LongParameterList", "MagicNumber", "MatchingDeclarationName")

package com.sysadmindoc.callshield.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.ui.theme.PremiumCompactButton

data class TemporaryDecisionDuration(
    val label: String,
    val durationMillis: Long,
)

@Composable
fun rememberTemporaryDecisionDurations(): List<TemporaryDecisionDuration> {
    val fifteenMinutes = stringResource(R.string.temporary_decision_15_minutes)
    val oneHour = stringResource(R.string.temporary_decision_1_hour)
    val twentyFourHours = stringResource(R.string.temporary_decision_24_hours)
    return remember(fifteenMinutes, oneHour, twentyFourHours) {
        listOf(
            TemporaryDecisionDuration(fifteenMinutes, 15L * 60L * 1_000L),
            TemporaryDecisionDuration(oneHour, 60L * 60L * 1_000L),
            TemporaryDecisionDuration(twentyFourHours, 24L * 60L * 60L * 1_000L),
        )
    }
}

@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
fun TemporaryDecisionMenu(
    label: String,
    icon: ImageVector,
    color: Color,
    durations: List<TemporaryDecisionDuration>,
    onSelect: (TemporaryDecisionDuration) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val expandedStateDescription = stringResource(R.string.accessibility_state_expanded)
    val collapsedStateDescription = stringResource(R.string.accessibility_state_collapsed)
    Box(modifier = modifier) {
        PremiumCompactButton(
            label = label,
            icon = icon,
            color = color,
            onClick = { expanded = true },
            modifier =
                Modifier.expandableStateSemantics(
                    expanded = expanded,
                    expandedStateDescription = expandedStateDescription,
                    collapsedStateDescription = collapsedStateDescription,
                    onExpandedChange = { expanded = it },
                ),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            durations.forEach { duration ->
                DropdownMenuItem(
                    text = {
                        DurationTtsText(
                            text = duration.label,
                            durationSeconds = (duration.durationMillis / 1_000L).toInt(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(duration)
                    },
                )
            }
        }
    }
}
