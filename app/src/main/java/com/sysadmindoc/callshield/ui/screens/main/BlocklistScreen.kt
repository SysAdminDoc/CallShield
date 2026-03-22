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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.WildcardRule
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.*

@Composable
fun BlocklistScreen(viewModel: MainViewModel) {
    val userBlocked by viewModel.userBlockedNumbers.collectAsState()
    val allSpam by viewModel.allSpamNumbers.collectAsState()
    val wildcardRules by viewModel.wildcardRules.collectAsState()
    val importResult by viewModel.importResult.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var showWildcardDialog by remember { mutableStateOf(false) }
    var tabIndex by remember { mutableIntStateOf(0) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.importBlocklist(it) } }

    Column(modifier = Modifier.fillMaxSize()) {
        // Tabs
        ScrollableTabRow(
            selectedTabIndex = tabIndex,
            containerColor = Surface,
            contentColor = CatText,
            edgePadding = 8.dp,
            indicator = { tabPositions ->
                if (tabIndex < tabPositions.size) {
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[tabIndex]),
                        color = CatGreen
                    )
                }
            }
        ) {
            Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("Blocklist") })
            Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("Wildcards (${wildcardRules.size})") })
            Tab(selected = tabIndex == 2, onClick = { tabIndex = 2 }, text = { Text("Database (${allSpam.size})") })
        }

        // Import result snackbar
        importResult?.let {
            Text(it, color = CatGreen, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        Box(modifier = Modifier.weight(1f)) {
            when (tabIndex) {
                0 -> {
                    if (userBlocked.isEmpty()) {
                        EmptyState("No manually blocked numbers", "Tap + to add a number")
                    } else {
                        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(userBlocked, key = { it.id }) { number ->
                                BlocklistItem(number = number, onUnblock = { viewModel.unblockNumber(number) })
                            }
                        }
                    }
                }
                1 -> {
                    if (wildcardRules.isEmpty()) {
                        EmptyState("No wildcard rules", "Tap + to add a pattern like +1832555*")
                    } else {
                        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                2 -> {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(allSpam, key = { it.id }) { number -> DatabaseItem(number = number) }
                    }
                }
            }

            // FABs
            Column(
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Export/import row (tab 0 only)
                if (tabIndex == 0 && userBlocked.isNotEmpty()) {
                    SmallFloatingActionButton(
                        onClick = { viewModel.exportBlocklist() },
                        containerColor = CatBlue, contentColor = Black
                    ) { Icon(Icons.Default.Share, "Export") }
                }
                if (tabIndex == 0) {
                    SmallFloatingActionButton(
                        onClick = { importLauncher.launch("application/json") },
                        containerColor = CatMauve, contentColor = Black
                    ) { Icon(Icons.Default.FileOpen, "Import") }
                }
                FloatingActionButton(
                    onClick = { if (tabIndex == 1) showWildcardDialog = true else showAddDialog = true },
                    containerColor = CatGreen, contentColor = Black
                ) { Icon(Icons.Default.Add, "Add") }
            }
        }
    }

    if (showAddDialog) {
        AddNumberDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { number, description ->
                viewModel.blockNumber(number, description = description)
                showAddDialog = false
            }
        )
    }

    if (showWildcardDialog) {
        AddWildcardDialog(
            onDismiss = { showWildcardDialog = false },
            onAdd = { pattern, isRegex, desc ->
                viewModel.addWildcardRule(pattern, isRegex, desc)
                showWildcardDialog = false
            }
        )
    }
}

@Composable
fun EmptyState(title: String, subtitle: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.PlaylistAdd, null, tint = CatOverlay, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(12.dp))
            Text(title, color = CatSubtext)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = CatOverlay, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun WildcardRuleItem(rule: WildcardRule, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant), shape = RoundedCornerShape(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (rule.isRegex) Icons.Default.Code else Icons.Default.FilterAlt,
                null, tint = CatYellow, modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(rule.pattern, fontWeight = FontWeight.SemiBold)
                if (rule.description.isNotEmpty()) {
                    Text(rule.description, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                }
                Text(
                    if (rule.isRegex) "Regex" else "Wildcard",
                    style = MaterialTheme.typography.labelSmall, color = CatOverlay
                )
            }
            Switch(checked = rule.enabled, onCheckedChange = onToggle, colors = SwitchDefaults.colors(checkedTrackColor = CatGreen))
            IconButton(onClick = onDelete) { Icon(Icons.Default.Close, "Delete", tint = CatOverlay) }
        }
    }
}

@Composable
fun BlocklistItem(number: SpamNumber, onUnblock: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant), shape = RoundedCornerShape(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Block, null, tint = CatRed, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(number.number, fontWeight = FontWeight.SemiBold)
                if (number.description.isNotEmpty()) {
                    Text(number.description, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                }
            }
            IconButton(onClick = onUnblock) { Icon(Icons.Default.RemoveCircleOutline, "Unblock", tint = CatOverlay) }
        }
    }
}

@Composable
fun DatabaseItem(number: SpamNumber) {
    val typeColor = when (number.type) { "robocall" -> CatRed; "scam" -> CatPeach; "telemarketer" -> CatYellow; else -> CatSubtext }
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant), shape = RoundedCornerShape(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = typeColor, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(number.number, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(number.type.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall, color = typeColor)
                    Text("${number.reports} reports", style = MaterialTheme.typography.labelSmall, color = CatOverlay)
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
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = SurfaceVariant,
        title = { Text("Block Number") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = number, onValueChange = { number = it }, label = { Text("Phone Number") },
                    placeholder = { Text("+1234567890") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                    singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CatGreen, cursorColor = CatGreen))
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description (optional)") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (number.isNotBlank()) onAdd(number, description) }),
                    singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CatGreen, cursorColor = CatGreen))
            }
        },
        confirmButton = { Button(onClick = { if (number.isNotBlank()) onAdd(number, description) }, colors = ButtonDefaults.buttonColors(containerColor = CatGreen)) { Text("Block", color = Black) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = CatSubtext) } }
    )
}

@Composable
fun AddWildcardDialog(onDismiss: () -> Unit, onAdd: (String, Boolean, String) -> Unit) {
    var pattern by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isRegex by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = SurfaceVariant,
        title = { Text("Add Wildcard Rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = pattern, onValueChange = { pattern = it },
                    label = { Text(if (isRegex) "Regex Pattern" else "Wildcard Pattern") },
                    placeholder = { Text(if (isRegex) "^\\+1832555\\d{4}$" else "+1832555*") },
                    singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CatYellow, cursorColor = CatYellow))
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description (optional)") },
                    singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CatYellow, cursorColor = CatYellow))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isRegex, onCheckedChange = { isRegex = it }, colors = CheckboxDefaults.colors(checkedColor = CatYellow))
                    Spacer(Modifier.width(4.dp))
                    Text("Use regex instead of wildcard", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    if (isRegex) "Regex: Use standard regex syntax"
                    else "Wildcard: * matches any digits, ? matches one digit",
                    style = MaterialTheme.typography.labelSmall, color = CatOverlay
                )
            }
        },
        confirmButton = { Button(onClick = { if (pattern.isNotBlank()) onAdd(pattern, isRegex, description) }, colors = ButtonDefaults.buttonColors(containerColor = CatYellow)) { Text("Add", color = Black) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = CatSubtext) } }
    )
}
