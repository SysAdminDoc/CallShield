package com.sysadmindoc.callshield.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.CallCategory
import com.sysadmindoc.callshield.data.CategoryCallAction
import com.sysadmindoc.callshield.data.CategoryCallPolicy
import com.sysadmindoc.callshield.ui.theme.CatBlue
import com.sysadmindoc.callshield.ui.theme.CatGreen
import com.sysadmindoc.callshield.ui.theme.CatOverlay
import com.sysadmindoc.callshield.ui.theme.CatSubtext
import com.sysadmindoc.callshield.ui.theme.CatText
import com.sysadmindoc.callshield.ui.theme.PremiumActionButton
import com.sysadmindoc.callshield.ui.theme.SurfaceBright

internal const val CATEGORY_CALL_ACTION_TAG_PREFIX = "category_call_action:"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
fun CategoryCallActionsSheet(
    actions: Map<CallCategory, CategoryCallAction>,
    onActionChange: (CallCategory, CategoryCallAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceBright,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.settings_category_actions_title),
                style = MaterialTheme.typography.titleMedium,
                color = CatText,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.settings_category_actions_desc),
                style = MaterialTheme.typography.bodySmall,
                color = CatSubtext,
            )
            Text(
                stringResource(R.string.settings_category_actions_precedence),
                style = MaterialTheme.typography.labelSmall,
                color = CatBlue,
            )
            HorizontalDivider()
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(CategoryCallPolicy.configurableCategories, key = CallCategory::storageKey) { category ->
                CategoryActionRow(
                    category = category,
                    selectedAction = actions[category] ?: CategoryCallAction.INHERIT,
                    onActionChange = { action -> onActionChange(category, action) },
                )
            }
        }

        PremiumActionButton(
            label = stringResource(R.string.notification_screening_done),
            icon = Icons.Default.Check,
            color = CatGreen,
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
private fun CategoryActionRow(
    category: CallCategory,
    selectedAction: CategoryCallAction,
    onActionChange: (CategoryCallAction) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = CatOverlay.copy(alpha = 0.05f),
        border = BorderStroke(1.dp, CatOverlay.copy(alpha = 0.16f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(category.stringResId),
                style = MaterialTheme.typography.bodyMedium,
                color = CatText,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CategoryCallAction.entries.forEach { action ->
                    FilterChip(
                        selected = selectedAction == action,
                        onClick = { onActionChange(action) },
                        label = {
                            Text(
                                stringResource(action.labelResId),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        modifier =
                            Modifier
                                .weight(1f)
                                .testTag("$CATEGORY_CALL_ACTION_TAG_PREFIX${category.storageKey}:${action.storageKey}"),
                        colors =
                            FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CatGreen.copy(alpha = 0.18f),
                                selectedLabelColor = CatGreen,
                            ),
                    )
                }
            }
        }
    }
}
