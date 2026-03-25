package com.sysadmindoc.callshield.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sysadmindoc.callshield.ui.screens.details.NumberDetailScreen
import com.sysadmindoc.callshield.ui.screens.main.BlockedLogScreen
import com.sysadmindoc.callshield.ui.screens.main.BlocklistScreen
import com.sysadmindoc.callshield.ui.screens.main.DashboardScreen
import com.sysadmindoc.callshield.ui.screens.onboarding.OnboardingScreen
import com.sysadmindoc.callshield.ui.screens.lookup.LookupScreen
import com.sysadmindoc.callshield.ui.screens.recent.RecentCallsScreen
import com.sysadmindoc.callshield.ui.screens.more.MoreScreen
import com.sysadmindoc.callshield.ui.theme.*

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions()

        // Deep link: tel: intent or notification tap opens number detail
        val deepLinkNumber = intent?.getStringExtra("open_number")
            ?: intent?.data?.schemeSpecificPart?.takeIf {
                intent?.action == Intent.ACTION_VIEW && intent?.data?.scheme == "tel"
            }
        // App shortcuts
        val shortcutAction = intent?.action

        setContent { CallShieldTheme { CallShieldRoot(deepLinkNumber = deepLinkNumber, shortcutAction = shortcutAction) } }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }
}

@Composable
fun CallShieldRoot(viewModel: MainViewModel = viewModel(), deepLinkNumber: String? = null, shortcutAction: String? = null) {
    val onboardingDone by viewModel.onboardingDone.collectAsState()
    val selectedNumber by viewModel.selectedNumber.collectAsState()

    // Handle deep link and shortcuts
    var initialTab by remember { mutableIntStateOf(0) }
    LaunchedEffect(deepLinkNumber, shortcutAction) {
        if (deepLinkNumber != null) viewModel.openNumberDetail(deepLinkNumber)
        when (shortcutAction) {
            "com.sysadmindoc.callshield.LOOKUP" -> initialTab = 3
            "com.sysadmindoc.callshield.SCAN" -> { initialTab = 0; viewModel.scanCallLog() }
        }
    }

    when {
        !onboardingDone -> OnboardingScreen(onComplete = { viewModel.completeOnboarding() })
        selectedNumber != null -> NumberDetailScreen(
            number = selectedNumber!!,
            viewModel = viewModel,
            onBack = { viewModel.closeNumberDetail() }
        )
        else -> CallShieldApp(viewModel, initialTab)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallShieldApp(viewModel: MainViewModel, startTab: Int = 0) {
    var selectedTab by rememberSaveable { mutableIntStateOf(startTab) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()

    // Close search when switching tabs
    LaunchedEffect(selectedTab) {
        if (showSearch) { showSearch = false; viewModel.setSearchQuery("") }
    }

    val navBarTopBorder = Color.White.copy(alpha = 0.04f)

    Scaffold(
        topBar = {
            if (showSearch) {
                // Global search bar
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = { Text("Search numbers, reasons...", color = CatOverlay) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CatGreen.copy(alpha = 0.5f),
                                cursorColor = CatGreen,
                                unfocusedBorderColor = CatOverlay.copy(alpha = 0.3f)
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {})
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { showSearch = false; viewModel.setSearchQuery("") }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", tint = CatSubtext)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Black)
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            "CallShield",
                            color = CatGreen,
                            letterSpacing = (-0.5).sp
                        )
                    },
                    actions = {
                        IconButton(onClick = { showSearch = true }) {
                            Icon(Icons.Default.Search, "Search", tint = CatSubtext)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Black)
                )
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = Surface,
                modifier = Modifier.drawBehind {
                    drawLine(
                        color = navBarTopBorder,
                        start = Offset(0f, 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = 1f
                    )
                }
            ) {
                NavItem(selectedTab == 0, { selectedTab = 0 }, Icons.Default.Shield, "Home", CatGreen)
                NavItem(selectedTab == 1, { selectedTab = 1 }, Icons.Default.Phone, "Recent", CatBlue)
                NavItem(selectedTab == 2, { selectedTab = 2 }, Icons.Default.History, "Log", CatPeach)
                NavItem(selectedTab == 3, { selectedTab = 3 }, Icons.Default.Search, "Lookup", CatYellow)
                NavItem(selectedTab == 4, { selectedTab = 4 }, Icons.Default.Block, "Blocklist", CatRed)
                NavItem(selectedTab == 5, { selectedTab = 5 }, Icons.Default.Settings, "More", CatMauve)
            }
        },
        containerColor = Black
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (showSearch && searchQuery.length >= 2) {
                // Search results overlay
                SearchResultsView(results = searchResults, onTap = { viewModel.openNumberDetail(it.number) })
            } else {
                AnimatedContent(targetState = selectedTab, transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    (slideInHorizontally { direction * it / 6 } + fadeIn(tween(200)))
                        .togetherWith(slideOutHorizontally { -direction * it / 6 } + fadeOut(tween(150)))
                }, label = "tabs") { tab ->
                    when (tab) {
                        0 -> DashboardScreen(viewModel)
                        1 -> RecentCallsScreen(viewModel)
                        2 -> BlockedLogScreen(viewModel)
                        3 -> LookupScreen()
                        4 -> BlocklistScreen(viewModel)
                        5 -> MoreScreen(viewModel)
                    }
                }
            }
        }
    }
}

@Composable
fun SearchResultsView(results: List<com.sysadmindoc.callshield.data.model.SpamNumber>, onTap: (com.sysadmindoc.callshield.data.model.SpamNumber) -> Unit) {
    if (results.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Icon(Icons.Default.SearchOff, null, tint = CatOverlay, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text("No results found", color = CatSubtext, style = MaterialTheme.typography.bodyMedium)
                Text("Try a different number or keyword", color = CatOverlay, style = MaterialTheme.typography.bodySmall)
            }
        }
    } else {
        androidx.compose.foundation.lazy.LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    "${results.size} result${if (results.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.labelMedium,
                    color = CatOverlay,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            items(results.size) { i ->
                val number = results[i]
                PremiumCard(
                    onClick = { onTap(number) },
                    cornerRadius = 14.dp,
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = CatRed, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(com.sysadmindoc.callshield.data.PhoneFormatter.format(number.number), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            Text("${number.type} - ${number.reports} reports", style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                            if (number.description.isNotEmpty()) Text(number.description, style = MaterialTheme.typography.labelSmall, color = CatOverlay, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RowScope.NavItem(
    selected: Boolean, onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String, color: androidx.compose.ui.graphics.Color
) {
    val iconTint = if (selected) {
        // Subtle brightness boost for selected icon
        color.copy(alpha = 1f).let {
            Color(
                red = (it.red * 1.12f).coerceAtMost(1f),
                green = (it.green * 1.12f).coerceAtMost(1f),
                blue = (it.blue * 1.12f).coerceAtMost(1f),
                alpha = it.alpha
            )
        }
    } else {
        color
    }

    NavigationBarItem(
        selected = selected, onClick = onClick,
        icon = { Icon(icon, null, tint = if (selected) iconTint else LocalContentColor.current) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = iconTint, selectedTextColor = color,
            indicatorColor = color.copy(alpha = 0.10f)
        )
    )
}
