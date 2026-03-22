package com.sysadmindoc.callshield.ui.screens.main

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
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.*

@Composable
fun BlocklistScreen(viewModel: MainViewModel) {
    val userBlocked by viewModel.userBlockedNumbers.collectAsState()
    val allSpam by viewModel.allSpamNumbers.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var tabIndex by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Tabs
        TabRow(
            selectedTabIndex = tabIndex,
            containerColor = Surface,
            contentColor = CatText,
            indicator = { tabPositions ->
                if (tabIndex < tabPositions.size) {
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[tabIndex]),
                        color = CatGreen
                    )
                }
            }
        ) {
            Tab(
                selected = tabIndex == 0,
                onClick = { tabIndex = 0 },
                text = { Text("My Blocklist") }
            )
            Tab(
                selected = tabIndex == 1,
                onClick = { tabIndex = 1 },
                text = { Text("Database (${allSpam.size})") }
            )
        }

        when (tabIndex) {
            0 -> {
                // User blocklist
                if (userBlocked.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.PlaylistAdd,
                                null,
                                tint = CatOverlay,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text("No manually blocked numbers", color = CatSubtext)
                            Spacer(Modifier.height(8.dp))
                            Text("Tap + to add a number", color = CatOverlay, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(userBlocked, key = { it.id }) { number ->
                            BlocklistItem(
                                number = number,
                                onUnblock = { viewModel.unblockNumber(number) }
                            )
                        }
                    }
                }
            }
            1 -> {
                // Full database
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(allSpam, key = { it.id }) { number ->
                        DatabaseItem(number = number)
                    }
                }
            }
        }

        // FAB
        if (tabIndex == 0) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = CatGreen,
                    contentColor = Black,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Icon(Icons.Default.Add, "Add number")
                }
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
}

@Composable
fun BlocklistItem(number: SpamNumber, onUnblock: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Block, null, tint = CatRed, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(number.number, fontWeight = FontWeight.SemiBold)
                if (number.description.isNotEmpty()) {
                    Text(number.description, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                }
            }
            IconButton(onClick = onUnblock) {
                Icon(Icons.Default.RemoveCircleOutline, "Unblock", tint = CatOverlay)
            }
        }
    }
}

@Composable
fun DatabaseItem(number: SpamNumber) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val typeColor = when (number.type) {
                "robocall" -> CatRed
                "scam" -> CatPeach
                "telemarketer" -> CatYellow
                else -> CatSubtext
            }
            Icon(Icons.Default.Warning, null, tint = typeColor, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(number.number, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        number.type.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = typeColor
                    )
                    Text(
                        "${number.reports} reports",
                        style = MaterialTheme.typography.labelSmall,
                        color = CatOverlay
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

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceVariant,
        title = { Text("Block Number") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    label = { Text("Phone Number") },
                    placeholder = { Text("+1234567890") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone,
                        imeAction = ImeAction.Next
                    ),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CatGreen,
                        cursorColor = CatGreen
                    )
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    placeholder = { Text("e.g., Robocall scam") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (number.isNotBlank()) onAdd(number, description)
                    }),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CatGreen,
                        cursorColor = CatGreen
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (number.isNotBlank()) onAdd(number, description) },
                colors = ButtonDefaults.buttonColors(containerColor = CatGreen)
            ) {
                Text("Block", color = Black)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = CatSubtext)
            }
        }
    )
}
