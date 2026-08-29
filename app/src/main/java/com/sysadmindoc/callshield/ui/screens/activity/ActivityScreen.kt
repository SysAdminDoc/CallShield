package com.sysadmindoc.callshield.ui.screens.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.screens.main.BlockedLogScreen
import com.sysadmindoc.callshield.ui.screens.recent.RecentCallsScreen
import com.sysadmindoc.callshield.ui.theme.Black
import com.sysadmindoc.callshield.ui.theme.CatGreen
import com.sysadmindoc.callshield.ui.theme.CatSubtext

/** One activity workspace keeps call history and blocked outcomes together. */
@Composable
fun ActivityScreen(viewModel: MainViewModel) {
    var selectedView by rememberSaveable { mutableIntStateOf(ACTIVITY_RECENT) }
    val stateHolder = rememberSaveableStateHolder()

    Column(modifier = Modifier.fillMaxSize().background(Black)) {
        Row(modifier = Modifier.fillMaxWidth()) {
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
        modifier = Modifier.weight(1f).height(56.dp),
        selectedContentColor = CatGreen,
        unselectedContentColor = CatSubtext,
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                )
                Spacer(Modifier.height(5.dp))
                Box(
                    modifier =
                        Modifier
                            .width(72.dp)
                            .height(2.dp)
                            .background(if (selected) CatGreen else Color.Transparent),
                )
            }
        },
    )
}

private const val ACTIVITY_RECENT = 0
private const val ACTIVITY_BLOCKED = 1
