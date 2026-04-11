package com.sysadmindoc.callshield.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.ui.screens.details.NumberDetailScreen
import com.sysadmindoc.callshield.ui.screens.main.BlockedLogScreen
import com.sysadmindoc.callshield.ui.screens.main.BlocklistScreen
import com.sysadmindoc.callshield.ui.screens.main.DashboardScreen
import com.sysadmindoc.callshield.ui.screens.onboarding.OnboardingScreen
import com.sysadmindoc.callshield.ui.screens.lookup.LookupScreen
import com.sysadmindoc.callshield.ui.screens.recent.RecentCallsScreen
import com.sysadmindoc.callshield.ui.screens.more.MoreScreen
import com.sysadmindoc.callshield.ui.theme.*

data class LaunchRequest(
    val id: Int,
    val deepLinkNumber: String? = null,
    val shortcutAction: String? = null
)

class MainActivity : ComponentActivity() {
    private var launchRequest by mutableStateOf(LaunchRequest(id = 0))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        launchRequest = intent.toLaunchRequest(nextId = 1)

        setContent { CallShieldTheme { CallShieldRoot(launchRequest = launchRequest) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchRequest = intent.toLaunchRequest(nextId = launchRequest.id + 1)
    }
}

@Composable
fun CallShieldRoot(viewModel: MainViewModel = viewModel(), launchRequest: LaunchRequest = LaunchRequest(id = 0)) {
    val onboardingDone by viewModel.onboardingDone.collectAsState()
    val selectedNumber by viewModel.selectedNumber.collectAsState()

    // Handle deep link and shortcuts
    var initialTab by remember { mutableIntStateOf(0) }
    LaunchedEffect(launchRequest.id) {
        launchRequest.deepLinkNumber?.let { viewModel.openNumberDetail(it) }
        when (launchRequest.shortcutAction) {
            "com.sysadmindoc.callshield.LOOKUP" -> initialTab = 3
            "com.sysadmindoc.callshield.SCAN" -> { initialTab = 0; viewModel.scanCallLog() }
            "com.sysadmindoc.callshield.SCAN_SMS" -> { initialTab = 0; viewModel.scanSmsInbox() }
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

    LaunchedEffect(startTab) {
        selectedTab = startTab
    }

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
                            placeholder = { Text(stringResource(R.string.search_placeholder), color = CatOverlay) },
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
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_close_search), tint = CatSubtext)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Black)
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.app_name),
                            color = CatGreen,
                            letterSpacing = (-0.5).sp
                        )
                    },
                    actions = {
                        IconButton(onClick = { showSearch = true }) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.cd_search), tint = CatSubtext)
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
                NavItem(selectedTab == 0, { selectedTab = 0 }, Icons.Default.Shield, stringResource(R.string.nav_home), CatGreen)
                NavItem(selectedTab == 1, { selectedTab = 1 }, Icons.Default.Phone, stringResource(R.string.nav_recent), CatBlue)
                NavItem(selectedTab == 2, { selectedTab = 2 }, Icons.Default.History, stringResource(R.string.nav_log), CatPeach)
                NavItem(selectedTab == 3, { selectedTab = 3 }, Icons.Default.Search, stringResource(R.string.nav_lookup), CatYellow)
                NavItem(selectedTab == 4, { selectedTab = 4 }, Icons.Default.Block, stringResource(R.string.nav_blocklist), CatRed)
                NavItem(selectedTab == 5, { selectedTab = 5 }, Icons.Default.Settings, stringResource(R.string.nav_more), CatMauve)
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
                Icon(Icons.Default.SearchOff, contentDescription = stringResource(R.string.cd_search_no_results), tint = CatOverlay, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.search_no_results), color = CatSubtext, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.search_try_different), color = CatOverlay, style = MaterialTheme.typography.bodySmall)
            }
        }
    } else {
        androidx.compose.foundation.lazy.LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    androidx.compose.ui.platform.LocalContext.current.resources.getQuantityString(R.plurals.search_results_count, results.size, results.size),
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
                        Icon(Icons.Default.Warning, contentDescription = stringResource(R.string.cd_search_result_spam), tint = CatRed, modifier = Modifier.size(24.dp))
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
        icon = { Icon(icon, contentDescription = label, tint = if (selected) iconTint else LocalContentColor.current) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = iconTint, selectedTextColor = color,
            indicatorColor = color.copy(alpha = 0.10f)
        )
    )
}

private fun Intent?.toLaunchRequest(nextId: Int): LaunchRequest {
    if (this == null) {
        return LaunchRequest(id = nextId)
    }

    val deepLinkNumber = getStringExtra("open_number")
        ?: data?.schemeSpecificPart?.takeIf {
            action == Intent.ACTION_VIEW && data?.scheme == "tel"
        }

    return LaunchRequest(
        id = nextId,
        deepLinkNumber = deepLinkNumber,
        shortcutAction = action
    )
}
