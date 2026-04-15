package com.sysadmindoc.callshield.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
    val spamCount by viewModel.spamCount.collectAsState()
    val blockCallsEnabled by viewModel.blockCallsEnabled.collectAsState()
    val blockSmsEnabled by viewModel.blockSmsEnabled.collectAsState()

    LaunchedEffect(startTab) {
        selectedTab = startTab
    }

    // Close search when switching tabs
    LaunchedEffect(selectedTab) {
        if (showSearch) { showSearch = false; viewModel.setSearchQuery("") }
    }

    val navBarTopBorder = Color.White.copy(alpha = 0.04f)
    val currentTitle = when (selectedTab) {
        0 -> stringResource(R.string.nav_home)
        1 -> stringResource(R.string.nav_recent)
        2 -> stringResource(R.string.nav_log)
        3 -> stringResource(R.string.nav_lookup)
        4 -> stringResource(R.string.nav_blocklist)
        else -> stringResource(R.string.nav_more)
    }
    val currentSubtitle = when (selectedTab) {
        0 -> stringResource(R.string.app_shell_subtitle_home)
        1 -> stringResource(R.string.app_shell_subtitle_recent)
        2 -> stringResource(R.string.app_shell_subtitle_log)
        3 -> stringResource(R.string.app_shell_subtitle_lookup)
        4 -> stringResource(R.string.app_shell_subtitle_blocklist)
        else -> stringResource(R.string.app_shell_subtitle_more)
    }
    val currentAccent = when (selectedTab) {
        0 -> CatGreen
        1 -> CatBlue
        2 -> CatPeach
        3 -> CatYellow
        4 -> CatRed
        else -> CatMauve
    }
    val protectionStatusLabel = when {
        blockCallsEnabled && blockSmsEnabled -> stringResource(R.string.app_shell_status_calls_texts)
        blockCallsEnabled -> stringResource(R.string.app_shell_status_calls)
        blockSmsEnabled -> stringResource(R.string.app_shell_status_texts)
        else -> stringResource(R.string.app_shell_status_paused)
    }

    Scaffold(
        topBar = {
            AppChrome(
                showSearch = showSearch,
                title = currentTitle,
                subtitle = currentSubtitle,
                accentColor = currentAccent,
                protectionStatusLabel = protectionStatusLabel,
                spamCount = spamCount,
                searchQuery = searchQuery,
                onSearchQueryChange = viewModel::setSearchQuery,
                onOpenSearch = { showSearch = true },
                onCloseSearch = {
                    showSearch = false
                    viewModel.setSearchQuery("")
                }
            )
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
            if (showSearch) {
                if (searchQuery.trim().length >= 2) {
                    SearchResultsView(
                        results = searchResults,
                        onTap = {
                            showSearch = false
                            viewModel.setSearchQuery("")
                            viewModel.openNumberDetail(it.number)
                        }
                    )
                } else {
                    SearchIdleView()
                }
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
                        3 -> LookupScreen(viewModel)
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
            items(
                items = results,
                key = { it.number }
            ) { number ->
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
private fun AppChrome(
    showSearch: Boolean,
    title: String,
    subtitle: String,
    accentColor: Color,
    protectionStatusLabel: String,
    spamCount: Int,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    Surface(color = Black) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            if (showSearch) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        focusManager.clearFocus(force = true)
                        keyboard?.hide()
                        onCloseSearch()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_close_search),
                            tint = CatSubtext
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    SearchField(
                        query = searchQuery,
                        accentColor = accentColor,
                        onValueChange = onSearchQueryChange
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.search_hint_min_chars),
                    style = MaterialTheme.typography.bodySmall,
                    color = CatOverlay
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            title,
                            color = accentColor,
                            style = MaterialTheme.typography.headlineSmall,
                            letterSpacing = (-0.5).sp
                        )
                        Text(
                            subtitle,
                            color = CatSubtext,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    IconButton(onClick = onOpenSearch) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = stringResource(R.string.cd_search),
                            tint = CatSubtext
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HeaderPill(
                        text = protectionStatusLabel,
                        accentColor = accentColor,
                        modifier = Modifier.weight(1f)
                    )
                    HeaderPill(
                        text = if (spamCount > 0) {
                            stringResource(R.string.app_shell_status_numbers, spamCount)
                        } else {
                            stringResource(R.string.app_shell_status_setup_needed)
                        },
                        accentColor = if (spamCount > 0) CatBlue else CatPeach,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            GradientDivider(color = accentColor)
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    accentColor: Color,
    onValueChange: (String) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    OutlinedTextField(
        value = query,
        onValueChange = onValueChange,
        placeholder = {
            Text(
                stringResource(R.string.search_placeholder),
                color = CatOverlay
            )
        },
        singleLine = true,
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                contentDescription = stringResource(R.string.cd_search),
                tint = accentColor
            )
        },
        trailingIcon = {
            if (query.isNotBlank()) {
                IconButton(onClick = {
                    onValueChange("")
                    focusManager.clearFocus(force = false)
                    keyboard?.show()
                }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_close),
                        tint = CatOverlay
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = accentColor.copy(alpha = 0.65f),
            unfocusedBorderColor = CatOverlay.copy(alpha = 0.3f),
            cursorColor = accentColor
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() })
    )
}

@Composable
private fun HeaderPill(
    text: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = accentColor.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.18f))
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = accentColor
        )
    }
}

@Composable
private fun SearchIdleView() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PremiumCard(accentColor = CatBlue, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = CatBlue,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            stringResource(R.string.search_idle_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = CatText
                        )
                        Text(
                            stringResource(R.string.search_idle_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = CatSubtext
                        )
                    }
                }
                GradientDivider(color = CatBlue)
                SearchHintRow(
                    icon = Icons.Default.Phone,
                    title = stringResource(R.string.search_idle_hint_exact_title),
                    subtitle = stringResource(R.string.search_idle_hint_exact_body),
                    accentColor = CatGreen
                )
                SearchHintRow(
                    icon = Icons.Default.Description,
                    title = stringResource(R.string.search_idle_hint_reason_title),
                    subtitle = stringResource(R.string.search_idle_hint_reason_body),
                    accentColor = CatPeach
                )
                SearchHintRow(
                    icon = Icons.Default.AutoAwesome,
                    title = stringResource(R.string.search_idle_hint_partial_title),
                    subtitle = stringResource(R.string.search_idle_hint_partial_body),
                    accentColor = CatMauve
                )
            }
        }
    }
}

@Composable
private fun SearchHintRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = accentColor.copy(alpha = 0.12f)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.padding(10.dp)
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = CatText
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = CatSubtext
            )
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
