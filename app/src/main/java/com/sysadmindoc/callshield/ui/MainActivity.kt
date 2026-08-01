package com.sysadmindoc.callshield.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.service.ProtectionHealthWorker
import com.sysadmindoc.callshield.ui.screens.activity.ActivityScreen
import com.sysadmindoc.callshield.ui.screens.details.NumberDetailScreen
import com.sysadmindoc.callshield.ui.screens.lookup.LookupScreen
import com.sysadmindoc.callshield.ui.screens.main.BlocklistScreen
import com.sysadmindoc.callshield.ui.screens.main.DashboardScreen
import com.sysadmindoc.callshield.ui.screens.more.MoreScreen
import com.sysadmindoc.callshield.ui.screens.onboarding.OnboardingScreen
import com.sysadmindoc.callshield.ui.theme.*
import com.sysadmindoc.callshield.util.hasMinAsciiDigits
import com.sysadmindoc.callshield.util.normalizePhoneNumberInput
import dagger.hilt.android.AndroidEntryPoint

data class LaunchRequest(
    val id: Int,
    val deepLinkNumber: String? = null,
    val shortcutAction: String? = null,
)

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private var launchRequest by mutableStateOf(LaunchRequest(id = 0))

    override fun onCreate(savedInstanceState: Bundle?) {
        // The manifest theme pins a black WINDOW background; Compose's cached-
        // theme first frame (v1.7.26) doesn't cover the window itself, so
        // Light/Graphite users still saw black flashes on IME resize and
        // transitions. Swap the window theme from the synchronous mirror
        // before any view is created.
        applyCachedWindowTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The framework re-attaches the ORIGINAL launch intent on every
        // recreation (rotation, theme change, split-screen) — replacing
        // `intent` via setIntent cannot prevent the replay, because
        // ActivityThread re-attaches from its own record. A saved-state flag
        // is the only reliable "already consumed" signal: when present, the
        // deep link / shortcut was handled in a previous incarnation and must
        // not re-run a scan, re-open a closed detail screen, or yank the
        // user's tab back to the shortcut target.
        launchRequest =
            if (savedInstanceState?.getBoolean(KEY_LAUNCH_CONSUMED) == true) {
                LaunchRequest(id = 0)
            } else {
                intent.toLaunchRequest(nextId = 1)
            }
        consumeLaunchIntent()

        setContent { CallShieldRoot(launchRequest = launchRequest) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LAUNCH_CONSUMED, true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchRequest = intent.toLaunchRequest(nextId = launchRequest.id + 1)
        consumeLaunchIntent()
    }

    /**
     * Strip the consumed launch payload (deep-link number, shortcut action)
     * after it has been folded into [launchRequest]. Defense-in-depth next to
     * the [KEY_LAUNCH_CONSUMED] saved-state flag (which is what actually
     * survives recreation — see onCreate).
     */
    private fun consumeLaunchIntent() {
        intent = Intent(this, MainActivity::class.java)
    }

    private companion object {
        const val KEY_LAUNCH_CONSUMED = "callshield_launch_consumed"
    }

    override fun onResume() {
        super.onResume()
        // Re-evaluate protection health whenever the user returns to the app —
        // this is the moment they come back from the role-request dialog or OS
        // settings, and it clears the "Call screening is off" alert immediately
        // instead of leaving it in the shade until the daily periodic check.
        ProtectionHealthWorker.checkNow(this)
    }
}

@Composable
fun CallShieldRoot(
    viewModel: MainViewModel = viewModel(),
    launchRequest: LaunchRequest = LaunchRequest(id = 0),
) {
    val onboardingDone by viewModel.onboardingDone.collectAsStateWithLifecycle()
    val selectedNumber by viewModel.selectedNumber.collectAsStateWithLifecycle()
    val appTheme by viewModel.appTheme.collectAsStateWithLifecycle()

    // Handle deep link and shortcuts. The target tab is derived synchronously
    // from the request — computing it inside the effect raced the child's
    // LaunchedEffect(tabRequestId), which captured the pre-update startTab and
    // never re-ran, so the Lookup shortcut landed on Home.
    val initialTab =
        when (launchRequest.shortcutAction) {
            "com.sysadmindoc.callshield.LOOKUP" -> 2
            else -> 0
        }
    LaunchedEffect(launchRequest.id) {
        launchRequest.deepLinkNumber?.let { viewModel.openNumberDetail(it) }
        when (launchRequest.shortcutAction) {
            "com.sysadmindoc.callshield.SCAN" -> viewModel.scanCallLog()
            "com.sysadmindoc.callshield.SCAN_SMS" -> viewModel.scanSmsInbox()
        }
    }

    // Opening a number detail replaces the tab shell rather than stacking on
    // top of it, so CallShieldApp leaves composition entirely. rememberSaveable
    // only survives host recreation, not leaving composition, which meant every
    // detail visit reset the selected tab, search state, and all per-tab state.
    // Holding both branches in a SaveableStateHolder preserves each subtree's
    // saveable state across the switch (the same mechanism the tab row uses).
    val rootStateHolder = rememberSaveableStateHolder()

    CallShieldTheme(themeMode = appTheme) {
        when {
            onboardingDone == null -> {
                // First DataStore emission not yet resolved — neutral surface,
                // no wrong-content flash in either direction.
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
            }

            onboardingDone == false -> {
                OnboardingScreen(onComplete = { viewModel.completeOnboarding() })
            }

            selectedNumber != null -> {
                rootStateHolder.SaveableStateProvider(ROOT_STATE_DETAIL) {
                    NumberDetailScreen(
                        number = selectedNumber!!,
                        viewModel = viewModel,
                        onBack = { viewModel.closeNumberDetail() },
                    )
                }
            }

            else -> {
                rootStateHolder.SaveableStateProvider(ROOT_STATE_APP) {
                    CallShieldApp(
                        viewModel = viewModel,
                        startTab = initialTab,
                        tabRequestId = launchRequest.id.takeIf { launchRequest.shortcutAction != null },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
fun CallShieldApp(
    viewModel: MainViewModel,
    startTab: Int = 0,
    tabRequestId: Int? = null,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(startTab) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var moreView by rememberSaveable { mutableIntStateOf(0) }
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()

    // Apply a shortcut's target tab once per request. Without the handled-id
    // guard this re-fires whenever CallShieldApp re-enters composition (e.g.
    // returning from a number detail) and drags the user back to the shortcut's
    // tab after they had navigated away.
    var handledTabRequest by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(tabRequestId) {
        if (tabRequestId != null && tabRequestId != handledTabRequest) {
            handledTabRequest = tabRequestId
            selectedTab = startTab
        }
    }

    // Close search when switching tabs
    LaunchedEffect(selectedTab) {
        if (showSearch) {
            showSearch = false
            viewModel.setSearchQuery("")
        }
    }

    val navBarTopBorder = DividerColor
    val currentTitle =
        when (selectedTab) {
            0 -> {
                stringResource(R.string.app_name)
            }

            1 -> {
                stringResource(R.string.nav_activity)
            }

            2 -> {
                stringResource(R.string.nav_lookup)
            }

            3 -> {
                stringResource(R.string.nav_blocklist)
            }

            else -> {
                when (moreView) {
                    1 -> stringResource(R.string.more_statistics)
                    2 -> stringResource(R.string.more_settings)
                    3 -> stringResource(R.string.more_whats_new)
                    4 -> stringResource(R.string.more_protection_test)
                    else -> stringResource(R.string.nav_more)
                }
            }
        }
    // Keep the shell unmistakably CallShield. Screen content can still use
    // semantic warning/error colours, but navigation should not become a
    // six-colour rainbow as users move through the app.
    Scaffold(
        topBar = {
            AppChrome(
                showSearch = showSearch,
                title = currentTitle,
                accentColor = CatGreen,
                searchQuery = searchQuery,
                onSearchQueryChange = viewModel::setSearchQuery,
                onOpenSearch = { showSearch = true },
                onCloseSearch = {
                    showSearch = false
                    viewModel.setSearchQuery("")
                },
                onBack = if (selectedTab == 4 && moreView != 0) ({ moreView = 0 }) else null,
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = Surface,
                modifier =
                    Modifier.drawBehind {
                        drawLine(
                            color = navBarTopBorder,
                            start = Offset(0f, 0f),
                            end = Offset(size.width, 0f),
                            strokeWidth = 1f,
                        )
                    },
            ) {
                NavItem(
                    selectedTab == 0,
                    { selectedTab = 0 },
                    Icons.Default.Shield,
                    stringResource(R.string.nav_home),
                    CatGreen,
                )
                NavItem(
                    selectedTab == 1,
                    { selectedTab = 1 },
                    Icons.Default.History,
                    stringResource(R.string.nav_activity),
                    CatGreen,
                )
                NavItem(
                    selectedTab == 2,
                    { selectedTab = 2 },
                    Icons.Default.Search,
                    stringResource(R.string.nav_lookup),
                    CatGreen,
                )
                NavItem(
                    selectedTab == 3,
                    { selectedTab = 3 },
                    Icons.Default.Tune,
                    stringResource(R.string.nav_blocklist),
                    CatGreen,
                )
                NavItem(
                    selectedTab == 4,
                    { selectedTab = 4 },
                    Icons.Default.MoreHoriz,
                    stringResource(R.string.nav_more),
                    CatGreen,
                )
            }
        },
        containerColor = Black,
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
                        },
                    )
                } else {
                    SearchIdleView()
                }
            } else {
                // Each tab's composition state (scroll positions, filter
                // toggles, typed lookup input, rememberSaveable values) lives
                // in the SaveableStateHolder keyed by tab index. Without it,
                // AnimatedContent discards a tab's whole saveable registry
                // the moment it animates out — so switching Home → Recent →
                // Home reset scroll, replayed entrance animations, and wiped
                // filters/typed input on every revisit.
                val tabStateHolder = rememberSaveableStateHolder()
                AnimatedContent(targetState = selectedTab, transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    (slideInHorizontally { direction * it / 6 } + fadeIn(tween(200)))
                        .togetherWith(slideOutHorizontally { -direction * it / 6 } + fadeOut(tween(150)))
                }, label = "tabs") { tab ->
                    tabStateHolder.SaveableStateProvider(tab) {
                        when (tab) {
                            0 -> DashboardScreen(viewModel)
                            1 -> ActivityScreen(viewModel)
                            2 -> LookupScreen(viewModel)
                            3 -> BlocklistScreen(viewModel)
                            4 -> MoreScreen(viewModel, currentView = moreView, onViewChange = { moreView = it })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SearchResultsView(
    results: List<com.sysadmindoc.callshield.data.model.SpamNumber>,
    onTap: (com.sysadmindoc.callshield.data.model.SpamNumber) -> Unit,
) {
    if (results.isEmpty()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PremiumStateCard(
                icon = Icons.Default.SearchOff,
                title = stringResource(R.string.search_no_results),
                body = stringResource(R.string.search_try_different),
                accentColor = CatPeach,
                modifier = Modifier.fillMaxWidth(),
            )
            PremiumCard(accentColor = CatPeach, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SectionHeader(stringResource(R.string.search_idle_title), CatPeach)
                    SearchHintRow(
                        icon = Icons.Default.Phone,
                        title = stringResource(R.string.search_idle_hint_exact_title),
                        subtitle = stringResource(R.string.search_idle_hint_exact_body),
                        accentColor = CatGreen,
                    )
                    SearchHintRow(
                        icon = Icons.Default.Description,
                        title = stringResource(R.string.search_idle_hint_reason_title),
                        subtitle = stringResource(R.string.search_idle_hint_reason_body),
                        accentColor = CatPeach,
                    )
                }
            }
        }
    } else {
        androidx.compose.foundation.lazy.LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                StatusPill(
                    text =
                        pluralStringResource(
                            R.plurals.search_results_count,
                            results.size,
                            results.size,
                        ),
                    color = CatBlue,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            items(
                items = results,
                key = { it.number },
            ) { number ->
                PremiumCard(
                    onClick = { onTap(number) },
                    cornerRadius = 12.dp,
                    modifier = Modifier.padding(vertical = 2.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        PremiumIconTile(
                            icon = Icons.Default.Warning,
                            contentDescription = stringResource(R.string.cd_search_result_spam),
                            color = CatRed,
                            size = 42.dp,
                            iconSize = 20.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                com.sysadmindoc.callshield.data.PhoneFormatter
                                    .formatIsolated(number.number),
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            )
                            Text(
                                pluralStringResource(
                                    R.plurals.search_result_type_reports,
                                    number.reports,
                                    number.type,
                                    number.reports,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = CatSubtext,
                            )
                            if (number.description.isNotEmpty()) {
                                Text(
                                    number.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CatSubtext,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Suppress("LongMethod", "LongParameterList")
@Composable
private fun AppChrome(
    showSearch: Boolean,
    title: String,
    accentColor: Color,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    Surface(color = Black) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
        ) {
            if (showSearch) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = {
                        focusManager.clearFocus(force = true)
                        keyboard?.hide()
                        onCloseSearch()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_close_search),
                            tint = CatText,
                        )
                    }
                    SearchField(
                        query = searchQuery,
                        accentColor = accentColor,
                        onValueChange = onSearchQueryChange,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                                tint = CatText,
                            )
                        }
                    } else {
                        Image(
                            painter = painterResource(R.drawable.ic_callshield_brand_art),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        title,
                        modifier = Modifier.weight(1f),
                        color = CatText,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    IconButton(onClick = onOpenSearch) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = stringResource(R.string.cd_search),
                            tint = CatText,
                        )
                    }
                }
            }
            HorizontalDivider(color = DividerColor)
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

    TextField(
        value = query,
        onValueChange = onValueChange,
        placeholder = {
            Text(
                stringResource(R.string.search_placeholder),
                color = CatOverlay,
            )
        },
        singleLine = true,
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                contentDescription = stringResource(R.string.cd_search),
                tint = accentColor,
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
                        tint = CatOverlay,
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ShapeXl),
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = SurfaceVariant,
                unfocusedContainerColor = SurfaceVariant,
                focusedTextColor = CatText,
                unfocusedTextColor = CatText,
                unfocusedLeadingIconColor = CatOverlay,
                unfocusedTrailingIconColor = CatOverlay,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = accentColor,
            ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
    )
}

@Composable
private fun SearchIdleView() {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PremiumCard(accentColor = CatBlue, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PremiumIconTile(
                        icon = Icons.Default.Search,
                        color = CatBlue,
                        size = 42.dp,
                        iconSize = 20.dp,
                    )
                    Column {
                        Text(
                            stringResource(R.string.search_idle_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = CatText,
                        )
                        Text(
                            stringResource(R.string.search_idle_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = CatSubtext,
                        )
                    }
                }
                GradientDivider(color = CatBlue)
                SearchHintRow(
                    icon = Icons.Default.Phone,
                    title = stringResource(R.string.search_idle_hint_exact_title),
                    subtitle = stringResource(R.string.search_idle_hint_exact_body),
                    accentColor = CatGreen,
                )
                SearchHintRow(
                    icon = Icons.Default.Description,
                    title = stringResource(R.string.search_idle_hint_reason_title),
                    subtitle = stringResource(R.string.search_idle_hint_reason_body),
                    accentColor = CatPeach,
                )
                SearchHintRow(
                    icon = Icons.Default.AutoAwesome,
                    title = stringResource(R.string.search_idle_hint_partial_title),
                    subtitle = stringResource(R.string.search_idle_hint_partial_body),
                    accentColor = CatMauve,
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
        verticalAlignment = Alignment.Top,
    ) {
        PremiumIconTile(icon = icon, color = accentColor, size = 40.dp, iconSize = 18.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = CatText,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = CatSubtext,
            )
        }
    }
}

@Composable
fun RowScope.NavItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: androidx.compose.ui.graphics.Color,
) {
    val iconTint =
        if (selected) {
            // Subtle brightness boost for selected icon
            color.copy(alpha = 1f).let {
                Color(
                    red = (it.red * 1.12f).coerceAtMost(1f),
                    green = (it.green * 1.12f).coerceAtMost(1f),
                    blue = (it.blue * 1.12f).coerceAtMost(1f),
                    alpha = it.alpha,
                )
            }
        } else {
            color
        }

    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        // Icon is decorative — the label names the tab for accessibility, so
        // a matching icon contentDescription makes TalkBack announce it twice.
        icon = { Icon(icon, contentDescription = null, tint = if (selected) iconTint else LocalContentColor.current) },
        label = {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
        },
        colors =
            NavigationBarItemDefaults.colors(
                selectedIconColor = iconTint,
                selectedTextColor = color,
                indicatorColor = Color.Transparent,
            ),
        // Five 200%-scaled labels cannot fit a compact phone navigation bar.
        // Material still exposes every label to accessibility services while
        // showing only the selected label at large font scales.
        alwaysShowLabel = LocalDensity.current.fontScale < 1.5f,
    )
}

/**
 * Swap the window theme to match the user's cached app theme so the window
 * behind Compose (visible during IME resize, transitions, overscroll) isn't
 * hard-black for Light/Graphite users. Must run before super.onCreate.
 */
internal fun applyCachedWindowTheme(activity: android.app.Activity) {
    val mode =
        com.sysadmindoc.callshield.ui.theme.AppThemeMode
            .fromStorage(
                com.sysadmindoc.callshield.data.SpamRepository
                    .cachedAppTheme(activity),
            )
    val useDark =
        com.sysadmindoc.callshield.ui.theme.syncApplicationNightMode(
            activity,
            mode,
        )
    when (mode) {
        com.sysadmindoc.callshield.ui.theme.AppThemeMode.Light -> {
            activity.setTheme(R.style.Theme_CallShield_Light)
        }

        com.sysadmindoc.callshield.ui.theme.AppThemeMode.Graphite -> {
            activity.setTheme(R.style.Theme_CallShield_Graphite)
        }

        com.sysadmindoc.callshield.ui.theme.AppThemeMode.System -> {
            activity.setTheme(
                if (useDark) R.style.Theme_CallShield_Graphite else R.style.Theme_CallShield_Light,
            )
        }

        com.sysadmindoc.callshield.ui.theme.AppThemeMode.Amoled -> {
            activity.setTheme(R.style.Theme_CallShield_Amoled)
        }
    }
}

internal fun Intent?.toLaunchRequest(nextId: Int): LaunchRequest {
    if (this == null) {
        return LaunchRequest(id = nextId)
    }

    val rawDeepLinkNumber =
        getStringExtra("open_number")
            ?: data?.schemeSpecificPart?.takeIf {
                action == Intent.ACTION_VIEW && data?.scheme == "tel"
            }
    val deepLinkNumber =
        rawDeepLinkNumber
            ?.let(::normalizePhoneNumberInput)
            ?.takeIf(::hasMinAsciiDigits)

    return LaunchRequest(
        id = nextId,
        deepLinkNumber = deepLinkNumber,
        // Only CallShield's own shortcut actions are launch requests. Carrying
        // the raw action meant a plain launcher start (ACTION_MAIN) produced a
        // non-null shortcutAction — so every recreation force-reset the
        // selected tab to the "shortcut target" (Home), defeating the tab's
        // rememberSaveable.
        shortcutAction = action?.takeIf { it in KNOWN_SHORTCUT_ACTIONS },
    )
}

private val KNOWN_SHORTCUT_ACTIONS =
    setOf(
        "com.sysadmindoc.callshield.LOOKUP",
        "com.sysadmindoc.callshield.SCAN",
        "com.sysadmindoc.callshield.SCAN_SMS",
    )

// Stable SaveableStateHolder keys for the two top-level branches. They must not
// change: the holder maps saved subtree state by these strings.
private const val ROOT_STATE_APP = "root_tabs"
private const val ROOT_STATE_DETAIL = "root_number_detail"
