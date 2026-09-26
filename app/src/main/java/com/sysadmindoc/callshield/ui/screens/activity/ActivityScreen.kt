package com.sysadmindoc.callshield.ui.screens.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.screens.main.BlockedLogScreen
import com.sysadmindoc.callshield.ui.screens.recent.RecentCallsScreen
import com.sysadmindoc.callshield.ui.theme.Black
import com.sysadmindoc.callshield.ui.theme.CatGreen
import com.sysadmindoc.callshield.ui.theme.CatOverlay
import com.sysadmindoc.callshield.ui.theme.CatSubtext
import com.sysadmindoc.callshield.ui.theme.ShapeXl

/**
 * One activity workspace keeps call history and blocked outcomes together.
 * [blockedLogRequestId] is a launch request (the blocked-summary notification)
 * that wants the Blocked tab. Each id switches the tab once, so a later
 * recomposition or recreation doesn't pull the user back to it.
 */
@Composable
fun ActivityScreen(
    viewModel: MainViewModel,
    blockedLogRequestId: Int? = null,
) {
    var selectedView by rememberSaveable { mutableIntStateOf(initialActivityView(blockedLogRequestId)) }
    var handledBlockedLogRequest by rememberSaveable { mutableStateOf(blockedLogRequestId) }
    LaunchedEffect(blockedLogRequestId) {
        if (blockedLogRequestId != null && blockedLogRequestId != handledBlockedLogRequest) {
            handledBlockedLogRequest = blockedLogRequestId
            selectedView = ACTIVITY_BLOCKED
        }
    }
    val stateHolder = rememberSaveableStateHolder()

    Column(modifier = Modifier.fillMaxSize().background(Black)) {
        Text(
            stringResource(R.string.activity_intro),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = CatSubtext,
        )
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            ActivityTab(
                selected = selectedView == ACTIVITY_RECENT,
                label = stringResource(R.string.activity_tab_recent),
                onClick = { selectedView = ACTIVITY_RECENT },
            )
            ActivityTab(
                selected = selectedView == ACTIVITY_BLOCKED,
                label = stringResource(R.string.activity_tab_blocked),
                onClick = { selectedView = ACTIVITY_BLOCKED },
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            stateHolder.SaveableStateProvider(selectedView) {
                if (selectedView == ACTIVITY_RECENT) {
                    RecentCallsScreen(viewModel)
                } else {
                    BlockedLogScreen(viewModel)
                }
            }
        }
    }
}

@Composable
internal fun RowScope.ActivityTab(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Tab(
        selected = selected,
        onClick = onClick,
        modifier =
            Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
                .height(48.dp)
                .background(if (selected) CatGreen else MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(ShapeXl))
                .border(1.dp, if (selected) CatGreen else CatOverlay.copy(alpha = 0.35f), RoundedCornerShape(ShapeXl)),
        selectedContentColor = Black,
        unselectedContentColor = CatSubtext,
        text = {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
        },
    )
}

/** The tab ActivityScreen opens on: Blocked when a launch asked for the log. */
internal fun initialActivityView(blockedLogRequestId: Int?): Int = if (blockedLogRequestId != null) ACTIVITY_BLOCKED else ACTIVITY_RECENT

internal const val ACTIVITY_RECENT = 0
internal const val ACTIVITY_BLOCKED = 1
