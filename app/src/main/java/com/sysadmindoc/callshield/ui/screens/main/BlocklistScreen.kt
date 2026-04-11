package com.sysadmindoc.callshield.ui.screens.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.data.model.SmsKeywordRule
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.WhitelistEntry
import com.sysadmindoc.callshield.data.model.WildcardRule
import com.sysadmindoc.callshield.ui.MainViewModel
import kotlinx.coroutines.launch
import com.sysadmindoc.callshield.ui.theme.*

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
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importBlocklist(it) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = tabIndex, containerColor = Surface, contentColor = CatText,
            edgePadding = 8.dp, divider = {},
            indicator = {
                TabRowDefaults.SecondaryIndicator(color = CatGreen)
            }
        ) {
            Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("Blocklist (${userBlocked.size})") })
            Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("Wildcards (${wildcardRules.size})") })
            Tab(selected = tabIndex == 2, onClick = { tabIndex = 2 }, text = { Text("Keywords (${keywordRules.size})") })
            Tab(selected = tabIndex == 3, onClick = { tabIndex = 3 }, text = { Text("Whitelist (${whitelistEntries.size})") })
            Tab(selected = tabIndex == 4, onClick = { tabIndex = 4 }, text = { Text("Database (${allSpam.size})") })
        }

        importResult?.let {
            val resultColor = if (it.startsWith("Imported ")) CatGreen else CatPeach
            Text(it, color = resultColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            LaunchedEffect(it) {
                kotlinx.coroutines.delay(4000)
                viewModel.clearImportResult()
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (tabIndex) {
                0 -> {
                    if (userBlocked.isEmpty()) EmptyState("No blocked numbers", "Tap + to add")
                    else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(userBlocked, key = { it.id }) { n -> BlocklistItem(n) { viewModel.unblockNumber(n) } }
                    }
                }
                1 -> {
                    if (wildcardRules.isEmpty()) EmptyState("No wildcard rules", "Tap + to add a pattern")
                    else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(wildcardRules, key = { it.id }) { r ->
                            WildcardRuleItem(r, { viewModel.toggleWildcardRule(r.id, it) }, { viewModel.deleteWildcardRule(r) })
                        }
                    }
                }
                2 -> {
                    if (keywordRules.isEmpty()) EmptyState("No keyword rules", "Block SMS containing specific words")
                    else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(keywordRules, key = { it.id }) { r ->
                            KeywordRuleItem(r, { viewModel.toggleKeywordRule(r.id, it) }, { viewModel.deleteKeywordRule(r) })
                        }
                    }
                }
                3 -> {
                    if (whitelistEntries.isEmpty()) EmptyState("No whitelisted numbers", "Numbers here always pass through")
                    else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(whitelistEntries, key = { it.id }) { e ->
                            WhitelistItem(e) { viewModel.removeFromWhitelist(e) }
                        }
                    }
                }
                4 -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(allSpam, key = { it.id }) { n -> DatabaseItem(n) }
                }
            }

            // FABs
            Column(modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tabIndex == 0 && userBlocked.isNotEmpty()) {
                    SmallFloatingActionButton(onClick = { viewModel.exportBlocklist() }, containerColor = CatBlue, contentColor = Black) { Icon(Icons.Default.Share, "Export") }
                }
                if (tabIndex == 0) {
                    SmallFloatingActionButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }, containerColor = CatMauve, contentColor = Black) { Icon(Icons.Default.FileOpen, "Import") }
                }
                FloatingActionButton(
                    onClick = {
                        when (tabIndex) {
                            0 -> showAddDialog = true
                            1 -> showWildcardDialog = true
                            2 -> showKeywordDialog = true
                            3 -> showWhitelistDialog = true
                        }
                    },
                    containerColor = CatGreen, contentColor = Black,
                    shape = RoundedCornerShape(16.dp)
                ) { Icon(Icons.Default.Add, "Add") }
            }
        }
    }

    // Snackbar host overlaid on the screen
    Box(modifier = Modifier.fillMaxSize()) {
        SnackbarHost(snackbarHost, modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (showAddDialog) AddNumberDialog({ showAddDialog = false }) { num, desc ->
        viewModel.blockNumber(num, description = desc); showAddDialog = false
        hapticConfirm(context); scope.launch { snackbarHost.showSnackbar("Number blocked", duration = SnackbarDuration.Short) }
    }
    if (showWildcardDialog) AddWildcardDialog({ showWildcardDialog = false }) { p, r, d ->
        viewModel.addWildcardRule(p, r, d); showWildcardDialog = false
        hapticTick(context); scope.launch { snackbarHost.showSnackbar("Rule added", duration = SnackbarDuration.Short) }
    }
    if (showWhitelistDialog) AddWhitelistDialog({ showWhitelistDialog = false }) { num, desc ->
        viewModel.addToWhitelist(num, desc); showWhitelistDialog = false
        hapticTick(context); scope.launch { snackbarHost.showSnackbar("Number whitelisted", duration = SnackbarDuration.Short) }
    }
    if (showKeywordDialog) AddKeywordDialog({ showKeywordDialog = false }) { kw, cs, d ->
        viewModel.addKeywordRule(kw, cs, d); showKeywordDialog = false
        hapticTick(context); scope.launch { snackbarHost.showSnackbar("Keyword rule added", duration = SnackbarDuration.Short) }
    }
}

@Composable
fun EmptyState(title: String, subtitle: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.AutoMirrored.Filled.PlaylistAdd, null, tint = CatOverlay,
                modifier = Modifier.size(64.dp).accentGlow(CatOverlay, 150f, 0.04f)
            )
            Spacer(Modifier.height(12.dp))
            Text(title, color = CatSubtext)
            Text(subtitle, color = CatOverlay, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun BlocklistItem(number: SpamNumber, onUnblock: () -> Unit) {
    PremiumCard(cornerRadius = 14.dp) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Block, null, tint = CatRed, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(number.number, fontWeight = FontWeight.SemiBold)
                if (number.description.isNotEmpty()) Text(number.description, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
            }
            IconButton(onClick = onUnblock) { Icon(Icons.Default.RemoveCircleOutline, "Unblock", tint = CatOverlay) }
        }
    }
}

@Composable
fun WildcardRuleItem(rule: WildcardRule, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    PremiumCard(cornerRadius = 14.dp) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (rule.isRegex) Icons.Default.Code else Icons.Default.FilterAlt, null, tint = CatYellow, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(rule.pattern, fontWeight = FontWeight.SemiBold)
                if (rule.description.isNotEmpty()) Text(rule.description, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                Text(if (rule.isRegex) "Regex" else "Wildcard", style = MaterialTheme.typography.labelSmall, color = CatOverlay)
            }
            Switch(checked = rule.enabled, onCheckedChange = onToggle, colors = SwitchDefaults.colors(checkedTrackColor = CatGreen, checkedThumbColor = Black))
            IconButton(onClick = onDelete) { Icon(Icons.Default.Close, "Delete", tint = CatOverlay) }
        }
    }
}

@Composable
fun KeywordRuleItem(rule: SmsKeywordRule, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    PremiumCard(cornerRadius = 14.dp) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.TextFields, null, tint = CatMauve, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("\"${rule.keyword}\"", fontWeight = FontWeight.SemiBold)
                if (rule.description.isNotEmpty()) Text(rule.description, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                Text(if (rule.caseSensitive) "Case-sensitive" else "Case-insensitive", style = MaterialTheme.typography.labelSmall, color = CatOverlay)
            }
            Switch(checked = rule.enabled, onCheckedChange = onToggle, colors = SwitchDefaults.colors(checkedTrackColor = CatGreen, checkedThumbColor = Black))
            IconButton(onClick = onDelete) { Icon(Icons.Default.Close, "Delete", tint = CatOverlay) }
        }
    }
}

@Composable
fun WhitelistItem(entry: WhitelistEntry, onRemove: () -> Unit) {
    PremiumCard(cornerRadius = 14.dp) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, null, tint = CatGreen, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.number, fontWeight = FontWeight.SemiBold)
                if (entry.description.isNotEmpty()) Text(entry.description, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
            }
            IconButton(onClick = onRemove) { Icon(Icons.Default.RemoveCircleOutline, "Remove", tint = CatOverlay) }
        }
    }
}

@Composable
fun DatabaseItem(number: SpamNumber) {
    val typeColor = when (number.type) { "robocall" -> CatRed; "scam" -> CatPeach; "telemarketer" -> CatYellow; else -> CatSubtext }
    PremiumCard(cornerRadius = 14.dp) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = typeColor, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(number.number, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(number.type.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall, color = typeColor)
                    Text("${number.reports} reports", style = MaterialTheme.typography.labelSmall, color = CatOverlay)
                }
                if (number.description.isNotEmpty()) Text(number.description, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
            }
        }
    }
}

// Dialogs
@Composable
fun AddNumberDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var number by remember { mutableStateOf("") }; var desc by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = SurfaceBright, title = { Text("Block Number") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = number, onValueChange = { number = it }, label = { Text("Phone Number") }, placeholder = { Text("+1234567890") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CatGreen, cursorColor = CatGreen))
            OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Description (optional)") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (number.isNotBlank()) onAdd(number, desc) }), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CatGreen, cursorColor = CatGreen))
        } },
        confirmButton = { Button(onClick = { if (number.isNotBlank()) onAdd(number, desc) }, colors = ButtonDefaults.buttonColors(containerColor = CatGreen)) { Text("Block", color = Black) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = CatSubtext) } }
    )
}

@Composable
fun AddWildcardDialog(onDismiss: () -> Unit, onAdd: (String, Boolean, String) -> Unit) {
    var pattern by remember { mutableStateOf("") }; var desc by remember { mutableStateOf("") }; var isRegex by remember { mutableStateOf(false) }
    var regexError by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = SurfaceBright, title = { Text("Add Wildcard Rule") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = pattern, onValueChange = { pattern = it; regexError = null }, label = { Text(if (isRegex) "Regex" else "Pattern") },
                placeholder = { Text(if (isRegex) "^\\+1832\\d{7}$" else "+1832555*") }, singleLine = true,
                isError = regexError != null,
                supportingText = regexError?.let { err -> { Text(err, color = CatRed) } },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CatYellow, cursorColor = CatYellow))
            OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Description") }, singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CatYellow, cursorColor = CatYellow))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = isRegex, onCheckedChange = { isRegex = it; regexError = null }, colors = CheckboxDefaults.colors(checkedColor = CatYellow))
                Text("Use regex", style = MaterialTheme.typography.bodySmall)
            }
        } },
        confirmButton = { Button(onClick = {
            if (pattern.isNotBlank()) {
                if (isRegex) {
                    try { Regex(pattern); onAdd(pattern, true, desc) } catch (e: Exception) { regexError = "Invalid regex: ${e.message}" }
                } else {
                    onAdd(pattern, false, desc)
                }
            }
        }, colors = ButtonDefaults.buttonColors(containerColor = CatYellow)) { Text("Add", color = Black) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = CatSubtext) } }
    )
}

@Composable
fun AddWhitelistDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var number by remember { mutableStateOf("") }; var desc by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = SurfaceBright, title = { Text("Add to Whitelist") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = number, onValueChange = { number = it }, label = { Text("Phone Number") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CatGreen, cursorColor = CatGreen))
            OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Description") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (number.isNotBlank()) onAdd(number, desc) }),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CatGreen, cursorColor = CatGreen))
            Text("Whitelisted numbers will never be blocked, even if they match spam rules.", style = MaterialTheme.typography.labelSmall, color = CatOverlay)
        } },
        confirmButton = { Button(onClick = { if (number.isNotBlank()) onAdd(number, desc) }, colors = ButtonDefaults.buttonColors(containerColor = CatGreen)) { Text("Whitelist", color = Black) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = CatSubtext) } }
    )
}

@Composable
fun AddKeywordDialog(onDismiss: () -> Unit, onAdd: (String, Boolean, String) -> Unit) {
    var keyword by remember { mutableStateOf("") }; var desc by remember { mutableStateOf("") }; var caseSensitive by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = SurfaceBright, title = { Text("Block SMS Keyword") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = keyword, onValueChange = { keyword = it }, label = { Text("Keyword") },
                placeholder = { Text("e.g., free gift card") }, singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CatMauve, cursorColor = CatMauve))
            OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Description") }, singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CatMauve, cursorColor = CatMauve))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = caseSensitive, onCheckedChange = { caseSensitive = it }, colors = CheckboxDefaults.colors(checkedColor = CatMauve))
                Text("Case-sensitive", style = MaterialTheme.typography.bodySmall)
            }
            Text("Any SMS containing this keyword will be blocked.", style = MaterialTheme.typography.labelSmall, color = CatOverlay)
        } },
        confirmButton = { Button(onClick = { if (keyword.isNotBlank()) onAdd(keyword, caseSensitive, desc) }, colors = ButtonDefaults.buttonColors(containerColor = CatMauve)) { Text("Add", color = Black) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = CatSubtext) } }
    )
}
