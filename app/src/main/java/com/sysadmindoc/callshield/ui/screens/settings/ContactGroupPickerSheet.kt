package com.sysadmindoc.callshield.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.ContactGroup
import com.sysadmindoc.callshield.ui.theme.CatGreen
import com.sysadmindoc.callshield.ui.theme.CatOverlay
import com.sysadmindoc.callshield.ui.theme.CatPeach
import com.sysadmindoc.callshield.ui.theme.CatSubtext
import com.sysadmindoc.callshield.ui.theme.CatText
import com.sysadmindoc.callshield.ui.theme.PremiumActionButton
import com.sysadmindoc.callshield.ui.theme.SurfaceBright

internal const val CONTACT_SCOPE_ALL_TAG = "contact_scope_all"
internal const val CONTACT_SCOPE_GROUP_TAG_PREFIX = "contact_scope_group:"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming", "LongMethod", "LongParameterList", "ktlint:standard:function-naming")
fun ContactGroupPickerSheet(
    groups: List<ContactGroup>,
    selectedKeys: Set<String>,
    loading: Boolean,
    permissionGranted: Boolean,
    onSelectionChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val knownKeys = groups.asSequence().map(ContactGroup::key).toSet()
    val unavailableCount = selectedKeys.count { it !in knownKeys }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceBright,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                stringResource(R.string.settings_contact_groups_title),
                style = MaterialTheme.typography.titleMedium,
                color = CatText,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.settings_contact_groups_desc),
                style = MaterialTheme.typography.bodySmall,
                color = CatSubtext,
            )
            HorizontalDivider()
        }

        LazyColumn(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "all") {
                ContactScopeRow(
                    title = stringResource(R.string.settings_all_contacts),
                    subtitle = stringResource(R.string.settings_all_contacts_desc),
                    selected = selectedKeys.isEmpty(),
                    radio = true,
                    modifier = Modifier.testTag(CONTACT_SCOPE_ALL_TAG),
                    onClick = { onSelectionChange(emptySet()) },
                )
            }

            when {
                !permissionGranted -> {
                    item(key = "permission") {
                        ContactGroupMessage(stringResource(R.string.settings_contact_groups_permission))
                    }
                }

                loading -> {
                    item(key = "loading") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = CatGreen)
                        }
                    }
                }

                groups.isEmpty() -> {
                    item(key = "empty") {
                        ContactGroupMessage(stringResource(R.string.settings_contact_groups_empty))
                    }
                }

                else -> {
                    items(groups, key = ContactGroup::key) { group ->
                        val selected = group.key in selectedKeys
                        ContactScopeRow(
                            title = group.title,
                            subtitle =
                                buildString {
                                    append(
                                        pluralStringResource(
                                            R.plurals.settings_contact_group_members,
                                            group.memberCount,
                                            group.memberCount,
                                        ),
                                    )
                                    group.accountName?.let { append(" · ").append(it) }
                                },
                            selected = selected,
                            modifier = Modifier.testTag("$CONTACT_SCOPE_GROUP_TAG_PREFIX${group.key}"),
                            onClick = {
                                val updated = selectedKeys.toMutableSet()
                                if (selected) {
                                    // Removing the last group is fine: an empty
                                    // selection means "All contacts", which the
                                    // radio row above then shows as selected.
                                    // Swallowing the tap left a dead checkbox.
                                    updated.remove(group.key)
                                } else {
                                    updated.add(group.key)
                                }
                                onSelectionChange(updated)
                            },
                        )
                    }
                }
            }

            // Only meaningful when we could actually enumerate groups: with
            // Contacts denied every selected key counts as "unavailable" and
            // the warning would misattribute a permission problem to data loss.
            if (!loading && permissionGranted && unavailableCount > 0) {
                item(key = "unavailable") {
                    Text(
                        pluralStringResource(
                            R.plurals.settings_contact_groups_unavailable,
                            unavailableCount,
                            unavailableCount,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = CatPeach,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
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
@Suppress("FunctionNaming", "LongParameterList", "ktlint:standard:function-naming")
private fun ContactScopeRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    radio: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .then(
                    if (radio) {
                        Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                    } else {
                        Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
                    },
                ),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) CatGreen.copy(alpha = 0.10f) else CatOverlay.copy(alpha = 0.04f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Groups, contentDescription = null, tint = if (selected) CatGreen else CatSubtext)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CatText,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = CatSubtext,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (radio) {
                RadioButton(selected = selected, onClick = null)
            } else {
                Checkbox(checked = selected, onCheckedChange = null)
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
private fun ContactGroupMessage(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = CatSubtext,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
    )
}
