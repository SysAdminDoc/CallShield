package com.sysadmindoc.callshield.ui.screens.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.ExistingBlockRules
import com.sysadmindoc.callshield.data.HashWildcardMatcher
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.data.RuleConflict
import com.sysadmindoc.callshield.data.RuleConflictAnalyzer
import com.sysadmindoc.callshield.data.RuleConflictRule
import com.sysadmindoc.callshield.data.RuleConflictWinner
import com.sysadmindoc.callshield.data.TimeSchedule
import com.sysadmindoc.callshield.data.model.HashWildcardRule
import com.sysadmindoc.callshield.data.model.SmsKeywordRule
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.WhitelistEntry
import com.sysadmindoc.callshield.data.model.WildcardRule
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.CatBlue
import com.sysadmindoc.callshield.ui.theme.CatGreen
import com.sysadmindoc.callshield.ui.theme.CatMauve
import com.sysadmindoc.callshield.ui.theme.CatOverlay
import com.sysadmindoc.callshield.ui.theme.CatPeach
import com.sysadmindoc.callshield.ui.theme.CatRed
import com.sysadmindoc.callshield.ui.theme.CatSubtext
import com.sysadmindoc.callshield.ui.theme.CatText
import com.sysadmindoc.callshield.ui.theme.CatYellow
import com.sysadmindoc.callshield.ui.theme.GradientDivider
import com.sysadmindoc.callshield.ui.theme.PremiumActionButton
import com.sysadmindoc.callshield.ui.theme.PremiumCard
import com.sysadmindoc.callshield.ui.theme.PremiumCompactButton
import com.sysadmindoc.callshield.ui.theme.PremiumIconTile
import com.sysadmindoc.callshield.ui.theme.StatusPill
import com.sysadmindoc.callshield.ui.theme.SurfaceBright
import com.sysadmindoc.callshield.ui.theme.hapticConfirm
import com.sysadmindoc.callshield.ui.theme.hapticTick
import com.sysadmindoc.callshield.util.hasMinAsciiDigits
import com.sysadmindoc.callshield.util.normalizePhoneNumberInput
import com.sysadmindoc.callshield.util.sanitizePhoneNumberInput
import kotlinx.coroutines.launch

private const val BLOCKLIST_TAB_BLOCKED = 0
private const val BLOCKLIST_TAB_WILDCARDS = 1
private const val BLOCKLIST_TAB_RANGES = 2 // A5: length-locked # patterns
private const val BLOCKLIST_TAB_KEYWORDS = 3
private const val BLOCKLIST_TAB_WHITELIST = 4
private const val BLOCKLIST_TAB_DATABASE = 5
internal const val BLOCKLIST_SWIPE_ITEM_TAG = "blocklist_swipe_item"
internal const val BLOCKLIST_REGEX_CHECKBOX_TAG = "blocklist_regex_checkbox"

private data class BlocklistWorkspaceModel(
    val title: String,
    val subtitle: String,
    val count: Int,
    val accentColor: Color,
    val icon: ImageVector,
    val addActionLabel: String? = null,
    val primaryUtilityLabel: String? = null,
    val onPrimaryUtility: (() -> Unit)? = null,
    val secondaryUtilityLabel: String? = null,
    val onSecondaryUtility: (() -> Unit)? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlocklistScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val userBlocked by viewModel.userBlockedNumbers.collectAsStateWithLifecycle()
    // Header count only — cheap Room COUNT, always observed. The full spam table
    // is collected lazily inside the Database tab (see DatabaseTabContent) so
    // opening Blocklist never materializes a ~100k-row list.
    val spamCount by viewModel.spamCount.collectAsStateWithLifecycle()
    val wildcardRules by viewModel.wildcardRules.collectAsStateWithLifecycle()
    val hashWildcardRules by viewModel.hashWildcardRules.collectAsStateWithLifecycle()
    val whitelistEntries by viewModel.whitelistEntries.collectAsStateWithLifecycle()
    val keywordRules by viewModel.keywordRules.collectAsStateWithLifecycle()
    val importResult by viewModel.importResult.collectAsStateWithLifecycle()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var showWildcardDialog by rememberSaveable { mutableStateOf(false) }
    var showRangeDialog by rememberSaveable { mutableStateOf(false) }
    var showWhitelistDialog by rememberSaveable { mutableStateOf(false) }
    var showKeywordDialog by rememberSaveable { mutableStateOf(false) }
    var tabIndex by rememberSaveable { mutableStateOf(BLOCKLIST_TAB_BLOCKED) }
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.importBlocklist(it) }
        }

    LaunchedEffect(importResult) {
        val message = importResult ?: return@LaunchedEffect
        snackbarHost.currentSnackbarData?.dismiss()
        snackbarHost.showSnackbar(message, duration = SnackbarDuration.Short)
        viewModel.clearImportResult()
    }

    val numberRemovedMessage = stringResource(R.string.blocklist_number_removed)
    val ruleRemovedMessage = stringResource(R.string.blocklist_rule_removed)
    val undoLabel = stringResource(R.string.blocklist_undo)

    /**
     * Delete-with-undo for rules. Deleting a wildcard, range, keyword, or
     * trusted entry was instant and unrecoverable, with the delete button
     * sitting right beside each row's enable switch — one misclick silently
     * dropped a rule (or re-exposed the user to blocking, for an emergency
     * contact) with no feedback at all.
     */
    fun deleteWithUndo(
        delete: () -> Unit,
        restore: () -> Unit,
    ) {
        delete()
        hapticTick(context)
        scope.launch {
            snackbarHost.currentSnackbarData?.dismiss()
            val result =
                snackbarHost.showSnackbar(
                    message = ruleRemovedMessage,
                    actionLabel = undoLabel,
                    duration = SnackbarDuration.Short,
                )
            if (result == SnackbarResult.ActionPerformed) restore()
        }
    }

    fun removeBlockedNumberWithUndo(number: SpamNumber) {
        viewModel.unblockNumber(number)
        hapticTick(context)
        scope.launch {
            snackbarHost.currentSnackbarData?.dismiss()
            val result =
                snackbarHost.showSnackbar(
                    message = numberRemovedMessage,
                    actionLabel = undoLabel,
                    duration = SnackbarDuration.Short,
                )
            if (result == SnackbarResult.ActionPerformed) {
                // Re-insert the exact row so a temporary block keeps its expiry
                // instead of coming back as a permanent block.
                viewModel.restoreBlockedNumber(number)
            }
        }
    }

    val workspace =
        when (tabIndex) {
            BLOCKLIST_TAB_BLOCKED -> {
                BlocklistWorkspaceModel(
                    title = stringResource(R.string.blocklist_overview_blocked_title),
                    subtitle = stringResource(R.string.blocklist_overview_blocked_subtitle),
                    count = userBlocked.size,
                    accentColor = CatRed,
                    icon = Icons.Default.Block,
                    addActionLabel = stringResource(R.string.blocklist_action_add_number),
                    primaryUtilityLabel = stringResource(R.string.blocklist_action_import),
                    onPrimaryUtility = {
                        // Match the backup restore picker: many providers report
                        // .json downloads as application/octet-stream, which made
                        // those files unselectable here but selectable there.
                        importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    },
                    secondaryUtilityLabel =
                        userBlocked.takeIf { it.isNotEmpty() }?.let {
                            stringResource(R.string.blocklist_action_export)
                        },
                    onSecondaryUtility =
                        userBlocked.takeIf { it.isNotEmpty() }?.let {
                            { viewModel.exportBlocklist() }
                        },
                )
            }

            BLOCKLIST_TAB_WILDCARDS -> {
                BlocklistWorkspaceModel(
                    title = stringResource(R.string.blocklist_overview_wildcards_title),
                    subtitle = stringResource(R.string.blocklist_overview_wildcards_subtitle),
                    count = wildcardRules.size,
                    accentColor = CatYellow,
                    icon = Icons.Default.FilterAlt,
                    addActionLabel = stringResource(R.string.blocklist_action_add_wildcard),
                )
            }

            BLOCKLIST_TAB_RANGES -> {
                BlocklistWorkspaceModel(
                    title = stringResource(R.string.blocklist_overview_ranges_title),
                    subtitle = stringResource(R.string.blocklist_overview_ranges_subtitle),
                    count = hashWildcardRules.size,
                    accentColor = CatPeach,
                    icon = Icons.Default.Tune,
                    addActionLabel = stringResource(R.string.blocklist_action_add_range),
                )
            }

            BLOCKLIST_TAB_KEYWORDS -> {
                BlocklistWorkspaceModel(
                    title = stringResource(R.string.blocklist_overview_keywords_title),
                    subtitle = stringResource(R.string.blocklist_overview_keywords_subtitle),
                    count = keywordRules.size,
                    accentColor = CatMauve,
                    icon = Icons.Default.TextFields,
                    addActionLabel = stringResource(R.string.blocklist_action_add_keyword),
                )
            }

            BLOCKLIST_TAB_WHITELIST -> {
                BlocklistWorkspaceModel(
                    title = stringResource(R.string.blocklist_overview_whitelist_title),
                    subtitle = stringResource(R.string.blocklist_overview_whitelist_subtitle),
                    count = whitelistEntries.size,
                    accentColor = CatGreen,
                    icon = Icons.Default.CheckCircle,
                    addActionLabel = stringResource(R.string.blocklist_action_add_trusted),
                )
            }

            else -> {
                BlocklistWorkspaceModel(
                    title = stringResource(R.string.blocklist_overview_database_title),
                    subtitle = stringResource(R.string.blocklist_overview_database_subtitle),
                    count = spamCount,
                    accentColor = CatBlue,
                    icon = Icons.Default.Storage,
                )
            }
        }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            BlocklistOverviewCard(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                workspace = workspace,
            )

            PrimaryScrollableTabRow(
                selectedTabIndex = tabIndex,
                containerColor = com.sysadmindoc.callshield.ui.theme.Black,
                contentColor = CatText,
                edgePadding = 8.dp,
                divider = {},
                // Selection is already communicated by label colour. A custom
                // full-width indicator rendered as a solid accent slab on
                // current Material 3, obscuring the content below the tabs.
                indicator = {},
            ) {
                Tab(
                    selected = tabIndex == BLOCKLIST_TAB_BLOCKED,
                    onClick = { tabIndex = BLOCKLIST_TAB_BLOCKED },
                    selectedContentColor = CatGreen,
                    unselectedContentColor = CatSubtext,
                    text = {
                        RuleTabLabel(
                            stringResource(R.string.blocklist_tab_blocked_short),
                            tabIndex == BLOCKLIST_TAB_BLOCKED,
                        )
                    },
                )
                Tab(
                    selected = tabIndex == BLOCKLIST_TAB_WILDCARDS,
                    onClick = { tabIndex = BLOCKLIST_TAB_WILDCARDS },
                    selectedContentColor = CatGreen,
                    unselectedContentColor = CatSubtext,
                    text = {
                        RuleTabLabel(
                            stringResource(R.string.blocklist_tab_wildcards_short),
                            tabIndex == BLOCKLIST_TAB_WILDCARDS,
                        )
                    },
                )
                Tab(
                    selected = tabIndex == BLOCKLIST_TAB_RANGES,
                    onClick = { tabIndex = BLOCKLIST_TAB_RANGES },
                    selectedContentColor = CatGreen,
                    unselectedContentColor = CatSubtext,
                    text = {
                        RuleTabLabel(
                            stringResource(R.string.blocklist_tab_ranges_short),
                            tabIndex == BLOCKLIST_TAB_RANGES,
                        )
                    },
                )
                Tab(
                    selected = tabIndex == BLOCKLIST_TAB_KEYWORDS,
                    onClick = { tabIndex = BLOCKLIST_TAB_KEYWORDS },
                    selectedContentColor = CatGreen,
                    unselectedContentColor = CatSubtext,
                    text = {
                        RuleTabLabel(
                            stringResource(R.string.blocklist_tab_keywords_short),
                            tabIndex == BLOCKLIST_TAB_KEYWORDS,
                        )
                    },
                )
                Tab(
                    selected = tabIndex == BLOCKLIST_TAB_WHITELIST,
                    onClick = { tabIndex = BLOCKLIST_TAB_WHITELIST },
                    selectedContentColor = CatGreen,
                    unselectedContentColor = CatSubtext,
                    text = {
                        RuleTabLabel(
                            stringResource(R.string.blocklist_tab_whitelist_short),
                            tabIndex == BLOCKLIST_TAB_WHITELIST,
                        )
                    },
                )
                Tab(
                    selected = tabIndex == BLOCKLIST_TAB_DATABASE,
                    onClick = { tabIndex = BLOCKLIST_TAB_DATABASE },
                    selectedContentColor = CatGreen,
                    unselectedContentColor = CatSubtext,
                    text = {
                        RuleTabLabel(
                            stringResource(R.string.blocklist_tab_database_short),
                            tabIndex == BLOCKLIST_TAB_DATABASE,
                        )
                    },
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                when (tabIndex) {
                    BLOCKLIST_TAB_BLOCKED -> {
                        if (userBlocked.isEmpty()) {
                            EmptyStateCard(
                                title = stringResource(R.string.blocklist_empty_blocked),
                                subtitle = stringResource(R.string.blocklist_empty_blocked_sub),
                                icon = Icons.Default.Block,
                                accentColor = CatRed,
                            )
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 104.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(userBlocked, key = { it.id }) { number ->
                                    SwipeToRemoveBlocklistItem(
                                        number = number,
                                        onRemove = { removeBlockedNumberWithUndo(number) },
                                    )
                                }
                            }
                        }
                    }

                    BLOCKLIST_TAB_WILDCARDS -> {
                        if (wildcardRules.isEmpty()) {
                            EmptyStateCard(
                                title = stringResource(R.string.blocklist_empty_wildcards),
                                subtitle = stringResource(R.string.blocklist_empty_wildcards_sub),
                                icon = Icons.Default.FilterAlt,
                                accentColor = CatYellow,
                            )
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 104.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(wildcardRules, key = { it.id }) { rule ->
                                    WildcardRuleItem(
                                        rule = rule,
                                        onToggle = { viewModel.toggleWildcardRule(rule.id, it) },
                                        onDelete = {
                                            deleteWithUndo(
                                                delete = { viewModel.deleteWildcardRule(rule) },
                                                restore = { viewModel.restoreWildcardRule(rule) },
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }

                    BLOCKLIST_TAB_RANGES -> {
                        if (hashWildcardRules.isEmpty()) {
                            EmptyStateCard(
                                title = stringResource(R.string.blocklist_empty_ranges),
                                subtitle = stringResource(R.string.blocklist_empty_ranges_sub),
                                icon = Icons.Default.Tune,
                                accentColor = CatPeach,
                            )
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 104.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(hashWildcardRules, key = { it.id }) { rule ->
                                    HashWildcardRuleItem(
                                        rule = rule,
                                        onToggle = { viewModel.toggleHashWildcardRule(rule.id, it) },
                                        onDelete = {
                                            deleteWithUndo(
                                                delete = { viewModel.deleteHashWildcardRule(rule) },
                                                restore = { viewModel.restoreHashWildcardRule(rule) },
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }

                    BLOCKLIST_TAB_KEYWORDS -> {
                        if (keywordRules.isEmpty()) {
                            EmptyStateCard(
                                title = stringResource(R.string.blocklist_empty_keywords),
                                subtitle = stringResource(R.string.blocklist_empty_keywords_sub),
                                icon = Icons.Default.TextFields,
                                accentColor = CatMauve,
                            )
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 104.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(keywordRules, key = { it.id }) { rule ->
                                    KeywordRuleItem(
                                        rule = rule,
                                        onToggle = { viewModel.toggleKeywordRule(rule.id, it) },
                                        onDelete = {
                                            deleteWithUndo(
                                                delete = { viewModel.deleteKeywordRule(rule) },
                                                restore = { viewModel.restoreKeywordRule(rule) },
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }

                    BLOCKLIST_TAB_WHITELIST -> {
                        if (whitelistEntries.isEmpty()) {
                            EmptyStateCard(
                                title = stringResource(R.string.blocklist_empty_whitelist),
                                subtitle = stringResource(R.string.blocklist_empty_whitelist_sub),
                                icon = Icons.Default.CheckCircle,
                                accentColor = CatGreen,
                            )
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 104.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(whitelistEntries, key = { it.id }) { entry ->
                                    WhitelistItem(
                                        entry = entry,
                                        onRemove = {
                                            deleteWithUndo(
                                                delete = { viewModel.removeFromWhitelist(entry) },
                                                restore = { viewModel.restoreWhitelistEntry(entry) },
                                            )
                                        },
                                        onToggleEmergency = { viewModel.toggleWhitelistEmergency(entry.id, !entry.isEmergency) },
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        // Only composed when the Database tab is active, so the
                        // full-table query is subscribed only while it's shown.
                        DatabaseTabContent(viewModel)
                    }
                }

                workspace.addActionLabel?.let { addLabel ->
                    ExtendedFloatingActionButton(
                        onClick = {
                            when (tabIndex) {
                                BLOCKLIST_TAB_BLOCKED -> showAddDialog = true
                                BLOCKLIST_TAB_WILDCARDS -> showWildcardDialog = true
                                BLOCKLIST_TAB_RANGES -> showRangeDialog = true
                                BLOCKLIST_TAB_KEYWORDS -> showKeywordDialog = true
                                BLOCKLIST_TAB_WHITELIST -> showWhitelistDialog = true
                            }
                        },
                        modifier =
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp),
                        containerColor = CatGreen,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = RoundedCornerShape(12.dp),
                        icon = { Icon(Icons.Default.Add, stringResource(R.string.cd_add)) },
                        text = { Text(addLabel, fontWeight = FontWeight.Bold) },
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHost,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }

    val numberBlockedMessage = stringResource(R.string.blocklist_number_blocked)
    val ruleAddedMessage = stringResource(R.string.blocklist_rule_added)
    val emergencyContactsAddedMessage = stringResource(R.string.emergency_contacts_added)
    val numberWhitelistedMessage = stringResource(R.string.blocklist_number_whitelisted)
    val keywordRuleAddedMessage = stringResource(R.string.blocklist_keyword_rule_added)

    if (showAddDialog) {
        AddNumberDialog(
            existingWhitelist = whitelistEntries,
            onDismiss = { showAddDialog = false },
        ) { number, description ->
            viewModel.blockNumber(number, description = description)
            showAddDialog = false
            hapticConfirm(context)
            scope.launch {
                snackbarHost.showSnackbar(
                    numberBlockedMessage,
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }
    if (showWildcardDialog) {
        AddWildcardDialog(
            existingWhitelist = whitelistEntries,
            onDismiss = { showWildcardDialog = false },
        ) { pattern, isRegex, description, schedule ->
            viewModel.addWildcardRule(pattern, isRegex, description, schedule)
            showWildcardDialog = false
            hapticTick(context)
            scope.launch {
                snackbarHost.showSnackbar(
                    ruleAddedMessage,
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }
    if (showRangeDialog) {
        AddHashWildcardDialog(
            existing = hashWildcardRules,
            existingWhitelist = whitelistEntries,
            onDismiss = { showRangeDialog = false },
        ) { pattern, description, schedule ->
            viewModel.addHashWildcardRule(pattern, description, schedule)
            showRangeDialog = false
            hapticTick(context)
            scope.launch {
                snackbarHost.showSnackbar(
                    ruleAddedMessage,
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }
    if (showWhitelistDialog) {
        AddWhitelistDialog(
            existingBlocks = userBlocked,
            existingWildcardRules = wildcardRules,
            existingHashWildcardRules = hashWildcardRules,
            onDismiss = { showWhitelistDialog = false },
        ) { number, description, emergency ->
            viewModel.addToWhitelist(number, description, isEmergency = emergency)
            showWhitelistDialog = false
            hapticTick(context)
            val message =
                if (emergency) {
                    emergencyContactsAddedMessage
                } else {
                    numberWhitelistedMessage
                }
            scope.launch { snackbarHost.showSnackbar(message, duration = SnackbarDuration.Short) }
        }
    }
    if (showKeywordDialog) {
        AddKeywordDialog(onDismiss = { showKeywordDialog = false }) { keyword, caseSensitive, description, schedule ->
            viewModel.addKeywordRule(keyword, caseSensitive, description, schedule)
            showKeywordDialog = false
            hapticTick(context)
            scope.launch {
                snackbarHost.showSnackbar(
                    keywordRuleAddedMessage,
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }
}

@Composable
private fun BlocklistOverviewCard(
    workspace: BlocklistWorkspaceModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PremiumIconTile(
                icon = workspace.icon,
                color = workspace.accentColor,
                size = 38.dp,
                iconSize = 21.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(text = workspace.title, style = MaterialTheme.typography.titleMedium, color = CatText)
                Text(
                    text =
                        pluralStringResource(
                            R.plurals.blocklist_count_saved,
                            workspace.count,
                            workspace.count,
                        ),
                    style = MaterialTheme.typography.labelMedium,
                    color = workspace.accentColor,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                workspace.primaryUtilityLabel?.let { label ->
                    PremiumCompactButton(
                        label = label,
                        icon = Icons.Default.FileOpen,
                        color = workspace.accentColor,
                        onClick = { workspace.onPrimaryUtility?.invoke() },
                    )
                }
                workspace.secondaryUtilityLabel?.let { label ->
                    PremiumCompactButton(
                        label = label,
                        icon = Icons.Default.Share,
                        color = workspace.accentColor,
                        onClick = { workspace.onSecondaryUtility?.invoke() },
                    )
                }
            }
        }
        GradientDivider()
    }
}

@Composable
private fun RuleTabLabel(
    label: String,
    selected: Boolean,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
        Spacer(Modifier.height(7.dp))
        Box(
            modifier =
                Modifier
                    .width(36.dp)
                    .height(2.dp)
                    .background(if (selected) CatGreen else Color.Transparent),
        )
    }
}

@Composable
private fun EmptyStateCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = stringResource(R.string.cd_empty_list),
                tint = accentColor,
                modifier = Modifier.size(42.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = CatText,
                textAlign = TextAlign.Center,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = CatSubtext,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION")
@Composable
internal fun SwipeToRemoveBlocklistItem(
    number: SpamNumber,
    onRemove: () -> Unit,
) {
    var removalHandled by remember(number.id) { mutableStateOf(false) }
    val currentOnRemove by rememberUpdatedState(onRemove)

    fun removeOnce(): Boolean {
        if (removalHandled) return false
        removalHandled = true
        currentOnRemove()
        return true
    }

    val unblockActionLabel = stringResource(R.string.cd_unblock)

    val dismissState =
        rememberSwipeToDismissBoxState(
            confirmValueChange = { targetValue ->
                if (targetValue == SwipeToDismissBoxValue.EndToStart && !removalHandled) {
                    removeOnce()
                    // Keep the reusable saveable state settled so Undo can
                    // reinsert this same item id without immediately removing it.
                    false
                } else {
                    true
                }
            },
        )
    SwipeToDismissBox(
        state = dismissState,
        modifier =
            Modifier
                .testTag(BLOCKLIST_SWIPE_ITEM_TAG)
                .semantics {
                    // The visual swipe is a convenience, never the only path
                    // to the destructive action for switch-access users.
                    customActions =
                        listOf(
                            CustomAccessibilityAction(unblockActionLabel) {
                                removeOnce()
                            },
                        )
                },
        backgroundContent = {
            val active = dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) CatRed.copy(alpha = 0.28f) else SurfaceBright)
                        .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Default.Delete, null, tint = CatText)
            }
        },
    ) {
        BlocklistItem(number, onUnblock = onRemove)
    }
}

@Composable
fun BlocklistItem(
    number: SpamNumber,
    onUnblock: () -> Unit,
) {
    PremiumCard(cornerRadius = 12.dp, accentColor = CatRed) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Block, null, tint = CatRed, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(PhoneFormatter.formatIsolated(number.number), fontWeight = FontWeight.SemiBold, color = CatText)
                if (number.description.isNotEmpty()) {
                    Text(number.description, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                }
                StatusPill(
                    text = stringResource(R.string.blocklist_manual_badge),
                    color = CatRed,
                    horizontalPadding = 8.dp,
                    verticalPadding = 4.dp,
                    textStyle = MaterialTheme.typography.labelSmall,
                )
            }
            IconButton(onClick = onUnblock) {
                Icon(Icons.Default.RemoveCircleOutline, stringResource(R.string.cd_unblock), tint = CatOverlay)
            }
        }
    }
}

@Composable
fun WildcardRuleItem(
    rule: WildcardRule,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    PremiumCard(cornerRadius = 12.dp, accentColor = CatYellow) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (rule.isRegex) Icons.Default.Code else Icons.Default.FilterAlt,
                null,
                tint = CatYellow,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(rule.pattern, fontWeight = FontWeight.SemiBold, color = CatText)
                if (rule.description.isNotEmpty()) {
                    Text(rule.description, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                }
                StatusPill(
                    text = stringResource(if (rule.isRegex) R.string.blocklist_regex else R.string.blocklist_wildcard),
                    color = CatYellow,
                    horizontalPadding = 8.dp,
                    verticalPadding = 4.dp,
                    textStyle = MaterialTheme.typography.labelSmall,
                )
                SchedulePill(rule.schedule)
            }
            androidx.compose.material3.Switch(
                checked = rule.enabled,
                onCheckedChange = onToggle,
                // Name the switch so TalkBack announces the rule it toggles
                // instead of an anonymous "on/off, switch" (the row also has a
                // delete action, so it can't be a single toggleable node).
                modifier = Modifier.semantics { contentDescription = rule.pattern },
                colors =
                    androidx.compose.material3.SwitchDefaults.colors(
                        checkedTrackColor = CatGreen,
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    ),
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Close, stringResource(R.string.cd_delete_rule), tint = CatRed.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
fun KeywordRuleItem(
    rule: SmsKeywordRule,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    PremiumCard(cornerRadius = 12.dp, accentColor = CatMauve) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.TextFields, null, tint = CatMauve, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("\"${rule.keyword}\"", fontWeight = FontWeight.SemiBold, color = CatText)
                if (rule.description.isNotEmpty()) {
                    Text(rule.description, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                }
                StatusPill(
                    text = stringResource(if (rule.caseSensitive) R.string.blocklist_case_sensitive else R.string.blocklist_case_insensitive),
                    color = CatMauve,
                    horizontalPadding = 8.dp,
                    verticalPadding = 4.dp,
                    textStyle = MaterialTheme.typography.labelSmall,
                )
                SchedulePill(rule.schedule)
            }
            androidx.compose.material3.Switch(
                checked = rule.enabled,
                onCheckedChange = onToggle,
                // Name the switch so TalkBack announces the rule it toggles
                // instead of an anonymous "on/off, switch" (the row also has a
                // delete action, so it can't be a single toggleable node).
                modifier = Modifier.semantics { contentDescription = rule.keyword },
                colors =
                    androidx.compose.material3.SwitchDefaults.colors(
                        checkedTrackColor = CatGreen,
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    ),
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Close, stringResource(R.string.cd_delete_rule), tint = CatRed.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
fun WhitelistItem(
    entry: WhitelistEntry,
    onRemove: () -> Unit,
    onToggleEmergency: () -> Unit,
) {
    val accent = if (entry.isEmergency) CatRed else CatGreen
    val emergencyDescription =
        if (entry.isEmergency) {
            stringResource(R.string.emergency_contacts_unmark)
        } else {
            stringResource(R.string.emergency_contacts_mark_as)
        }
    PremiumCard(cornerRadius = 12.dp, accentColor = accent) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (entry.isEmergency) Icons.Default.PriorityHigh else Icons.Default.CheckCircle,
                null,
                tint = accent,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(PhoneFormatter.formatIsolated(entry.number), fontWeight = FontWeight.SemiBold, color = CatText)
                if (entry.description.isNotEmpty()) {
                    Text(entry.description, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                }
                if (entry.isEmergency) {
                    StatusPill(
                        text = stringResource(R.string.emergency_contacts_badge),
                        color = CatRed,
                        horizontalPadding = 8.dp,
                        verticalPadding = 4.dp,
                        textStyle = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            IconButton(
                onClick = onToggleEmergency,
                modifier = Modifier.semantics { contentDescription = emergencyDescription },
            ) {
                Icon(
                    if (entry.isEmergency) Icons.Default.Star else Icons.Default.StarBorder,
                    null,
                    tint = if (entry.isEmergency) CatRed else CatSubtext,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.RemoveCircleOutline, stringResource(R.string.cd_remove), tint = CatOverlay)
            }
        }
    }
}

@Composable
private fun DatabaseTabContent(viewModel: MainViewModel) {
    val allSpam by viewModel.allSpamNumbers.collectAsStateWithLifecycle()
    if (allSpam.isEmpty()) {
        EmptyStateCard(
            title = stringResource(R.string.blocklist_empty_database),
            subtitle = stringResource(R.string.blocklist_empty_database_sub),
            icon = Icons.Default.Storage,
            accentColor = CatBlue,
        )
    } else {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(allSpam, key = { it.id }) { number ->
                DatabaseItem(number)
            }
        }
    }
}

@Composable
fun DatabaseItem(number: SpamNumber) {
    val typeColor =
        when (number.type.lowercase()) {
            "robocall" -> CatRed
            "scam" -> com.sysadmindoc.callshield.ui.theme.CatPeach
            "telemarketer" -> CatYellow
            else -> CatBlue
        }
    PremiumCard(cornerRadius = 12.dp, accentColor = typeColor) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Storage, null, tint = typeColor, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(PhoneFormatter.formatIsolated(number.number), fontWeight = FontWeight.SemiBold, color = CatText)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(
                        text = number.type.replaceFirstChar { it.uppercase() },
                        color = typeColor,
                        horizontalPadding = 8.dp,
                        verticalPadding = 4.dp,
                        textStyle = MaterialTheme.typography.labelSmall,
                    )
                    StatusPill(
                        text =
                            pluralStringResource(
                                R.plurals.blocklist_reports,
                                number.reports,
                                number.reports,
                            ),
                        color = CatBlue,
                        horizontalPadding = 8.dp,
                        verticalPadding = 4.dp,
                        textStyle = MaterialTheme.typography.labelSmall,
                    )
                }
                if (number.description.isNotEmpty()) {
                    Text(number.description, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                }
            }
        }
    }
}

@Composable
fun AddNumberDialog(
    existingWhitelist: List<WhitelistEntry> = emptyList(),
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit,
) {
    var number by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    val normalizedNumber = remember(number) { normalizePhoneInput(number) }
    val canConfirm = hasMinAsciiDigits(normalizedNumber)
    val conflict =
        remember(normalizedNumber, existingWhitelist) {
            if (canConfirm) RuleConflictAnalyzer.forExactBlock(normalizedNumber, existingWhitelist) else null
        }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = com.sysadmindoc.callshield.ui.theme.SurfaceBright,
        title = { Text(stringResource(R.string.dialog_block_number)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = number,
                    onValueChange = { number = sanitizePhoneInput(it) },
                    label = { Text(stringResource(R.string.dialog_phone_number)) },
                    placeholder = { Text(stringResource(R.string.dialog_phone_placeholder)) },
                    singleLine = true,
                    supportingText =
                        if (canConfirm) {
                            { Text(PhoneFormatter.formatIsolated(normalizedNumber), color = CatSubtext) }
                        } else {
                            null
                        },
                    keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone,
                            imeAction = androidx.compose.ui.text.input.ImeAction.Next,
                        ),
                    colors =
                        androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CatGreen,
                            cursorColor = CatGreen,
                        ),
                )
                conflict?.let { RuleConflictWarning(it) }
                androidx.compose.material3.OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.dialog_description_optional)) },
                    singleLine = true,
                    keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                        ),
                    keyboardActions =
                        androidx.compose.foundation.text.KeyboardActions(
                            onDone = {
                                if (canConfirm) onAdd(normalizedNumber, description.trim())
                            },
                        ),
                    colors =
                        androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CatGreen,
                            cursorColor = CatGreen,
                        ),
                )
            }
        },
        confirmButton = {
            PremiumActionButton(
                label = stringResource(R.string.dialog_block),
                icon = Icons.Default.Block,
                color = CatRed,
                onClick = { onAdd(normalizedNumber, description.trim()) },
                enabled = canConfirm,
            )
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel), color = CatSubtext)
            }
        },
    )
}

@Composable
fun AddWildcardDialog(
    existingWhitelist: List<WhitelistEntry> = emptyList(),
    onDismiss: () -> Unit,
    onAdd: (String, Boolean, String, TimeSchedule) -> Unit,
) {
    var pattern by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var isRegex by rememberSaveable { mutableStateOf(false) }
    var regexErrorDetail by remember { mutableStateOf<String?>(null) }
    var scheduleState by rememberSaveable(stateSaver = ScheduleUiState.Saver) { mutableStateOf(ScheduleUiState()) }
    val trimmedPattern = pattern.trim()
    val conflict =
        remember(trimmedPattern, isRegex, existingWhitelist) {
            if (trimmedPattern.isBlank()) {
                null
            } else {
                RuleConflictAnalyzer.forWildcardBlock(trimmedPattern, isRegex, existingWhitelist)
            }
        }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = com.sysadmindoc.callshield.ui.theme.SurfaceBright,
        title = { Text(stringResource(R.string.dialog_add_wildcard_rule)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = pattern,
                    onValueChange = {
                        pattern = it
                        regexErrorDetail = null
                    },
                    label = { Text(stringResource(if (isRegex) R.string.dialog_regex_label else R.string.dialog_pattern_label)) },
                    placeholder = { Text(stringResource(if (isRegex) R.string.dialog_regex_placeholder else R.string.dialog_wildcard_placeholder)) },
                    singleLine = true,
                    isError = regexErrorDetail != null,
                    supportingText =
                        regexErrorDetail?.let { detail ->
                            { Text(stringResource(R.string.dialog_invalid_regex, detail), color = CatRed) }
                        },
                    colors =
                        androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CatYellow,
                            cursorColor = CatYellow,
                        ),
                )
                conflict?.let { RuleConflictWarning(it) }
                androidx.compose.material3.OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.dialog_description)) },
                    singleLine = true,
                    colors =
                        androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CatYellow,
                            cursorColor = CatYellow,
                        ),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = isRegex,
                        onCheckedChange = {
                            isRegex = it
                            regexErrorDetail = null
                        },
                        modifier = Modifier.testTag(BLOCKLIST_REGEX_CHECKBOX_TAG),
                        colors =
                            androidx.compose.material3.CheckboxDefaults
                                .colors(checkedColor = CatYellow),
                    )
                    Text(stringResource(R.string.dialog_use_regex), style = MaterialTheme.typography.bodySmall)
                }
                GradientDivider(color = CatYellow)
                ScheduleSection(scheduleState) { scheduleState = it }
            }
        },
        confirmButton = {
            PremiumActionButton(
                label = stringResource(R.string.dialog_add),
                icon = Icons.Default.Add,
                color = CatYellow,
                onClick = {
                    if (trimmedPattern.isNotBlank() && !scheduleState.needsDaySelection) {
                        if (isRegex) {
                            try {
                                Regex(trimmedPattern)
                                onAdd(trimmedPattern, true, description.trim(), scheduleState.toSchedule())
                            } catch (e: Exception) {
                                regexErrorDetail = e.message ?: ""
                            }
                        } else {
                            onAdd(trimmedPattern, false, description.trim(), scheduleState.toSchedule())
                        }
                    }
                },
                enabled = trimmedPattern.isNotBlank() && !scheduleState.needsDaySelection,
            )
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel), color = CatSubtext)
            }
        },
    )
}

@Composable
fun AddWhitelistDialog(
    existingBlocks: List<SpamNumber> = emptyList(),
    existingWildcardRules: List<WildcardRule> = emptyList(),
    existingHashWildcardRules: List<HashWildcardRule> = emptyList(),
    onDismiss: () -> Unit,
    onAdd: (String, String, Boolean) -> Unit,
) {
    var number by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var emergency by rememberSaveable { mutableStateOf(false) }
    val normalizedNumber = remember(number) { normalizePhoneInput(number) }
    val canConfirm = hasMinAsciiDigits(normalizedNumber)
    val conflict =
        remember(
            normalizedNumber,
            emergency,
            existingBlocks,
            existingWildcardRules,
            existingHashWildcardRules,
        ) {
            if (canConfirm) {
                RuleConflictAnalyzer.forWhitelist(
                    number = normalizedNumber,
                    emergency = emergency,
                    rules =
                        ExistingBlockRules(
                            exactBlocks = existingBlocks,
                            wildcardRules = existingWildcardRules,
                            hashWildcardRules = existingHashWildcardRules,
                        ),
                )
            } else {
                null
            }
        }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = com.sysadmindoc.callshield.ui.theme.SurfaceBright,
        title = { Text(stringResource(R.string.dialog_add_to_whitelist)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = number,
                    onValueChange = { number = sanitizePhoneInput(it) },
                    label = { Text(stringResource(R.string.dialog_phone_number)) },
                    singleLine = true,
                    supportingText =
                        if (canConfirm) {
                            { Text(PhoneFormatter.formatIsolated(normalizedNumber), color = CatSubtext) }
                        } else {
                            null
                        },
                    keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone,
                            imeAction = androidx.compose.ui.text.input.ImeAction.Next,
                        ),
                    colors =
                        androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CatGreen,
                            cursorColor = CatGreen,
                        ),
                )
                conflict?.let { RuleConflictWarning(it) }
                androidx.compose.material3.OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.dialog_description)) },
                    singleLine = true,
                    keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                        ),
                    keyboardActions =
                        androidx.compose.foundation.text.KeyboardActions(
                            onDone = {
                                if (canConfirm) onAdd(normalizedNumber, description.trim(), emergency)
                            },
                        ),
                    colors =
                        androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CatGreen,
                            cursorColor = CatGreen,
                        ),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = emergency,
                        onCheckedChange = { emergency = it },
                        colors =
                            androidx.compose.material3.CheckboxDefaults
                                .colors(checkedColor = CatRed),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.emergency_contacts_mark_as), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.emergency_contacts_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = CatSubtext,
                        )
                    }
                }
                Text(stringResource(R.string.dialog_whitelist_note), style = MaterialTheme.typography.labelSmall, color = CatSubtext)
            }
        },
        confirmButton = {
            PremiumActionButton(
                label =
                    if (emergency) {
                        stringResource(R.string.emergency_contacts_add)
                    } else {
                        stringResource(R.string.dialog_whitelist)
                    },
                icon = if (emergency) Icons.Default.PriorityHigh else Icons.Default.CheckCircle,
                color = if (emergency) CatRed else CatGreen,
                onClick = { onAdd(normalizedNumber, description.trim(), emergency) },
                enabled = canConfirm,
            )
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel), color = CatSubtext)
            }
        },
    )
}

@Composable
fun AddKeywordDialog(
    onDismiss: () -> Unit,
    onAdd: (String, Boolean, String, TimeSchedule) -> Unit,
) {
    var keyword by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var caseSensitive by rememberSaveable { mutableStateOf(false) }
    var scheduleState by rememberSaveable(stateSaver = ScheduleUiState.Saver) { mutableStateOf(ScheduleUiState()) }
    val trimmedKeyword = keyword.trim()

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = com.sysadmindoc.callshield.ui.theme.SurfaceBright,
        title = { Text(stringResource(R.string.dialog_block_sms_keyword)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    label = { Text(stringResource(R.string.dialog_keyword)) },
                    placeholder = { Text(stringResource(R.string.dialog_keyword_placeholder)) },
                    singleLine = true,
                    colors =
                        androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CatMauve,
                            cursorColor = CatMauve,
                        ),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.dialog_description)) },
                    singleLine = true,
                    colors =
                        androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CatMauve,
                            cursorColor = CatMauve,
                        ),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = caseSensitive,
                        onCheckedChange = { caseSensitive = it },
                        colors =
                            androidx.compose.material3.CheckboxDefaults
                                .colors(checkedColor = CatMauve),
                    )
                    Text(stringResource(R.string.blocklist_case_sensitive), style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.dialog_keyword_note), style = MaterialTheme.typography.labelSmall, color = CatSubtext)
                GradientDivider(color = CatMauve)
                ScheduleSection(scheduleState) { scheduleState = it }
            }
        },
        confirmButton = {
            PremiumActionButton(
                label = stringResource(R.string.dialog_add),
                icon = Icons.Default.TextFields,
                color = CatMauve,
                onClick = {
                    onAdd(trimmedKeyword, caseSensitive, description.trim(), scheduleState.toSchedule())
                },
                enabled = trimmedKeyword.isNotBlank() && !scheduleState.needsDaySelection,
            )
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel), color = CatSubtext)
            }
        },
    )
}

@Composable
private fun RuleConflictWarning(conflict: RuleConflict) {
    val winner =
        stringResource(
            when (conflict.winner) {
                RuleConflictWinner.EMERGENCY_ALLOW -> R.string.rule_conflict_emergency_allow
                RuleConflictWinner.WHITELIST -> R.string.rule_conflict_whitelist
            },
        )
    val overridden =
        stringResource(
            when (conflict.overriddenRule) {
                RuleConflictRule.EXACT_BLOCK -> R.string.rule_conflict_exact_block
                RuleConflictRule.WILDCARD_BLOCK -> R.string.rule_conflict_wildcard_block
                RuleConflictRule.RANGE_BLOCK -> R.string.rule_conflict_range_block
            },
        )
    Surface(
        color = CatYellow.copy(alpha = 0.12f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text =
                stringResource(
                    R.string.rule_conflict_warning,
                    winner,
                    overridden,
                    PhoneFormatter.formatIsolated(conflict.sampleNumber),
                ),
            style = MaterialTheme.typography.labelMedium,
            color = CatYellow,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

private fun sanitizePhoneInput(input: String): String = sanitizePhoneNumberInput(input)

private fun normalizePhoneInput(input: String): String = normalizePhoneNumberInput(input)

// ─────────────────────────────────────────────────────────────────────
// A5 — Hash wildcard ("range") rules UI
// ─────────────────────────────────────────────────────────────────────

/** Format a coverage count with thousand-separators (e.g. 10_000 → "10,000"). */
private fun formatCoverage(count: Long): String =
    java.text.NumberFormat
        .getIntegerInstance()
        .format(count)

@Composable
fun HashWildcardRuleItem(
    rule: HashWildcardRule,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val coverage = remember(rule.pattern) { HashWildcardMatcher.coveredNumberCount(rule.pattern) }
    PremiumCard(cornerRadius = 12.dp, accentColor = CatPeach) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Tune, null, tint = CatPeach, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    rule.pattern,
                    fontWeight = FontWeight.SemiBold,
                    color = CatText,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
                if (rule.description.isNotEmpty()) {
                    Text(
                        rule.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = CatSubtext,
                    )
                }
                StatusPill(
                    text = stringResource(R.string.hash_wildcard_item_covers, formatCoverage(coverage)),
                    color = CatPeach,
                    horizontalPadding = 8.dp,
                    verticalPadding = 4.dp,
                    textStyle = MaterialTheme.typography.labelSmall,
                )
                SchedulePill(rule.schedule)
            }
            androidx.compose.material3.Switch(
                checked = rule.enabled,
                onCheckedChange = onToggle,
                // Name the switch so TalkBack announces the rule it toggles —
                // a list of range rules is otherwise a row of identical
                // unnamed switches.
                modifier = Modifier.semantics { contentDescription = rule.pattern },
                colors =
                    androidx.compose.material3.SwitchDefaults.colors(
                        checkedTrackColor = CatGreen,
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    ),
            )
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Close,
                    stringResource(R.string.cd_delete_rule),
                    tint = CatRed.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/**
 * Add-range dialog. Validates:
 *   - Pattern contains at least one `#` (otherwise a simple user-blocklist
 *     entry is a better fit and the UI nudges the user toward that tab).
 *   - Coverage under 100,000,000 — beyond that the pattern is almost
 *     certainly too broad, so we refuse the add as a safety rail.
 *   - Overlap with any existing rule — shown as an inline warning
 *     ("Already covered by +33#######" / "Duplicate of existing rule")
 *     but only blocks saves on exact-duplicate (the narrower-covers-broader
 *     case is a user choice, not an error).
 */
@Composable
fun AddHashWildcardDialog(
    existing: List<HashWildcardRule>,
    existingWhitelist: List<WhitelistEntry> = emptyList(),
    onDismiss: () -> Unit,
    onAdd: (String, String, TimeSchedule) -> Unit,
) {
    var pattern by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    // A7 schedule state — kept local to the dialog; committed to the rule
    // only when the user presses "Add range". Default: disabled, so leaving
    // the whole section untouched produces the pre-A7 behaviour.
    var scheduleState by rememberSaveable(stateSaver = ScheduleUiState.Saver) { mutableStateOf(ScheduleUiState()) }
    val trimmed = pattern.trim()
    val conflict =
        remember(trimmed, existingWhitelist) {
            if (trimmed.isBlank()) null else RuleConflictAnalyzer.forHashWildcardBlock(trimmed, existingWhitelist)
        }

    val hashCount = remember(trimmed) { trimmed.count { it == '#' } }
    val coverage =
        remember(trimmed) {
            if (trimmed.isEmpty() || hashCount == 0) {
                0L
            } else {
                HashWildcardMatcher.coveredNumberCount(trimmed)
            }
        }
    val overlap =
        remember(trimmed, existing) {
            if (trimmed.isEmpty()) {
                null
            } else {
                existing
                    .asSequence()
                    .mapNotNull { rule ->
                        val kind = HashWildcardMatcher.coversOrCoveredBy(trimmed, rule.pattern)
                        if (kind == HashWildcardMatcher.Overlap.NONE) null else rule to kind
                    }.firstOrNull()
            }
        }

    val tooBroad = coverage > 100_000_000L
    val isDuplicate = overlap?.second == HashWildcardMatcher.Overlap.EQUAL
    val canConfirm =
        trimmed.isNotEmpty() && hashCount >= 1 && !tooBroad &&
            !isDuplicate && !scheduleState.needsDaySelection

    val overlapMessage: String? =
        overlap?.let { (rule, kind) ->
            when (kind) {
                HashWildcardMatcher.Overlap.EQUAL -> {
                    stringResource(R.string.hash_wildcard_dialog_overlap_equal)
                }

                HashWildcardMatcher.Overlap.B_COVERS_A -> {
                    stringResource(R.string.hash_wildcard_dialog_overlap_covered, rule.pattern)
                }

                HashWildcardMatcher.Overlap.A_COVERS_B -> {
                    stringResource(R.string.hash_wildcard_dialog_overlap_covers, rule.pattern)
                }

                HashWildcardMatcher.Overlap.NONE -> {
                    null
                }
            }
        }

    val patternError: String? =
        when {
            trimmed.isNotEmpty() && hashCount == 0 -> {
                stringResource(R.string.hash_wildcard_dialog_empty_error)
            }

            tooBroad -> {
                stringResource(R.string.hash_wildcard_dialog_too_broad)
            }

            else -> {
                null
            }
        }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = com.sysadmindoc.callshield.ui.theme.SurfaceBright,
        title = { Text(stringResource(R.string.hash_wildcard_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text(stringResource(R.string.hash_wildcard_dialog_pattern_label)) },
                    placeholder = { Text(stringResource(R.string.hash_wildcard_dialog_pattern_hint)) },
                    singleLine = true,
                    isError = patternError != null,
                    supportingText = patternError?.let { msg -> { Text(msg, color = CatRed) } },
                    colors =
                        androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CatPeach,
                            cursorColor = CatPeach,
                        ),
                )
                conflict?.let { RuleConflictWarning(it) }
                androidx.compose.material3.OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.hash_wildcard_dialog_description_label)) },
                    singleLine = true,
                    colors =
                        androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CatPeach,
                            cursorColor = CatPeach,
                        ),
                )
                Text(
                    stringResource(R.string.hash_wildcard_dialog_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = CatSubtext,
                )
                if (hashCount > 0 && !tooBroad) {
                    StatusPill(
                        text =
                            stringResource(
                                R.string.hash_wildcard_dialog_coverage_label,
                                formatCoverage(coverage),
                            ),
                        color = CatPeach,
                        horizontalPadding = 8.dp,
                        verticalPadding = 4.dp,
                        textStyle = MaterialTheme.typography.labelSmall,
                    )
                }
                overlapMessage?.let { msg ->
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDuplicate) CatRed else CatPeach,
                    )
                }

                GradientDivider(color = CatPeach)
                ScheduleSection(scheduleState) { scheduleState = it }
            }
        },
        confirmButton = {
            PremiumActionButton(
                label = stringResource(R.string.hash_wildcard_dialog_add),
                icon = Icons.Default.Tune,
                color = CatPeach,
                onClick = { onAdd(trimmed, description.trim(), scheduleState.toSchedule()) },
                enabled = canConfirm,
            )
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.hash_wildcard_dialog_cancel), color = CatSubtext)
            }
        },
    )
}
