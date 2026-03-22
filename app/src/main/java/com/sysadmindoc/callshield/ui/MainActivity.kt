package com.sysadmindoc.callshield.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sysadmindoc.callshield.ui.screens.details.NumberDetailScreen
import com.sysadmindoc.callshield.ui.screens.main.BlockedLogScreen
import com.sysadmindoc.callshield.ui.screens.main.BlocklistScreen
import com.sysadmindoc.callshield.ui.screens.main.DashboardScreen
import com.sysadmindoc.callshield.ui.screens.onboarding.OnboardingScreen
import com.sysadmindoc.callshield.ui.screens.recent.RecentCallsScreen
import com.sysadmindoc.callshield.ui.screens.settings.SettingsScreen
import com.sysadmindoc.callshield.ui.screens.stats.StatsScreen
import com.sysadmindoc.callshield.ui.theme.*

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions()
        setContent { CallShieldTheme { CallShieldRoot() } }
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
fun CallShieldRoot(viewModel: MainViewModel = viewModel()) {
    val onboardingDone by viewModel.onboardingDone.collectAsState()
    val selectedNumber by viewModel.selectedNumber.collectAsState()

    when {
        !onboardingDone -> OnboardingScreen(onComplete = { viewModel.completeOnboarding() })
        selectedNumber != null -> NumberDetailScreen(
            number = selectedNumber!!,
            viewModel = viewModel,
            onBack = { viewModel.closeNumberDetail() }
        )
        else -> CallShieldApp(viewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallShieldApp(viewModel: MainViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CallShield", color = CatGreen) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Black)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Surface) {
                NavItem(selectedTab == 0, { selectedTab = 0 }, Icons.Default.Shield, "Home", CatGreen)
                NavItem(selectedTab == 1, { selectedTab = 1 }, Icons.Default.Phone, "Recent", CatBlue)
                NavItem(selectedTab == 2, { selectedTab = 2 }, Icons.Default.History, "Log", CatPeach)
                NavItem(selectedTab == 3, { selectedTab = 3 }, Icons.Default.Block, "Blocklist", CatRed)
                NavItem(selectedTab == 4, { selectedTab = 4 }, Icons.Default.BarChart, "Stats", CatYellow)
                NavItem(selectedTab == 5, { selectedTab = 5 }, Icons.Default.Settings, "Settings", CatMauve)
            }
        },
        containerColor = Black
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            AnimatedContent(targetState = selectedTab, transitionSpec = {
                fadeIn() togetherWith fadeOut()
            }, label = "tabs") { tab ->
                when (tab) {
                    0 -> DashboardScreen(viewModel)
                    1 -> RecentCallsScreen(viewModel)
                    2 -> BlockedLogScreen(viewModel)
                    3 -> BlocklistScreen(viewModel)
                    4 -> StatsScreen(viewModel)
                    5 -> SettingsScreen(viewModel)
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
    NavigationBarItem(
        selected = selected, onClick = onClick,
        icon = { Icon(icon, null) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = color, selectedTextColor = color,
            indicatorColor = color.copy(alpha = 0.15f)
        )
    )
}
