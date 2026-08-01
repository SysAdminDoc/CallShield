package com.sysadmindoc.callshield.ui.screens.main

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PhoneCallback
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.SpeakerNotesOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.BlockingProfiles
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup
import com.sysadmindoc.callshield.permissions.CallShieldPermissions
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.SyncState
import com.sysadmindoc.callshield.ui.friendlyMatchReasonLabel
import com.sysadmindoc.callshield.ui.theme.CatBlue
import com.sysadmindoc.callshield.ui.theme.CatGreen
import com.sysadmindoc.callshield.ui.theme.CatMauve
import com.sysadmindoc.callshield.ui.theme.CatMuted
import com.sysadmindoc.callshield.ui.theme.CatOverlay
import com.sysadmindoc.callshield.ui.theme.CatPeach
import com.sysadmindoc.callshield.ui.theme.CatRed
import com.sysadmindoc.callshield.ui.theme.CatSubtext
import com.sysadmindoc.callshield.ui.theme.CatTeal
import com.sysadmindoc.callshield.ui.theme.CatText
import com.sysadmindoc.callshield.ui.theme.CatYellow
import com.sysadmindoc.callshield.ui.theme.GradientDivider
import com.sysadmindoc.callshield.ui.theme.PremiumActionButton
import com.sysadmindoc.callshield.ui.theme.PremiumCard
import com.sysadmindoc.callshield.ui.theme.PremiumCompactButton
import com.sysadmindoc.callshield.ui.theme.PremiumIconTile
import com.sysadmindoc.callshield.ui.theme.SectionHeader
import com.sysadmindoc.callshield.ui.theme.StatusPill
import com.sysadmindoc.callshield.ui.theme.hapticConfirm
import com.sysadmindoc.callshield.ui.theme.hapticTick
import com.sysadmindoc.callshield.util.startActivitySafely
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(viewModel: MainViewModel) {
    val totalBlocked by viewModel.totalBlocked.collectAsStateWithLifecycle()
    val blockedToday by viewModel.blockedToday.collectAsStateWithLifecycle()
    val spamCount by viewModel.spamCount.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val blockCallsEnabled by viewModel.blockCallsEnabled.collectAsStateWithLifecycle()
    val blockSmsEnabled by viewModel.blockSmsEnabled.collectAsStateWithLifecycle()
    val heuristics by viewModel.heuristicsEnabled.collectAsStateWithLifecycle()
    val smsContent by viewModel.smsContentEnabled.collectAsStateWithLifecycle()
    val stirShaken by viewModel.stirShakenEnabled.collectAsStateWithLifecycle()
    val neighborSpoof by viewModel.neighborSpoofEnabled.collectAsStateWithLifecycle()
    val mlScorer by viewModel.mlScorerEnabled.collectAsStateWithLifecycle()
    val rcsFilter by viewModel.rcsFilterEnabled.collectAsStateWithLifecycle()
    val freqEscalation by viewModel.freqEscalationEnabled.collectAsStateWithLifecycle()
    val blockedThisWeek by viewModel.blockedThisWeek.collectAsStateWithLifecycle()
    val blockedLastWeek by viewModel.blockedLastWeek.collectAsStateWithLifecycle()
    val blockedCalls by viewModel.blockedCalls.collectAsStateWithLifecycle()
    val scanResult by viewModel.scanResult.collectAsStateWithLifecycle()
    val smsScanResult by viewModel.smsScanResult.collectAsStateWithLifecycle()
    val lastSync by viewModel.lastSyncTimestamp.collectAsStateWithLifecycle()
    val lastSyncSource by viewModel.lastSyncSource.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val scanningCalls by viewModel.scanningCalls.collectAsStateWithLifecycle()
    val scanningSms by viewModel.scanningSms.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val roleManager =
        remember(context) {
            context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
        }
    var permissionRefreshTick by remember { mutableIntStateOf(0) }
    // Pending "Block area code" confirmation: (areaCode, locationLabel).
    // Saved as a two-element list so the confirm dialog survives rotation
    // (Pair itself has no Bundle saver).
    var pendingAreaBlock by rememberSaveable(
        stateSaver =
            androidx.compose.runtime.saveable.listSaver<Pair<String, String>?, String>(
                save = { it?.let { pair -> listOf(pair.first, pair.second) } ?: emptyList() },
                restore = { if (it.size == 2) it[0] to it[1] else null },
            ),
    ) { mutableStateOf<Pair<String, String>?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    permissionRefreshTick++
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val missingPerms =
        remember(
            context,
            permissionRefreshTick,
            blockCallsEnabled,
            blockSmsEnabled,
        ) {
            CallShieldPermissions.missingEnabledProtectionPermissions(
                context = context,
                callsEnabled = blockCallsEnabled,
                smsEnabled = blockSmsEnabled,
            )
        }
    val callPermissionsReady =
        remember(context, permissionRefreshTick) {
            CallShieldPermissions.hasCallProtectionPermissions(context)
        }
    val smsPermissionsReady =
        remember(context, permissionRefreshTick) {
            CallShieldPermissions.hasSmsProtectionPermissions(context)
        }
    val callLogReady =
        remember(context, permissionRefreshTick) {
            CallShieldPermissions.isPermissionGranted(context, Manifest.permission.READ_CALL_LOG)
        }
    val smsInboxReady =
        remember(context, permissionRefreshTick) {
            CallShieldPermissions.canReadSmsInbox(context)
        }
    val spamDatabaseReady = spamCount > 0
    val callScreenerReady =
        remember(roleManager, permissionRefreshTick) {
            CallShieldPermissions.hasCallScreeningRole(roleManager)
        }
    val overlayGranted =
        remember(context, permissionRefreshTick) {
            CallShieldPermissions.canDrawOverlays(context)
        }
    val notificationsGranted =
        remember(context, permissionRefreshTick) {
            CallShieldPermissions.hasNotificationPermission(context)
        }
    val corePermissionsReady = missingPerms.isEmpty()
    val dashboardStatus =
        remember(
            blockCallsEnabled,
            blockSmsEnabled,
            callPermissionsReady,
            smsPermissionsReady,
            corePermissionsReady,
            spamDatabaseReady,
            callScreenerReady,
            overlayGranted,
            notificationsGranted,
        ) {
            buildDashboardStatusModel(
                blockCallsEnabled = blockCallsEnabled,
                blockSmsEnabled = blockSmsEnabled,
                callPermissionsReady = callPermissionsReady,
                smsPermissionsReady = smsPermissionsReady,
                permissionsReady = corePermissionsReady,
                spamDatabaseReady = spamDatabaseReady,
                callScreenerReady = callScreenerReady,
                overlayGranted = overlayGranted,
                notificationsGranted = notificationsGranted,
            )
        }
    val protectionEnabled = dashboardStatus.protectionEnabled
    val callProtectionReady = dashboardStatus.callProtectionReady
    val smsProtectionReady = dashboardStatus.smsProtectionReady
    val shieldActive = dashboardStatus.shieldActive
    val requiredSetupComplete = dashboardStatus.requiredSetupComplete
    val requiredSetupTotal = dashboardStatus.requiredSetupTotal
    val heroTitle =
        when (dashboardStatus.heroMode) {
            DashboardHeroMode.Active -> stringResource(R.string.dashboard_protection_active)
            DashboardHeroMode.SetupNeeded -> stringResource(R.string.dashboard_setup_needed)
            DashboardHeroMode.Disabled -> stringResource(R.string.dashboard_protection_disabled)
        }
    val heroSubtitle =
        when {
            dashboardStatus.heroMode == DashboardHeroMode.SetupNeeded -> {
                stringResource(
                    R.string.dashboard_setup_progress,
                    requiredSetupComplete,
                    requiredSetupTotal,
                )
            }

            shieldActive && blockCallsEnabled && blockSmsEnabled -> {
                stringResource(R.string.dashboard_calls_and_texts_protected)
            }

            shieldActive && blockCallsEnabled -> {
                stringResource(R.string.dashboard_calls_protected)
            }

            shieldActive && blockSmsEnabled -> {
                stringResource(R.string.dashboard_texts_protected)
            }

            !protectionEnabled -> {
                stringResource(R.string.dashboard_turn_on_protection_hint)
            }

            !corePermissionsReady -> {
                stringResource(R.string.dashboard_finish_permissions_hint)
            }

            !spamDatabaseReady -> {
                stringResource(R.string.dashboard_finish_setup_hint)
            }

            blockCallsEnabled && !callScreenerReady -> {
                stringResource(R.string.dashboard_call_screener_missing_hint)
            }

            else -> {
                stringResource(R.string.dashboard_finish_setup_hint)
            }
        }
    val heroAction =
        when {
            !corePermissionsReady -> {
                HeroAction(
                    label = stringResource(R.string.dashboard_review_permissions),
                    icon = Icons.Default.Settings,
                    onClick = { openAppSettings(context) },
                )
            }

            !spamDatabaseReady -> {
                HeroAction(
                    label = stringResource(R.string.dashboard_sync_database),
                    icon = Icons.Default.Sync,
                    onClick = {
                        hapticTick(context)
                        viewModel.sync()
                    },
                )
            }

            blockCallsEnabled && !callScreenerReady && roleManager != null -> {
                HeroAction(
                    label = stringResource(R.string.dashboard_enable_call_screening),
                    icon = Icons.AutoMirrored.Filled.PhoneCallback,
                    onClick = { requestCallScreening(context, roleManager) },
                )
            }

            else -> {
                null
            }
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        var heroVisible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { heroVisible = true }
        val heroAlpha by animateFloatAsState(
            targetValue = if (heroVisible) 1f else 0f,
            animationSpec = tween(600, easing = FastOutSlowInEasing),
            label = "heroAlpha",
        )
        val heroScale by animateFloatAsState(
            targetValue = if (heroVisible) 1f else 0.96f,
            animationSpec = tween(600, easing = FastOutSlowInEasing),
            label = "heroScale",
        )
        DashboardHeroCard(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = heroAlpha
                        scaleX = heroScale
                        scaleY = heroScale
                    },
            dashboardStatus = dashboardStatus,
            heroTitle = heroTitle,
            heroSubtitle = heroSubtitle,
            requiredSetupComplete = requiredSetupComplete,
            requiredSetupTotal = requiredSetupTotal,
            engineCount =
                activeEngineCount(
                    stirShaken = stirShaken,
                    heuristics = heuristics,
                    smsContent = smsContent,
                    neighborSpoof = neighborSpoof,
                    mlScorer = mlScorer,
                    rcsFilter = rcsFilter,
                    freqEscalation = freqEscalation,
                ),
            lastSync = lastSync,
            lastSyncSource = lastSyncSource,
            syncState = syncState,
            heroAction = heroAction,
            onSyncDatabase = {
                hapticTick(context)
                viewModel.sync()
            },
        )

        DashboardSetupChecklistCard(
            dashboardStatus = dashboardStatus,
            corePermissionsReady = corePermissionsReady,
            syncState = syncState,
            spamDatabaseReady = spamDatabaseReady,
            spamCount = spamCount,
            blockCallsEnabled = blockCallsEnabled,
            callScreenerReady = callScreenerReady,
            overlayGranted = overlayGranted,
            notificationsGranted = notificationsGranted,
            onReviewPermissions = { openAppSettings(context) },
            onSyncDatabase = {
                hapticTick(context)
                viewModel.sync()
            },
            onEnableCallScreener =
                if (roleManager == null) {
                    null
                } else {
                    { requestCallScreening(context, roleManager) }
                },
            onEnableOverlay = { openOverlaySettings(context) },
            onEnableNotifications = { openNotificationSettings(context) },
        )

        DashboardStatsRow(
            totalBlocked = totalBlocked,
            blockedToday = blockedToday,
            blockedThisWeek = blockedThisWeek,
        )

        if (blockedThisWeek > 0 || blockedLastWeek > 0) {
            val diff = blockedThisWeek - blockedLastWeek
            val trendIcon =
                when {
                    diff > 0 -> Icons.AutoMirrored.Filled.TrendingUp
                    diff < 0 -> Icons.AutoMirrored.Filled.TrendingDown
                    else -> Icons.AutoMirrored.Filled.TrendingFlat
                }
            val trendColor =
                when {
                    diff > 0 -> CatRed
                    diff < 0 -> CatGreen
                    else -> CatSubtext
                }
            val trendText =
                when {
                    diff > 0 -> stringResource(R.string.dashboard_trend_more, diff)
                    diff < 0 -> stringResource(R.string.dashboard_trend_fewer, -diff)
                    else -> stringResource(R.string.dashboard_trend_same)
                }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(trendIcon, null, tint = trendColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(trendText, style = MaterialTheme.typography.labelSmall, color = trendColor)
            }
        }

        val lastBlocked = blockedCalls.firstOrNull { it.wasBlocked }
        if (lastBlocked != null) {
            PremiumCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 12.dp,
                onClick = { viewModel.openNumberDetail(lastBlocked.number) },
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (lastBlocked.isCall) Icons.Default.PhoneDisabled else Icons.Default.SpeakerNotesOff,
                        null,
                        tint = if (lastBlocked.isCall) CatRed else CatMauve,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(
                                R.string.dashboard_last_blocked,
                                PhoneFormatter.formatIsolated(lastBlocked.number),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "${relativeTimeText(lastBlocked.timestamp)} · ${friendlyMatchReasonLabel(lastBlocked.matchReason)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = CatOverlay,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = CatOverlay, modifier = Modifier.size(16.dp))
                }
            }
        }

        PremiumCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                SectionHeader(stringResource(R.string.dashboard_quick_controls), CatGreen)
                Spacer(Modifier.height(12.dp))
                QuickToggle(
                    icon = Icons.Default.Phone,
                    label = stringResource(R.string.dashboard_block_calls),
                    checked = blockCallsEnabled,
                ) { viewModel.setBlockCalls(it) }
                GradientDivider(modifier = Modifier.padding(vertical = 4.dp))
                QuickToggle(
                    icon = Icons.Default.Sms,
                    label = stringResource(R.string.dashboard_block_sms),
                    checked = blockSmsEnabled,
                ) { viewModel.setBlockSms(it) }
            }
        }

        PremiumCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                SectionHeader(stringResource(R.string.dashboard_quick_profiles), CatMauve)
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileChip(
                        Modifier.weight(1f),
                        stringResource(R.string.dashboard_profile_work),
                        CatBlue,
                        activeProfile == BlockingProfiles.Profile.WORK,
                    ) { viewModel.applyProfile(BlockingProfiles.Profile.WORK) }
                    ProfileChip(
                        Modifier.weight(1f),
                        stringResource(R.string.dashboard_profile_personal),
                        CatGreen,
                        activeProfile == BlockingProfiles.Profile.PERSONAL,
                    ) { viewModel.applyProfile(BlockingProfiles.Profile.PERSONAL) }
                    ProfileChip(
                        Modifier.weight(1f),
                        stringResource(R.string.dashboard_profile_sleep),
                        CatMauve,
                        activeProfile == BlockingProfiles.Profile.SLEEP,
                    ) { viewModel.applyProfile(BlockingProfiles.Profile.SLEEP) }
                }
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileChip(
                        Modifier.weight(1f),
                        stringResource(R.string.dashboard_profile_maximum),
                        CatRed,
                        activeProfile == BlockingProfiles.Profile.MAX,
                    ) { viewModel.applyProfile(BlockingProfiles.Profile.MAX) }
                    ProfileChip(
                        Modifier.weight(1f),
                        stringResource(R.string.dashboard_profile_off),
                        CatOverlay,
                        activeProfile == BlockingProfiles.Profile.OFF,
                    ) { viewModel.applyProfile(BlockingProfiles.Profile.OFF) }
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        PremiumCard(modifier = Modifier.fillMaxWidth(), accentColor = CatGreen) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SectionHeader(stringResource(R.string.dashboard_quick_actions), CatGreen)
                DashboardActionRow(
                    icon = Icons.Default.Sync,
                    title = stringResource(R.string.dashboard_sync_database),
                    subtitle =
                        if (spamDatabaseReady) {
                            pluralStringResource(
                                R.plurals.dashboard_action_sync_subtitle_ready,
                                spamCount,
                                spamCount,
                            )
                        } else {
                            stringResource(R.string.dashboard_action_sync_subtitle)
                        },
                    accentColor = CatGreen,
                    actionLabel = stringResource(R.string.dashboard_sync),
                    loading = syncState is SyncState.Syncing,
                    enabled = syncState !is SyncState.Syncing,
                ) {
                    hapticTick(context)
                    viewModel.sync()
                }
                GradientDivider()
                DashboardActionRow(
                    icon = Icons.Default.Call,
                    title = stringResource(R.string.dashboard_scan_calls),
                    subtitle =
                        if (callLogReady) {
                            stringResource(R.string.dashboard_action_scan_calls_subtitle)
                        } else {
                            stringResource(R.string.dashboard_action_calls_permissions_subtitle)
                        },
                    accentColor = CatBlue,
                    actionLabel =
                        if (callLogReady) {
                            stringResource(R.string.dashboard_action_run)
                        } else {
                            stringResource(R.string.dashboard_action_review)
                        },
                    loading = scanningCalls,
                    enabled = !scanningCalls,
                ) {
                    if (callLogReady) {
                        hapticTick(context)
                        viewModel.scanCallLog()
                    } else {
                        openAppSettings(context)
                    }
                }
                GradientDivider()
                DashboardActionRow(
                    icon = Icons.AutoMirrored.Filled.TextSnippet,
                    title = stringResource(R.string.dashboard_scan_sms_inbox),
                    subtitle =
                        if (smsInboxReady) {
                            stringResource(R.string.dashboard_action_scan_sms_subtitle)
                        } else {
                            stringResource(R.string.dashboard_action_sms_permissions_subtitle)
                        },
                    accentColor = CatMauve,
                    actionLabel =
                        if (smsInboxReady) {
                            stringResource(R.string.dashboard_action_run)
                        } else {
                            stringResource(R.string.dashboard_action_review)
                        },
                    loading = scanningSms,
                    enabled = !scanningSms,
                ) {
                    if (smsInboxReady) {
                        hapticTick(context)
                        viewModel.scanSmsInbox()
                    } else {
                        openAppSettings(context)
                    }
                }
            }
        }

        AnimatedVisibility(
            syncState is SyncState.Success ||
                syncState is SyncState.Warning ||
                syncState is SyncState.Error,
        ) {
            val accentColor =
                when (syncState) {
                    is SyncState.Success -> CatGreen
                    is SyncState.Warning -> CatYellow
                    else -> CatRed
                }
            val message =
                when (syncState) {
                    is SyncState.Success -> (syncState as SyncState.Success).message
                    is SyncState.Warning -> (syncState as SyncState.Warning).message
                    is SyncState.Error -> (syncState as SyncState.Error).message
                    else -> ""
                }
            PremiumCard(
                modifier = Modifier.fillMaxWidth(),
                accentColor = accentColor,
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (syncState is SyncState.Success) Icons.Default.CheckCircle else Icons.Default.Warning,
                        null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        message,
                        color = accentColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        scanResult?.let { result ->
            PremiumCard(
                modifier = Modifier.fillMaxWidth(),
                accentColor =
                    if (result.error != null) {
                        CatRed
                    } else if (result.spamFound > 0) {
                        CatRed
                    } else {
                        CatGreen
                    },
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    SectionHeader(stringResource(R.string.dashboard_call_log_scan), CatBlue)
                    Spacer(Modifier.height(10.dp))
                    if (result.error != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, tint = CatRed, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(result.error, color = CatRed, style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        val totalScannedText =
                            pluralStringResource(
                                R.plurals.dashboard_scan_unique_numbers_scanned,
                                result.totalScanned,
                                result.totalScanned,
                            )
                        val spamFoundText =
                            pluralStringResource(
                                R.plurals.dashboard_scan_spam_found,
                                result.spamFound,
                                result.spamFound,
                            )
                        Text(
                            stringResource(
                                R.string.dashboard_scan_result,
                                totalScannedText,
                                spamFoundText,
                            ),
                            color = if (result.spamFound > 0) CatRed else CatGreen,
                        )
                        for (spam in result.spamNumbers.take(5)) {
                            Spacer(Modifier.height(6.dp))
                            GradientDivider()
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        PhoneFormatter.formatIsolated(spam.number),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        "${spam.callCount}x | ${friendlyMatchReasonLabel(spam.matchReason)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = CatSubtext,
                                    )
                                }
                                PremiumCompactButton(
                                    label = stringResource(R.string.dashboard_block),
                                    icon = Icons.Default.Block,
                                    color = CatRed,
                                    onClick = { viewModel.blockNumber(spam.number, spam.type) },
                                )
                            }
                        }
                        if (result.spamNumbers.size > 5) {
                            Spacer(Modifier.height(4.dp))
                            val moreCount = result.spamNumbers.size - 5
                            Text(
                                pluralStringResource(R.plurals.dashboard_scan_more, moreCount, moreCount),
                                color = CatOverlay,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } // else (no error)
                }
            }
        }

        smsScanResult?.let { result ->
            PremiumCard(
                modifier = Modifier.fillMaxWidth(),
                accentColor =
                    if (result.error != null) {
                        CatRed
                    } else if (result.spamFound > 0) {
                        CatRed
                    } else {
                        CatGreen
                    },
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    SectionHeader(stringResource(R.string.dashboard_sms_inbox_scan), CatMauve)
                    Spacer(Modifier.height(10.dp))
                    if (result.error != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, tint = CatRed, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(result.error, color = CatRed, style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        val totalScannedText =
                            pluralStringResource(
                                R.plurals.dashboard_sms_scan_messages_scanned,
                                result.totalScanned,
                                result.totalScanned,
                            )
                        val spamFoundText =
                            pluralStringResource(
                                R.plurals.dashboard_scan_spam_found,
                                result.spamFound,
                                result.spamFound,
                            )
                        Text(
                            stringResource(
                                R.string.dashboard_sms_scan_result,
                                totalScannedText,
                                spamFoundText,
                            ),
                            color = if (result.spamFound > 0) CatRed else CatGreen,
                        )
                        for (sms in result.spamMessages.take(5)) {
                            Spacer(Modifier.height(6.dp))
                            GradientDivider()
                            Spacer(Modifier.height(6.dp))
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        PhoneFormatter.formatIsolated(sms.number),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(sms.body, style = MaterialTheme.typography.bodySmall, color = CatSubtext, maxLines = 1)
                                    Text(friendlyMatchReasonLabel(sms.matchReason), style = MaterialTheme.typography.labelSmall, color = CatPeach)
                                }
                                PremiumCompactButton(
                                    label = stringResource(R.string.dashboard_block),
                                    icon = Icons.Default.Block,
                                    color = CatRed,
                                    onClick = { viewModel.blockNumber(sms.number, sms.type) },
                                )
                            }
                        }
                        if (result.spamMessages.size > 5) {
                            Spacer(Modifier.height(4.dp))
                            val moreCount = result.spamMessages.size - 5
                            Text(
                                pluralStringResource(R.plurals.dashboard_scan_more, moreCount, moreCount),
                                color = CatOverlay,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } // else (no error)
                }
            }
        }

        val topAreaCodes =
            remember(blockedCalls) {
                blockedCalls
                    .mapNotNull { AreaCodeLookup.getAreaCode(it.number) }
                    .groupBy { it }
                    .mapValues { it.value.size }
                    .filter { it.value >= 5 }
                    .entries
                    .sortedByDescending { it.value }
                    .take(3)
            }
        if (topAreaCodes.isNotEmpty()) {
            PremiumCard(
                modifier = Modifier.fillMaxWidth(),
                accentColor = CatYellow,
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lightbulb, null, tint = CatYellow, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.dashboard_smart_suggestions),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    topAreaCodes.forEachIndexed { index, (ac, count) ->
                        if (index > 0) {
                            GradientDivider(modifier = Modifier.padding(vertical = 2.dp))
                        }
                        val loc = AreaCodeLookup.lookup("+1$ac") ?: ac
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.dashboard_spam_from_area, count, ac, loc),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            PremiumCompactButton(
                                label = stringResource(R.string.dashboard_block_area, ac),
                                icon = Icons.Default.FilterAlt,
                                color = CatYellow,
                                onClick = { pendingAreaBlock = ac to loc },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingAreaBlock?.let { (ac, loc) ->
        val areaRuleDescription = stringResource(R.string.dashboard_block_area_description, ac, loc)
        val areaAddedToast = stringResource(R.string.dashboard_block_area_added, ac)
        AlertDialog(
            onDismissRequest = { pendingAreaBlock = null },
            title = { Text(stringResource(R.string.dashboard_block_area_confirm_title, ac)) },
            text = { Text(stringResource(R.string.dashboard_block_area_confirm_body, ac, loc)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.addWildcardRule("+1$ac*", false, areaRuleDescription)
                    android.widget.Toast
                        .makeText(context, areaAddedToast, android.widget.Toast.LENGTH_SHORT)
                        .show()
                    pendingAreaBlock = null
                }) {
                    Text(stringResource(R.string.dashboard_block_area_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAreaBlock = null }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

@Suppress("LongMethod", "LongParameterList")
@Composable
internal fun DashboardHeroCard(
    modifier: Modifier = Modifier,
    dashboardStatus: DashboardStatusModel,
    heroTitle: String,
    heroSubtitle: String,
    requiredSetupComplete: Int,
    requiredSetupTotal: Int,
    engineCount: Int,
    lastSync: Long,
    lastSyncSource: String,
    syncState: SyncState,
    heroAction: HeroAction?,
    onSyncDatabase: () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = heroTitle,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = CatText,
        )
        Text(
            text = heroSubtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = CatSubtext,
        )
        heroAction?.let { action ->
            PremiumActionButton(
                label = action.label,
                icon = action.icon,
                color = CatGreen,
                onClick = action.onClick,
                enabled = syncState !is SyncState.Syncing || action.icon != Icons.Default.Sync,
                loading = syncState is SyncState.Syncing && action.icon == Icons.Default.Sync,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            )
        }
        if (heroAction?.icon != Icons.Default.Sync) {
            PremiumCompactButton(
                label = stringResource(R.string.dashboard_sync_database),
                icon = Icons.Default.Sync,
                color = CatGreen,
                onClick = onSyncDatabase,
                enabled = syncState !is SyncState.Syncing,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
        GradientDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DashboardReadinessMetric(
                value = "$requiredSetupComplete/$requiredSetupTotal",
                label = stringResource(R.string.dashboard_metric_core_setup),
                icon = Icons.Default.Shield,
                color = if (dashboardStatus.setupComplete) CatGreen else CatYellow,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.width(1.dp).height(60.dp).background(CatMuted))
            DashboardReadinessMetric(
                value = engineCount.toString(),
                label = stringResource(R.string.dashboard_metric_engines),
                icon = Icons.Default.Security,
                color = CatGreen,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.width(1.dp).height(60.dp).background(CatMuted))
            DashboardReadinessMetric(
                value =
                    if (lastSync > 0L || lastSyncSource == SpamRepository.SYNC_SOURCE_BUNDLED) {
                        stringResource(R.string.dashboard_metric_ready)
                    } else {
                        "—"
                    },
                label = stringResource(R.string.dashboard_metric_database),
                icon = Icons.Default.DownloadDone,
                color = syncFreshnessColor(lastSync, lastSyncSource),
                modifier = Modifier.weight(1f),
            )
        }
        GradientDivider()
    }
}

@Composable
private fun DashboardReadinessMetric(
    value: String,
    label: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, color = CatText, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = CatSubtext, maxLines = 1)
    }
}

@Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList")
@Composable
internal fun DashboardSetupChecklistCard(
    dashboardStatus: DashboardStatusModel,
    corePermissionsReady: Boolean,
    syncState: SyncState,
    spamDatabaseReady: Boolean,
    spamCount: Int,
    blockCallsEnabled: Boolean,
    callScreenerReady: Boolean,
    overlayGranted: Boolean,
    notificationsGranted: Boolean,
    onReviewPermissions: () -> Unit,
    onSyncDatabase: () -> Unit,
    onEnableCallScreener: (() -> Unit)?,
    onEnableOverlay: () -> Unit,
    onEnableNotifications: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader(stringResource(R.string.dashboard_setup_checklist))
                SetupStateBadge(
                    label =
                        if (dashboardStatus.setupComplete) {
                            stringResource(R.string.dashboard_setup_complete)
                        } else {
                            stringResource(R.string.dashboard_setup_needs_attention)
                        },
                    color = if (dashboardStatus.setupComplete) CatGreen else CatYellow,
                )
            }

            SetupChecklistRow(
                icon = Icons.Default.Security,
                title = stringResource(R.string.dashboard_setup_permissions_title),
                detail =
                    if (corePermissionsReady) {
                        stringResource(R.string.dashboard_permissions_ready_detail)
                    } else {
                        stringResource(R.string.dashboard_permissions_required_short)
                    },
                ready = corePermissionsReady,
                accentColor = CatBlue,
                actionLabel = if (corePermissionsReady) null else stringResource(R.string.dashboard_action_review),
                onAction = if (corePermissionsReady) null else onReviewPermissions,
            )

            GradientDivider()

            SetupChecklistRow(
                icon = Icons.Default.DownloadDone,
                title = stringResource(R.string.dashboard_setup_database_title),
                detail =
                    when {
                        syncState is SyncState.Syncing -> {
                            stringResource(R.string.dashboard_database_syncing_short)
                        }

                        spamDatabaseReady -> {
                            pluralStringResource(
                                R.plurals.dashboard_database_ready_detail,
                                spamCount,
                                spamCount,
                            )
                        }

                        else -> {
                            stringResource(R.string.dashboard_database_needed_short)
                        }
                    },
                ready = spamDatabaseReady,
                accentColor = CatGreen,
                actionLabel = if (spamDatabaseReady) null else stringResource(R.string.dashboard_sync),
                onAction = if (spamDatabaseReady) null else onSyncDatabase,
            )

            GradientDivider()

            SetupChecklistRow(
                icon = Icons.AutoMirrored.Filled.PhoneCallback,
                title = stringResource(R.string.dashboard_setup_call_screener_title),
                detail =
                    when {
                        !blockCallsEnabled -> stringResource(R.string.dashboard_screener_optional_short)
                        callScreenerReady -> stringResource(R.string.dashboard_screener_ready_short)
                        else -> stringResource(R.string.dashboard_screener_needed_short)
                    },
                ready = !blockCallsEnabled || callScreenerReady,
                accentColor = CatMauve,
                actionLabel =
                    if (!blockCallsEnabled || callScreenerReady || onEnableCallScreener == null) {
                        null
                    } else {
                        stringResource(R.string.dashboard_enable_call_screening)
                    },
                onAction = if (!blockCallsEnabled || callScreenerReady) null else onEnableCallScreener,
            )

            GradientDivider(modifier = Modifier.padding(top = 2.dp))

            Text(
                text = stringResource(R.string.dashboard_optional_extras),
                style = MaterialTheme.typography.labelMedium,
                color = CatOverlay,
                fontWeight = FontWeight.SemiBold,
            )

            SetupChecklistRow(
                icon = Icons.Default.Layers,
                title = stringResource(R.string.dashboard_setup_overlay_title),
                detail =
                    if (overlayGranted) {
                        stringResource(R.string.dashboard_overlay_ready_short)
                    } else {
                        stringResource(R.string.dashboard_overlay_needed_short)
                    },
                ready = overlayGranted,
                accentColor = CatTeal,
                actionLabel = if (overlayGranted) null else stringResource(R.string.dashboard_enable_overlay),
                onAction = if (overlayGranted) null else onEnableOverlay,
            )

            GradientDivider()

            SetupChecklistRow(
                icon = Icons.Default.Notifications,
                title = stringResource(R.string.dashboard_setup_notifications_title),
                detail =
                    if (notificationsGranted) {
                        stringResource(R.string.dashboard_notifications_ready_short)
                    } else {
                        stringResource(R.string.dashboard_notifications_needed_short)
                    },
                ready = notificationsGranted,
                accentColor = CatBlue,
                actionLabel = if (notificationsGranted) null else stringResource(R.string.dashboard_enable_notifications),
                onAction = if (notificationsGranted) null else onEnableNotifications,
            )
        }
    }
}

@Composable
internal fun DashboardStatsRow(
    totalBlocked: Int,
    blockedToday: Int,
    blockedThisWeek: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatCard(
            modifier = Modifier.weight(1f),
            title = stringResource(R.string.dashboard_stat_today),
            value = blockedToday.toString(),
            icon = Icons.Default.Today,
            color = CatBlue,
        )
        Box(Modifier.width(1.dp).height(72.dp).background(CatMuted))
        StatCard(
            modifier = Modifier.weight(1f),
            title = stringResource(R.string.dashboard_stat_this_week),
            value = blockedThisWeek.toString(),
            icon = Icons.Default.DateRange,
            color = CatMauve,
        )
        Box(Modifier.width(1.dp).height(72.dp).background(CatMuted))
        StatCard(
            modifier = Modifier.weight(1f),
            title = stringResource(R.string.dashboard_stat_total),
            value = totalBlocked.toString(),
            icon = Icons.Default.Block,
            color = CatPeach,
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun SetupChecklistRow(
    icon: ImageVector,
    title: String,
    detail: String,
    ready: Boolean,
    accentColor: Color,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PremiumIconTile(icon = icon, color = accentColor, size = 34.dp, iconSize = 22.dp)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = CatSubtext,
                maxLines = 2,
            )
        }
        when {
            ready -> {
                SetupStateBadge(stringResource(R.string.dashboard_status_ready), CatGreen)
            }

            actionLabel != null && onAction != null -> {
                PremiumCompactButton(
                    label = actionLabel,
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    color = accentColor,
                    onClick = onAction,
                )
            }

            else -> {
                SetupStateBadge(stringResource(R.string.dashboard_status_needed), CatYellow)
            }
        }
    }
}

@Composable
private fun SetupStateBadge(
    label: String,
    color: Color,
) {
    Text(
        text = label,
        color = color,
        modifier = Modifier.wrapContentWidth(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun DashboardActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
    actionLabel: String,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PremiumIconTile(icon = icon, color = accentColor)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
        }
        PremiumActionButton(
            label = actionLabel,
            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            color = accentColor,
            onClick = onClick,
            enabled = enabled,
            loading = loading,
        )
    }
}

/**
 * A wall clock that re-emits every minute so relative-time labels and the
 * sync-freshness color advance while the dashboard stays open, instead of
 * freezing at composition time.
 */
@Composable
internal fun rememberNowTick(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
private fun relativeTimeText(timestamp: Long): String {
    val ago = rememberNowTick() - timestamp
    return when {
        ago < 60_000 -> stringResource(R.string.dashboard_time_just_now)
        ago < 3_600_000 -> stringResource(R.string.dashboard_time_minutes_ago, (ago / 60_000).toInt())
        ago < 86_400_000 -> stringResource(R.string.dashboard_time_hours_ago, (ago / 3_600_000).toInt())
        else -> stringResource(R.string.dashboard_time_days_ago, (ago / 86_400_000).toInt())
    }
}

@Composable
private fun syncFreshnessColor(
    lastSync: Long,
    lastSyncSource: String,
): Color {
    val now = rememberNowTick()
    if (lastSyncSource == SpamRepository.SYNC_SOURCE_BUNDLED) return CatBlue
    if (lastSync <= 0L) return CatYellow
    val ago = now - lastSync
    return when {
        ago < 86_400_000 -> CatGreen
        ago < 172_800_000 -> CatYellow
        else -> CatRed
    }
}

internal data class HeroAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

private fun activeEngineCount(
    stirShaken: Boolean,
    heuristics: Boolean,
    smsContent: Boolean,
    neighborSpoof: Boolean,
    mlScorer: Boolean,
    rcsFilter: Boolean,
    freqEscalation: Boolean,
): Int =
    listOf(
        true,
        stirShaken,
        heuristics,
        smsContent,
        neighborSpoof,
        mlScorer,
        rcsFilter,
        freqEscalation,
    ).count { it }

private fun openAppSettings(context: Context) {
    context.startActivitySafely(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ),
    )
}

private fun openOverlaySettings(context: Context) {
    // Some ROMs lack the overlay-permission activity entirely — fall back to the
    // app-details screen rather than crashing.
    context.startActivitySafely(
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ),
        onFailure = { openAppSettings(context) },
    )
}

private fun openNotificationSettings(context: Context) {
    context.startActivitySafely(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        },
        onFailure = { openAppSettings(context) },
    )
}

private fun requestCallScreening(
    context: Context,
    roleManager: RoleManager,
) {
    try {
        context.startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
    } catch (_: Exception) {
        // Some OEM ROMs remove ROLE_CALL_SCREENING entirely — fall back to app settings
        // so the user can at least see what's available.
        openAppSettings(context)
    }
}

@Composable
fun QuickToggle(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val tintColor = if (checked) CatGreen else CatSubtext
    // Row-level toggleable so TalkBack reads "Block calls, switch, on" as one
    // node (the app's two most important controls were previously unlabeled).
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = {
                        hapticTick(context)
                        onChanged(it)
                    },
                ).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PremiumIconTile(icon = icon, color = tintColor, size = 38.dp, iconSize = 19.dp)
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors =
                SwitchDefaults.colors(
                    checkedTrackColor = CatGreen,
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                ),
        )
    }
}

@Composable
fun ProfileChip(
    modifier: Modifier,
    label: String,
    color: Color,
    isActive: Boolean = false,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            hapticConfirm(context)
            onClick()
        },
        // Expose the active profile as a selection state — it was conveyed only
        // by border/tint/check, invisible to TalkBack on the mode-selection row.
        modifier =
            modifier
                .height(36.dp)
                .semantics { selected = isActive },
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (isActive) color.copy(alpha = 0.6f) else color.copy(alpha = 0.2f)),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        colors =
            ButtonDefaults.outlinedButtonColors(
                contentColor = color,
                containerColor = if (isActive) color.copy(alpha = 0.12f) else Color.Transparent,
            ),
    ) {
        if (isActive) {
            Icon(Icons.Default.Check, null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun StatCard(
    modifier: Modifier,
    title: String,
    value: String,
    icon: ImageVector,
    color: Color,
) {
    val targetValue = value.toIntOrNull() ?: 0
    val animatedValue by animateIntAsState(
        targetValue = targetValue,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "counter",
    )

    Column(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(21.dp))
        Text(
            animatedValue.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = CatText,
        )
        Text(title, style = MaterialTheme.typography.bodySmall, color = CatSubtext, maxLines = 1)
    }
}
