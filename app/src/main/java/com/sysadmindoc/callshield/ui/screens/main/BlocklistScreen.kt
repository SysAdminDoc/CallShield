package com.sysadmindoc.callshield.ui.screens.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.data.model.SmsKeywordRule
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.WhitelistEntry
import com.sysadmindoc.callshield.data.model.WildcardRule
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.Black
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
import com.sysadmindoc.callshield.ui.theme.PremiumCard
import com.sysadmindoc.callshield.ui.theme.StatusPill
import com.sysadmindoc.callshield.ui.theme.SurfaceBright
import com.sysadmindoc.callshield.ui.theme.hapticConfirm
import com.sysadmindoc.callshield.ui.theme.hapticTick
import kotlinx.coroutines.launch

private const val BLOCKLIST_TAB_BLOCKED = 0
private const val BLOCKLIST_TAB_WILDCARDS = 1
private const val BLOCKLIST_TAB_KEYWORDS = 2
private const val BLOCKLIST_TAB_WHITELIST = 3
private const val BLOCKLIST_TAB_DATABASE = 4

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
    val userBlocked by viewModel.userBlockedNumbers.collectAsState()
    val allSpam by viewModel.allSpamNumbers.collectAsState()
    val wildcardRules by viewModel.wildcardRules.collectAsState()
    val whitelistEntries by viewModel.whitelistEntries.collectAsState()
    val keywordRules by viewModel.keywordRules.collectAsState()
    val importResult by viewModel.importResult.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var showWildcardDialog by remember { mutableStateOf(false) }
    var showWhitelistDialog by remember { mutableStateOf(false) }
    var showKeywordDialog by remember { mutableStateOf(false) }
    var tabIndex by rememberSaveable { mutableStateOf(BLOCKLIST_TAB_BLOCKED) }
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importBlocklist(it) }
    }

    LaunchedEffect(importResult) {
        val message = importResult ?: return@LaunchedEffect
        snackbarHost.currentSnackbarData?.dismiss()
        snackbarHost.showSnackbar(message, duration = SnackbarDuration.Short)
        viewModel.clearImportResult()
    }

    val workspace = when (tabIndex) {
        BLOCKLIST_TAB_BLOCKED -> BlocklistWorkspaceModel(
            title = stringResource(R.string.blocklist_overview_blocked_title),
            subtitle = stringResource(R.string.blocklist_overview_blocked_subtitle),
            count = userBlocked.size,
            accentColor = CatRed,
            icon = Icons.Default.Block,
            addActionLabel = stringResource(R.string.blocklist_action_add_number),
            primaryUtilityLabel = stringResource(R.string.blocklist_action_import),
            onPrimaryUtility = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
            secondaryUtilityLabel = userBlocked.takeIf { it.isNotEmpty() }?.let {
                stringResource(R.string.blocklist_action_export)
            },
            onSecondaryUtility = userBlocked.takeIf { it.isNotEmpty() }?.let {
                { viewModel.exportBlocklist() }
            }
        )
        BLOCKLIST_TAB_WILDCARDS -> BlocklistWorkspaceModel(
            title = stringResource(R.string.blocklist_overview_wildcards_title),
            subtitle = stringResource(R.string.blocklist_overview_wildcards_subtitle),
            count = wildcardRules.size,
            accentColor = CatYellow,
            icon = Icons.Default.FilterAlt,
            addActionLabel = stringResource(R.string.blocklist_action_add_wildcard)
        )
        BLOCKLIST_TAB_KEYWORDS -> BlocklistWorkspaceModel(
            title = stringResource(R.string.blocklist_overview_keywords_title),
            subtitle = stringResource(R.string.blocklist_overview_keywords_subtitle),
            count = keywordRules.size,
            accentColor = CatMauve,
            icon = Icons.Default.TextFields,
            addActionLabel = stringResource(R.string.blocklist_action_add_keyword)
        )
        BLOCKLIST_TAB_WHITELIST -> BlocklistWorkspaceModel(
            title = stringResource(R.string.blocklist_overview_whitelist_title),
            subtitle = stringResource(R.string.blocklist_overview_whitelist_subtitle),
            count = whitelistEntries.size,
            accentColor = CatGreen,
            icon = Icons.Default.CheckCircle,
            addActionLabel = stringResource(R.string.blocklist_action_add_trusted)
        )
        else -> BlocklistWorkspaceModel(
            title = stringResource(R.string.blocklist_overview_database_title),
            subtitle = stringResource(R.string.blocklist_overview_database_subtitle),
            count = allSpam.size,
            accentColor = CatBlue,
            icon = Icons.Default.Storage,
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            BlocklistOverviewCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                workspace = workspace
            )

            ScrollableTabRow(
                selectedTabIndex = tabIndex,
                containerColor = com.sysadmindoc.callshield.ui.theme.Surface,
                contentColor = CatText,
                edgePadding = 8.dp,
                divider = {},
                indicator = {
                    TabRowDefaults.SecondaryIndicator(color = workspace.accentColor)
                }
            ) {
                Tab(
                    selected = tabIndex == BLOCKLIST_TAB_BLOCKED,
                    onClick = { tabIndex = BLOCKLIST_TAB_BLOCKED },
                    selectedContentColor = CatText,
                    unselectedContentColor = CatSubtext,
                    text = { Text(stringResource(R.string.blocklist_tab_blocked_short)) }
                )
                Tab(
                    selected = tabIndex == BLOCKLIST_TAB_WILDCARDS,
                    onClick = { tabIndex = BLOCKLIST_TAB_WILDCARDS },
                    selectedContentColor = CatText,
                    unselectedContentColor = CatSubtext,
                    text = { Text(stringResource(R.string.blocklist_tab_wildcards_short)) }
                )
                Tab(
                    selected = tabIndex == BLOCKLIST_TAB_KEYWORDS,
                    onClick = { tabIndex = BLOCKLIST_TAB_KEYWORDS },
                    selectedContentColor = CatText,
                    unselectedContentColor = CatSubtext,
                    text = { Text(stringResource(R.string.blocklist_tab_keywords_short)) }
                )
                Tab(
                    selected = tabIndex == BLOCKLIST_TAB_WHITELIST,
                    onClick = { tabIndex = BLOCKLIST_TAB_WHITELIST },
                    selectedContentColor = CatText,
                    unselectedContentColor = CatSubtext,
                    text = { Text(stringResource(R.string.blocklist_tab_whitelist_short)) }
                )
                Tab(
                    selected = tabIndex == BLOCKLIST_TAB_DATABASE,
                    onClick = { tabIndex = BLOCKLIST_TAB_DATABASE },
                    selectedContentColor = CatText,
                    unselectedContentColor = CatSubtext,
                    text = { Text(stringResource(R.string.blocklist_tab_database_short)) }
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
                                accentColor = CatRed
                            )
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 104.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(userBlocked, key = { it.id }) { number ->
                                    BlocklistItem(number) { viewModel.unblockNumber(number) }
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
                                accentColor = CatYellow
                            )
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 104.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(wildcardRules, key = { it.id }) { rule ->
                                    WildcardRuleItem(
                                        rule = rule,
                                        onToggle = { viewModel.toggleWildcardRule(rule.id, it) },
                                        onDelete = { viewModel.deleteWildcardRule(rule) }
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
                                accentColor = CatMauve
                            )
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 104.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(keywordRules, key = { it.id }) { rule ->
                                    KeywordRuleItem(
                                        rule = rule,
                                        onToggle = { viewModel.toggleKeywordRule(rule.id, it) },
                                        onDelete = { viewModel.deleteKeywordRule(rule) }
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
                                accentColor = CatGreen
                            )
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 104.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(whitelistEntries, key = { it.id }) { entry ->
                                    WhitelistItem(
                                        entry = entry,
                                        onRemove = { viewModel.removeFromWhitelist(entry) },
                                        onToggleEmergency = { viewModel.toggleWhitelistEmergency(entry.id, !entry.isEmergency) }
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        if (allSpam.isEmpty()) {
                            EmptyStateCard(
                                title = stringResource(R.string.blocklist_empty_database),
                                subtitle = stringResource(R.string.blocklist_empty_database_sub),
                                icon = Icons.Default.Storage,
                                accentColor = CatBlue
                            )
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 32.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(allSpam, key = { it.id }) { number ->
                                    DatabaseItem(number)
                                }
                            }
                        }
                    }
                }

                workspace.addActionLabel?.let { addLabel ->
                    ExtendedFloatingActionButton(
                        onClick = {
                            when (tabIndex) {
                                BLOCKLIST_TAB_BLOCKED -> showAddDialog = true
                                BLOCKLIST_TAB_WILDCARDS -> showWildcardDialog = true
                                BLOCKLIST_TAB_KEYWORDS -> showKeywordDialog = true
                                BLOCKLIST_TAB_WHITELIST -> showWhitelistDialog = true
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        containerColor = workspace.accentColor,
                        contentColor = Black,
                        icon = { Icon(Icons.Default.Add, stringResource(R.string.cd_add)) },
                        text = { Text(addLabel, fontWeight = FontWeight.Bold) }
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }

    if (showAddDialog) {
        AddNumberDialog(onDismiss = { showAddDialog = false }) { number, description ->
            viewModel.blockNumber(number, description = description)
            showAddDialog = false
            hapticConfirm(context)
            scope.launch {
                snackbarHost.showSnackbar(
                    context.getString(R.string.blocklist_number_blocked),
                    duration = SnackbarDuration.Short
                )
            }
        }
    }
    if (showWildcardDialog) {
        AddWildcardDialog(onDismiss = { showWildcardDialog = false }) { pattern, isRegex, description ->
            viewModel.addWildcardRule(pattern, isRegex, description)
            showWildcardDialog = false
            hapticTick(context)
            scope.launch {
                snackbarHost.showSnackbar(
                    context.getString(R.string.blocklist_rule_added),
                    duration = SnackbarDuration.Short
                )
            }
        }
    }
    if (showWhitelistDialog) {
        AddWhitelistDialog(onDismiss = { showWhitelistDialog = false }) { number, description, emergency ->
            viewModel.addToWhitelist(number, description, isEmergency = emergency)
            showWhitelistDialog = false
            hapticTick(context)
            val message = if (emergency) {
                context.getString(R.string.emergency_contacts_added)
            } else {
                context.getString(R.string.blocklist_number_whitelisted)
            }
            scope.launch { snackbarHost.showSnackbar(message, duration = SnackbarDuration.Short) }
        }
    }
    if (showKeywordDialog) {
        AddKeywordDialog(onDismiss = { showKeywordDialog = false }) { keyword, caseSensitive, description ->
            viewModel.addKeywordRule(keyword, caseSensitive, description)
            showKeywordDialog = false
            hapticTick(context)
            scope.launch {
                snackbarHost.showSnackbar(
                    context.getString(R.string.blocklist_keyword_rule_added),
                    duration = SnackbarDuration.Short
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
    PremiumCard(
        modifier = modifier,
        accentColor = workspace.accentColor,
        cornerRadius = 22.dp
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = workspace.accentColor.copy(alpha = 0.12f)
                ) {
                    Icon(
                        imageVector = workspace.icon,
                        contentDescription = null,
                        tint = workspace.accentColor,
                        modifier = Modifier.padding(14.dp)
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusPill(
                        text = stringResource(R.string.blocklist_count_saved, workspace.count),
                        color = workspace.accentColor,
                        horizontalPadding = 10.dp,
                        verticalPadding = 6.dp
                    )
                    Text(
                        text = workspace.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = CatText
                    )
                    Text(
                        text = workspace.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = CatSubtext
                    )
                }
            }

            if (workspace.primaryUtilityLabel != null || workspace.secondaryUtilityLabel != null) {
                GradientDivider(color = workspace.accentColor)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    workspace.primaryUtilityLabel?.let { label ->
                        OutlinedButton(
                            onClick = { workspace.onPrimaryUtility?.invoke() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.FileOpen, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                    workspace.secondaryUtilityLabel?.let { label ->
                        OutlinedButton(
                            onClick = { workspace.onSecondaryUtility?.invoke() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            }
        }
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
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        PremiumCard(
            modifier = Modifier.fillMaxWidth(),
            accentColor = accentColor,
            cornerRadius = 22.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = accentColor.copy(alpha = 0.12f)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = stringResource(R.string.cd_empty_list),
                        tint = accentColor,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = CatText,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = CatSubtext,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun BlocklistItem(number: SpamNumber, onUnblock: () -> Unit) {
    PremiumCard(cornerRadius = 16.dp, accentColor = CatRed) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Block, null, tint = CatRed, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(PhoneFormatter.format(number.number), fontWeight = FontWeight.SemiBold, color = CatText)
                if (number.description.isNotEmpty()) {
                    Text(number.description, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                }
                StatusPill(
                    text = stringResource(R.string.blocklist_manual_badge),
                    color = CatRed,
                    horizontalPadding = 8.dp,
                    verticalPadding = 4.dp,
                    textStyle = MaterialTheme.typography.labelSmall
                )
            }
            IconButton(onClick = onUnblock) {
                Icon(Icons.Default.RemoveCircleOutline, stringResource(R.string.cd_unblock), tint = CatOverlay)
            }
        }
    }
}

@Composable
fun WildcardRuleItem(rule: WildcardRule, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    PremiumCard(cornerRadius = 16.dp, accentColor = CatYellow) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (rule.isRegex) Icons.Default.Code else Icons.Default.FilterAlt,
                null,
                tint = CatYellow,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
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
                    textStyle = MaterialTheme.typography.labelSmall
                )
            }
            androidx.compose.material3.Switch(
                checked = rule.enabled,
                onCheckedChange = onToggle,
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedTrackColor = CatGreen,
                    checkedThumbColor = Black
                )
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Close, stringResource(R.string.cd_delete_rule), tint = CatRed.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
fun KeywordRuleItem(rule: SmsKeywordRule, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    PremiumCard(cornerRadius = 16.dp, accentColor = CatMauve) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.TextFields, null, tint = CatMauve, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
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
                    textStyle = MaterialTheme.typography.labelSmall
                )
            }
            androidx.compose.material3.Switch(
                checked = rule.enabled,
                onCheckedChange = onToggle,
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedTrackColor = CatGreen,
                    checkedThumbColor = Black
                )
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
    val emergencyDescription = if (entry.isEmergency) {
        stringResource(R.string.emergency_contacts_unmark)
    } else {
        stringResource(R.string.emergency_contacts_mark_as)
    }
    PremiumCard(cornerRadius = 16.dp, accentColor = accent) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (entry.isEmergency) Icons.Default.PriorityHigh else Icons.Default.CheckCircle,
                null,
                tint = accent,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(PhoneFormatter.format(entry.number), fontWeight = FontWeight.SemiBold, color = CatText)
                if (entry.description.isNotEmpty()) {
                    Text(entry.description, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                }
                if (entry.isEmergency) {
                    StatusPill(
                        text = stringResource(R.string.emergency_contacts_badge),
                        color = CatRed,
                        horizontalPadding = 8.dp,
                        verticalPadding = 4.dp,
                        textStyle = MaterialTheme.typography.labelSmall
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
fun DatabaseItem(number: SpamNumber) {
    val typeColor = when (number.type.lowercase()) {
        "robocall" -> CatRed
        "scam" -> com.sysadmindoc.callshield.ui.theme.CatPeach
        "telemarketer" -> CatYellow
        else -> CatBlue
    }
    PremiumCard(cornerRadius = 16.dp, accentColor = typeColor) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Storage, null, tint = typeColor, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(PhoneFormatter.format(number.number), fontWeight = FontWeight.SemiBold, color = CatText)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(
                        text = number.type.replaceFirstChar { it.uppercase() },
                        color = typeColor,
                        horizontalPadding = 8.dp,
                        verticalPadding = 4.dp,
                        textStyle = MaterialTheme.typography.labelSmall
                    )
                    StatusPill(
                        text = stringResource(R.string.blocklist_reports, number.reports),
                        color = CatBlue,
                        horizontalPadding = 8.dp,
                        verticalPadding = 4.dp,
                        textStyle = MaterialTheme.typography.labelSmall
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
fun AddNumberDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var number by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val normalizedNumber = remember(number) { normalizePhoneInput(number) }
    val canConfirm = normalizedNumber.filter(Char::isDigit).length >= 5

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
                    supportingText = if (canConfirm) {
                        { Text(PhoneFormatter.format(normalizedNumber), color = CatSubtext) }
                    } else {
                        null
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Next
                    ),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CatGreen,
                        cursorColor = CatGreen
                    )
                )
                androidx.compose.material3.OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.dialog_description_optional)) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = {
                            if (canConfirm) onAdd(normalizedNumber, description.trim())
                        }
                    ),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CatGreen,
                        cursorColor = CatGreen
                    )
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(
                onClick = { onAdd(normalizedNumber, description.trim()) },
                enabled = canConfirm,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = CatGreen)
            ) {
                Text(stringResource(R.string.dialog_block), color = Black)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel), color = CatSubtext)
            }
        }
    )
}

@Composable
fun AddWildcardDialog(onDismiss: () -> Unit, onAdd: (String, Boolean, String) -> Unit) {
    val context = LocalContext.current
    var pattern by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isRegex by remember { mutableStateOf(false) }
    var regexError by remember { mutableStateOf<String?>(null) }
    val trimmedPattern = pattern.trim()

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
                        regexError = null
                    },
                    label = { Text(stringResource(if (isRegex) R.string.dialog_regex_label else R.string.dialog_pattern_label)) },
                    placeholder = { Text(stringResource(if (isRegex) R.string.dialog_regex_placeholder else R.string.dialog_wildcard_placeholder)) },
                    singleLine = true,
                    isError = regexError != null,
                    supportingText = regexError?.let { message -> { Text(message, color = CatRed) } },
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CatYellow,
                        cursorColor = CatYellow
                    )
                )
                androidx.compose.material3.OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.dialog_description)) },
                    singleLine = true,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CatYellow,
                        cursorColor = CatYellow
                    )
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = isRegex,
                        onCheckedChange = {
                            isRegex = it
                            regexError = null
                        },
                        colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = CatYellow)
                    )
                    Text(stringResource(R.string.dialog_use_regex), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(
                onClick = {
                    if (trimmedPattern.isNotBlank()) {
                        if (isRegex) {
                            try {
                                Regex(trimmedPattern)
                                onAdd(trimmedPattern, true, description.trim())
                            } catch (e: Exception) {
                                regexError = context.getString(R.string.dialog_invalid_regex, e.message ?: "")
                            }
                        } else {
                            onAdd(trimmedPattern, false, description.trim())
                        }
                    }
                },
                enabled = trimmedPattern.isNotBlank(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = CatYellow)
            ) {
                Text(stringResource(R.string.dialog_add), color = Black)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel), color = CatSubtext)
            }
        }
    )
}

@Composable
fun AddWhitelistDialog(onDismiss: () -> Unit, onAdd: (String, String, Boolean) -> Unit) {
    var number by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var emergency by remember { mutableStateOf(false) }
    val normalizedNumber = remember(number) { normalizePhoneInput(number) }
    val canConfirm = normalizedNumber.filter(Char::isDigit).length >= 5

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
                    supportingText = if (canConfirm) {
                        { Text(PhoneFormatter.format(normalizedNumber), color = CatSubtext) }
                    } else {
                        null
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Next
                    ),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CatGreen,
                        cursorColor = CatGreen
                    )
                )
                androidx.compose.material3.OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.dialog_description)) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = {
                            if (canConfirm) onAdd(normalizedNumber, description.trim(), emergency)
                        }
                    ),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CatGreen,
                        cursorColor = CatGreen
                    )
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = emergency,
                        onCheckedChange = { emergency = it },
                        colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = CatRed)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.emergency_contacts_mark_as), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.emergency_contacts_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = CatSubtext
                        )
                    }
                }
                Text(stringResource(R.string.dialog_whitelist_note), style = MaterialTheme.typography.labelSmall, color = CatSubtext)
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(
                onClick = { onAdd(normalizedNumber, description.trim(), emergency) },
                enabled = canConfirm,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = if (emergency) CatRed else CatGreen),
            ) {
                Text(
                    if (emergency) stringResource(R.string.emergency_contacts_add) else stringResource(R.string.dialog_whitelist),
                    color = Black
                )
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel), color = CatSubtext)
            }
        }
    )
}

@Composable
fun AddKeywordDialog(onDismiss: () -> Unit, onAdd: (String, Boolean, String) -> Unit) {
    var keyword by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var caseSensitive by remember { mutableStateOf(false) }
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
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CatMauve,
                        cursorColor = CatMauve
                    )
                )
                androidx.compose.material3.OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.dialog_description)) },
                    singleLine = true,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CatMauve,
                        cursorColor = CatMauve
                    )
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = caseSensitive,
                        onCheckedChange = { caseSensitive = it },
                        colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = CatMauve)
                    )
                    Text(stringResource(R.string.blocklist_case_sensitive), style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.dialog_keyword_note), style = MaterialTheme.typography.labelSmall, color = CatSubtext)
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(
                onClick = { onAdd(trimmedKeyword, caseSensitive, description.trim()) },
                enabled = trimmedKeyword.isNotBlank(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = CatMauve)
            ) {
                Text(stringResource(R.string.dialog_add), color = Black)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel), color = CatSubtext)
            }
        }
    )
}

private fun sanitizePhoneInput(input: String): String {
    val builder = StringBuilder()
    input.forEach { char ->
        when {
            char.isDigit() -> builder.append(char)
            char == '+' && builder.isEmpty() -> builder.append(char)
            char == ' ' || char == '-' || char == '(' || char == ')' -> builder.append(char)
        }
    }
    return builder.toString().take(24)
}

private fun normalizePhoneInput(input: String): String {
    val digitsOnly = input.filter { it.isDigit() }
    return if (input.trim().startsWith("+")) {
        "+$digitsOnly"
    } else {
        digitsOnly
    }
}
