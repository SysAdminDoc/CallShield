@file:Suppress("TooManyFunctions", "ktlint:standard:function-naming")

package com.sysadmindoc.callshield.ui.screens.settings

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.ExternalBlocklistParser
import com.sysadmindoc.callshield.data.model.ExternalBlocklistPreview
import com.sysadmindoc.callshield.data.model.ExternalBlocklistSubscription
import com.sysadmindoc.callshield.data.model.ListCatalogEntry
import com.sysadmindoc.callshield.data.remote.FeedMirror
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.StatusMessage
import com.sysadmindoc.callshield.ui.screens.main.relativeTimeText
import com.sysadmindoc.callshield.ui.theme.*
import java.util.Locale

/** Blocklists downloaded from a URL, with the typed URL and label owned by the screen. */
@Composable
internal fun ExternalBlocklistSection(
    viewModel: MainViewModel,
    url: String,
    label: String,
    onUrlChange: (String) -> Unit,
    onLabelChange: (String) -> Unit,
) {
    val context = LocalContext.current
    val externalBlocklists by viewModel.externalBlocklistSubscriptions.collectAsStateWithLifecycle()
    val externalBlocklistPreview by viewModel.externalBlocklistPreview.collectAsStateWithLifecycle()
    val externalBlocklistResult by viewModel.externalBlocklistResult.collectAsStateWithLifecycle()
    val externalBlocklistUndo by viewModel.externalBlocklistUndo.collectAsStateWithLifecycle()
    val listCatalog by viewModel.listCatalog.collectAsStateWithLifecycle()
    ExternalBlocklistSettings(
        url = url,
        label = label,
        subscriptions = externalBlocklists,
        catalog = listCatalog,
        onAddCatalogList = { entry ->
            hapticTick(context)
            viewModel.addCatalogList(entry)
        },
        preview = externalBlocklistPreview,
        result = externalBlocklistResult,
        onUrlChange = onUrlChange,
        onLabelChange = onLabelChange,
        onPreview = {
            hapticTick(context)
            viewModel.previewExternalBlocklist(url, label)
        },
        onApply = {
            hapticTick(context)
            viewModel.applyExternalBlocklist(url, label)
        },
        onApplyPreview = { preview ->
            hapticTick(context)
            viewModel.applyExternalBlocklist(preview.url, preview.label)
        },
        onToggle = { subscription, enabled ->
            hapticTick(context)
            viewModel.setExternalBlocklistEnabled(subscription, enabled)
        },
        onRemove = { subscription ->
            hapticTick(context)
            viewModel.removeExternalBlocklist(subscription)
        },
        canUndo = externalBlocklistUndo != null,
        onUndo = {
            hapticTick(context)
            viewModel.undoRemoveExternalBlocklist()
        },
        onClearResult = viewModel::clearExternalBlocklistResult,
    )
}

/** The feed mirror, with the typed URL owned by the screen. */
@Composable
internal fun FeedMirrorSection(
    viewModel: MainViewModel,
    input: String,
    onInputChange: (String) -> Unit,
) {
    val context = LocalContext.current
    val feedMirrorUrl by viewModel.feedMirrorUrl.collectAsStateWithLifecycle()
    val feedMirrorResult by viewModel.feedMirrorResult.collectAsStateWithLifecycle()
    FeedMirrorSettings(
        input = input,
        savedUrl = feedMirrorUrl,
        result = feedMirrorResult,
        onInputChange = onInputChange,
        onSave = {
            hapticTick(context)
            viewModel.saveFeedMirror(input)
        },
        onRemove = {
            hapticTick(context)
            viewModel.removeFeedMirror()
        },
        onClearResult = viewModel::clearFeedMirrorResult,
    )
}

@Composable
@Suppress("FunctionNaming", "LongMethod", "LongParameterList", "ktlint:standard:function-naming")
internal fun ExternalBlocklistSettings(
    url: String,
    label: String,
    subscriptions: List<ExternalBlocklistSubscription>,
    catalog: List<ListCatalogEntry>,
    onAddCatalogList: (ListCatalogEntry) -> Unit,
    preview: ExternalBlocklistPreview?,
    result: StatusMessage?,
    onUrlChange: (String) -> Unit,
    onLabelChange: (String) -> Unit,
    onPreview: () -> Unit,
    onApply: () -> Unit,
    onApplyPreview: (ExternalBlocklistPreview) -> Unit,
    onToggle: (ExternalBlocklistSubscription, Boolean) -> Unit,
    onRemove: (ExternalBlocklistSubscription) -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onClearResult: () -> Unit,
) {
    SettingsCard(stringResource(R.string.settings_external_blocklists)) {
        Text(
            stringResource(R.string.settings_external_blocklists_desc),
            style = MaterialTheme.typography.bodySmall,
            color = CatSubtext,
        )
        if (catalog.isNotEmpty()) {
            ListCatalogSection(catalog = catalog, subscriptions = subscriptions, onAdd = onAddCatalogList)
            Spacer(Modifier.height(12.dp))
            GradientDivider()
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.settings_external_blocklist_by_link),
                style = MaterialTheme.typography.titleSmall,
                color = CatText,
                modifier = Modifier.semantics { heading() },
            )
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            label = { Text(stringResource(R.string.settings_external_blocklist_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Default.Link, contentDescription = null, tint = CatBlue) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = RoundedCornerShape(8.dp),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CatBlue,
                    unfocusedBorderColor = CardBorderAccent,
                    focusedLabelColor = CatBlue,
                    cursorColor = CatBlue,
                ),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = label,
            onValueChange = onLabelChange,
            label = { Text(stringResource(R.string.settings_external_blocklist_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null, tint = CatMauve) },
            shape = RoundedCornerShape(8.dp),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CatMauve,
                    unfocusedBorderColor = CardBorderAccent,
                    focusedLabelColor = CatMauve,
                    cursorColor = CatMauve,
                ),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PremiumActionButton(
                label = stringResource(R.string.settings_external_blocklist_preview),
                icon = Icons.Default.Search,
                color = CatBlue,
                onClick = onPreview,
                enabled = url.isNotBlank(),
                modifier = Modifier.weight(1f),
                outlined = true,
            )
            PremiumActionButton(
                label = stringResource(R.string.settings_external_blocklist_apply),
                icon = Icons.Default.Save,
                color = CatGreen,
                onClick = onApply,
                enabled = url.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
        }
        preview?.let {
            // The panel commits the feed it PREVIEWED (url/label captured in
            // the preview object) — not whatever is currently typed in the
            // URL field, which the user may have edited since previewing.
            ExternalBlocklistPreviewPanel(preview = it, onApply = { onApplyPreview(it) })
        }
        result?.let { status ->
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    status.text,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (status.success) {
                            CatGreen
                        } else {
                            CatPeach
                        },
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (canUndo) {
                    TextButton(onClick = onUndo) {
                        Text(stringResource(R.string.settings_external_blocklist_undo), color = CatBlue)
                    }
                }
                IconButton(onClick = onClearResult) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close), tint = CatOverlay)
                }
            }
        }
        if (subscriptions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            GradientDivider()
            Spacer(Modifier.height(8.dp))
            subscriptions.forEachIndexed { index, subscription ->
                ExternalBlocklistSubscriptionRow(
                    subscription = subscription,
                    onToggle = { enabled -> onToggle(subscription, enabled) },
                    onRemove = { onRemove(subscription) },
                )
                if (index < subscriptions.lastIndex) {
                    GradientDivider()
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming", "LongMethod", "LongParameterList", "ktlint:standard:function-naming")
internal fun FeedMirrorSettings(
    input: String,
    savedUrl: String?,
    result: StatusMessage?,
    onInputChange: (String) -> Unit,
    onSave: () -> Unit,
    onRemove: () -> Unit,
    onClearResult: () -> Unit,
) {
    SettingsCard(stringResource(R.string.settings_feed_mirror)) {
        Text(
            stringResource(R.string.settings_feed_mirror_desc),
            style = MaterialTheme.typography.bodySmall,
            color = CatSubtext,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            label = { Text(stringResource(R.string.settings_feed_mirror_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Default.Link, contentDescription = null, tint = CatBlue) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = RoundedCornerShape(8.dp),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CatBlue,
                    unfocusedBorderColor = CardBorderAccent,
                    focusedLabelColor = CatBlue,
                    cursorColor = CatBlue,
                ),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PremiumActionButton(
                label = stringResource(R.string.settings_feed_mirror_jsdelivr),
                icon = Icons.Default.Public,
                color = CatBlue,
                onClick = { onInputChange(FeedMirror.JSDELIVR_BASE_URL) },
                modifier = Modifier.weight(1f),
                outlined = true,
            )
            PremiumActionButton(
                label = stringResource(R.string.settings_feed_mirror_save),
                icon = Icons.Default.Save,
                color = CatGreen,
                onClick = onSave,
                enabled = input.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
        }
        if (savedUrl != null) {
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.settings_feed_mirror_active, savedUrl),
                    style = MaterialTheme.typography.bodySmall,
                    color = CatSubtext,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRemove) {
                    Text(stringResource(R.string.settings_feed_mirror_remove), color = CatPeach)
                }
            }
        }
        result?.let { status ->
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    status.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.success) CatGreen else CatPeach,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                )
                IconButton(onClick = onClearResult) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close), tint = CatOverlay)
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
internal fun ExternalBlocklistPreviewPanel(
    preview: ExternalBlocklistPreview,
    onApply: () -> Unit,
) {
    Spacer(Modifier.height(10.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = CatBlue.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, CatBlue.copy(alpha = 0.20f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                PremiumIconTile(icon = Icons.Default.Link, color = CatBlue)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.settings_external_blocklist_preview_title, preview.label),
                        style = MaterialTheme.typography.titleSmall,
                        color = CatText,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(
                            R.string.settings_external_blocklist_preview_summary,
                            preview.format.uppercase(),
                            preview.numberCount,
                            preview.added,
                            preview.removed,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = CatSubtext,
                    )
                    Text(
                        stringResource(
                            R.string.settings_external_blocklist_preview_skips,
                            preview.skippedRows,
                            preview.blockedByOtherSources,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = CatSubtext,
                    )
                }
            }
            PremiumActionButton(
                label = stringResource(R.string.settings_external_blocklist_commit_preview),
                icon = Icons.Default.Save,
                color = CatGreen,
                onClick = onApply,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

internal const val LIST_CATALOG_ADD_TAG = "list_catalog_add"

/**
 * The recommended lists, each with its country and license on the row before
 * its Add button, since adding one downloads it from someone else's site.
 */
@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
internal fun ListCatalogSection(
    catalog: List<ListCatalogEntry>,
    subscriptions: List<ExternalBlocklistSubscription>,
    onAdd: (ListCatalogEntry) -> Unit,
) {
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.settings_list_catalog_title),
        style = MaterialTheme.typography.titleSmall,
        color = CatText,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.settings_list_catalog_desc),
        style = MaterialTheme.typography.bodySmall,
        color = CatSubtext,
    )
    val addedIds = subscriptions.map { it.id }.toSet()
    val addedCatalogIds = subscriptions.map { it.catalogId }.filter { it.isNotEmpty() }.toSet()
    catalog.forEach { entry ->
        ListCatalogRow(
            entry = entry,
            added = entry.id in addedCatalogIds || ExternalBlocklistParser.idForUrl(entry.url) in addedIds,
            onAdd = { onAdd(entry) },
        )
    }
}

@Composable
@Suppress("FunctionNaming", "LongMethod", "ktlint:standard:function-naming")
internal fun ListCatalogRow(
    entry: ListCatalogEntry,
    added: Boolean,
    onAdd: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val country = remember(entry.numberPlan.country) { countryName(entry.numberPlan.country) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PremiumIconTile(icon = Icons.Default.Public, color = CatBlue, size = 38.dp, iconSize = 20.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(entry.name, style = MaterialTheme.typography.bodyMedium, color = CatText)
            Text(country, style = MaterialTheme.typography.labelSmall, color = CatSubtext)
            Text(
                stringResource(R.string.settings_list_catalog_license_link, entry.license),
                style = MaterialTheme.typography.labelSmall,
                color = CatBlue,
                modifier =
                    Modifier.clickable(
                        onClickLabel = stringResource(R.string.settings_list_catalog_license_action, entry.license),
                        role = Role.Button,
                    ) { runCatching { uriHandler.openUri(entry.licenseUrl) } },
            )
        }
        if (added) {
            Text(
                stringResource(R.string.settings_list_catalog_added),
                style = MaterialTheme.typography.labelMedium,
                color = CatGreen,
            )
        } else {
            val addAction = stringResource(R.string.settings_list_catalog_add_action, entry.name)
            TextButton(
                onClick = onAdd,
                modifier =
                    Modifier.semantics {
                        contentDescription = addAction
                        testTag = LIST_CATALOG_ADD_TAG
                    },
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = CatBlue, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.settings_list_catalog_add), color = CatBlue)
            }
        }
    }
}

/** [iso] as a country name in the phone's language, or the code itself when it isn't a country. */
private fun countryName(iso: String): String =
    runCatching {
        Locale
            .Builder()
            .setRegion(iso)
            .build()
            .displayCountry
    }.getOrNull()
        ?.takeIf { it.isNotBlank() && it != iso }
        ?: iso

internal const val EXTERNAL_BLOCKLIST_SWITCH_TAG = "external_blocklist_switch"

@Composable
@Suppress("FunctionNaming", "LongMethod", "ktlint:standard:function-naming")
internal fun ExternalBlocklistSubscriptionRow(
    subscription: ExternalBlocklistSubscription,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val removeAction = stringResource(R.string.settings_external_blocklist_remove_action, subscription.label)
    // One TalkBack item per list that toggles it, with remove as an action on it.
    // By touch only the switch toggles: turning a list off deletes its numbers,
    // and a tap on its name shouldn't do that.
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    role = Role.Switch
                    toggleableState = ToggleableState(subscription.enabled)
                    onClick {
                        onToggle(!subscription.enabled)
                        true
                    }
                    customActions =
                        listOf(
                            CustomAccessibilityAction(removeAction) {
                                onRemove()
                                true
                            },
                        )
                }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PremiumIconTile(
            icon = if (subscription.enabled) Icons.Default.Link else Icons.Default.LinkOff,
            color = if (subscription.enabled) CatGreen else CatOverlay,
            size = 38.dp,
            iconSize = 20.dp,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(subscription.label, style = MaterialTheme.typography.bodyMedium, color = CatText)
            Text(subscription.host, style = MaterialTheme.typography.labelSmall, color = CatSubtext)
            Text(
                stringResource(
                    R.string.settings_external_blocklist_subscription_stats,
                    subscription.lastNumberCount,
                    subscription.lastAdded,
                    subscription.lastRemoved,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = if (subscription.enabled) CatGreen else CatOverlay,
            )
            if (subscription.enabled && subscription.lastSyncedAt > 0L) {
                Text(
                    stringResource(
                        R.string.settings_external_blocklist_last_synced,
                        relativeTimeText(subscription.lastSyncedAt),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = CatSubtext,
                )
            }
            if (subscription.lastError.isNotBlank()) {
                Text(subscription.lastError, style = MaterialTheme.typography.labelSmall, color = CatPeach)
            }
        }
        Switch(
            checked = subscription.enabled,
            onCheckedChange = onToggle,
            // The row carries the switch for accessibility services, with the list's name.
            modifier = Modifier.clearAndSetSemantics { testTag = EXTERNAL_BLOCKLIST_SWITCH_TAG },
            colors =
                SwitchDefaults.colors(
                    checkedTrackColor = CatGreen,
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                ),
        )
        // Hidden from accessibility services, which reach remove through the row's
        // custom action. Voice Access has no label for it and needs its grid.
        IconButton(onClick = onRemove, modifier = Modifier.clearAndSetSemantics { }) {
            Icon(Icons.Default.Delete, contentDescription = null, tint = CatPeach)
        }
    }
}
