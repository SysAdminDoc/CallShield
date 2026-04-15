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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShieldMoon
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.SpeakerNotesOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.BlockingProfiles
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup
import com.sysadmindoc.callshield.permissions.CallShieldPermissions
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.SyncState
import com.sysadmindoc.callshield.ui.theme.Black
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
import com.sysadmindoc.callshield.ui.theme.PremiumCard
import com.sysadmindoc.callshield.ui.theme.SectionHeader
import com.sysadmindoc.callshield.ui.theme.StatusPill
import com.sysadmindoc.callshield.ui.theme.accentGlow
import com.sysadmindoc.callshield.ui.theme.hapticConfirm
import com.sysadmindoc.callshield.ui.theme.hapticTick

@Composable
fun DashboardScreen(viewModel: MainViewModel) {
    val totalBlocked by viewModel.totalBlocked.collectAsState()
    val blockedToday by viewModel.blockedToday.collectAsState()
    val spamCount by viewModel.spamCount.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val blockCallsEnabled by viewModel.blockCallsEnabled.collectAsState()
    val blockSmsEnabled by viewModel.blockSmsEnabled.collectAsState()
    val aggressiveMode by viewModel.aggressiveModeEnabled.collectAsState()
    val heuristics by viewModel.heuristicsEnabled.collectAsState()
    val smsContent by viewModel.smsContentEnabled.collectAsState()
    val stirShaken by viewModel.stirShakenEnabled.collectAsState()
    val neighborSpoof by viewModel.neighborSpoofEnabled.collectAsState()
    val mlScorer by viewModel.mlScorerEnabled.collectAsState()
    val rcsFilter by viewModel.rcsFilterEnabled.collectAsState()
    val freqEscalation by viewModel.freqEscalationEnabled.collectAsState()
    val blockedThisWeek by viewModel.blockedThisWeek.collectAsState()
    val blockedLastWeek by viewModel.blockedLastWeek.collectAsState()
    val blockedCalls by viewModel.blockedCalls.collectAsState()
    val scanResult by viewModel.scanResult.collectAsState()
    val smsScanResult by viewModel.smsScanResult.collectAsState()
    val lastSync by viewModel.lastSyncTimestamp.collectAsState()
    val lastSyncSource by viewModel.lastSyncSource.collectAsState()
    val activeProfile by viewModel.activeProfile.collectAsState()
    val scanningCalls by viewModel.scanningCalls.collectAsState()
    val scanningSms by viewModel.scanningSms.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val roleManager = remember(context) {
        context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
    }
    var permissionRefreshTick by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionRefreshTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val missingPerms = remember(
        context,
        permissionRefreshTick,
        blockCallsEnabled,
        blockSmsEnabled
    ) {
        CallShieldPermissions.missingEnabledProtectionPermissions(
            context = context,
            callsEnabled = blockCallsEnabled,
            smsEnabled = blockSmsEnabled
        )
    }
    val callPermissionsReady = remember(context, permissionRefreshTick) {
        CallShieldPermissions.hasCallProtectionPermissions(context)
    }
    val smsPermissionsReady = remember(context, permissionRefreshTick) {
        CallShieldPermissions.hasSmsProtectionPermissions(context)
    }
    val callLogReady = remember(context, permissionRefreshTick) {
        CallShieldPermissions.isPermissionGranted(context, Manifest.permission.READ_CALL_LOG)
    }
    val smsInboxReady = remember(context, permissionRefreshTick) {
        CallShieldPermissions.canReadSmsInbox(context)
    }
    val spamDatabaseReady = spamCount > 0
    val callScreenerReady = remember(roleManager, permissionRefreshTick) {
        CallShieldPermissions.hasCallScreeningRole(roleManager)
    }
    val overlayGranted = remember(context, permissionRefreshTick) {
        CallShieldPermissions.canDrawOverlays(context)
    }
    val notificationsGranted = remember(context, permissionRefreshTick) {
        CallShieldPermissions.hasNotificationPermission(context)
    }
    val corePermissionsReady = missingPerms.isEmpty()
    val dashboardStatus = remember(
        blockCallsEnabled,
        blockSmsEnabled,
        callPermissionsReady,
        smsPermissionsReady,
        corePermissionsReady,
        spamDatabaseReady,
        callScreenerReady,
        overlayGranted,
        notificationsGranted
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
            notificationsGranted = notificationsGranted
        )
    }
    val protectionEnabled = dashboardStatus.protectionEnabled
    val callProtectionReady = dashboardStatus.callProtectionReady
    val smsProtectionReady = dashboardStatus.smsProtectionReady
    val shieldActive = dashboardStatus.shieldActive
    val requiredSetupComplete = dashboardStatus.requiredSetupComplete
    val requiredSetupTotal = dashboardStatus.requiredSetupTotal
    val optionalSetupComplete = dashboardStatus.optionalSetupComplete
    val optionalSetupTotal = dashboardStatus.optionalSetupTotal
    val setupSummary = when {
        dashboardStatus.setupComplete && optionalSetupComplete == optionalSetupTotal ->
            stringResource(R.string.dashboard_setup_complete)
        dashboardStatus.setupComplete ->
            stringResource(R.string.dashboard_setup_optional_summary)
        else ->
            stringResource(R.string.dashboard_setup_required_summary)
    }
    val heroAccent = when {
        shieldActive -> CatGreen
        protectionEnabled -> CatYellow
        else -> CatBlue
    }
    val heroTitle = when (dashboardStatus.heroMode) {
        DashboardHeroMode.Active -> stringResource(R.string.dashboard_protection_active)
        DashboardHeroMode.SetupNeeded -> stringResource(R.string.dashboard_setup_needed)
        DashboardHeroMode.Disabled -> stringResource(R.string.dashboard_protection_disabled)
    }
    val heroSubtitle = when {
        shieldActive && blockCallsEnabled && blockSmsEnabled ->
            stringResource(R.string.dashboard_calls_and_texts_protected)
        shieldActive && blockCallsEnabled ->
            stringResource(R.string.dashboard_calls_protected)
        shieldActive && blockSmsEnabled ->
            stringResource(R.string.dashboard_texts_protected)
        !protectionEnabled ->
            stringResource(R.string.dashboard_turn_on_protection_hint)
        !corePermissionsReady ->
            stringResource(R.string.dashboard_finish_permissions_hint)
        !spamDatabaseReady ->
            stringResource(R.string.dashboard_finish_setup_hint)
        blockCallsEnabled && !callScreenerReady ->
            stringResource(R.string.dashboard_call_screener_missing_hint)
        else ->
            stringResource(R.string.dashboard_finish_setup_hint)
    }
    val heroAction = when {
        !corePermissionsReady -> HeroAction(
            label = stringResource(R.string.dashboard_review_permissions),
            icon = Icons.Default.Settings,
            onClick = { openAppSettings(context) }
        )
        !spamDatabaseReady -> HeroAction(
            label = stringResource(R.string.dashboard_sync_database),
            icon = Icons.Default.Sync,
            onClick = {
                hapticTick(context)
                viewModel.sync()
            }
        )
        blockCallsEnabled && !callScreenerReady && roleManager != null -> HeroAction(
            label = stringResource(R.string.dashboard_enable_call_screening),
            icon = Icons.AutoMirrored.Filled.PhoneCallback,
            onClick = { requestCallScreening(context, roleManager) }
        )
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        var heroVisible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { heroVisible = true }
        val heroAlpha by animateFloatAsState(
            targetValue = if (heroVisible) 1f else 0f,
            animationSpec = tween(600, easing = FastOutSlowInEasing),
            label = "heroAlpha"
        )
        val heroScale by animateFloatAsState(
            targetValue = if (heroVisible) 1f else 0.96f,
            animationSpec = tween(600, easing = FastOutSlowInEasing),
            label = "heroScale"
        )
        PremiumCard(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = heroAlpha
                    scaleX = heroScale
                    scaleY = heroScale
                },
            accentColor = heroAccent
        ) {
            val pulseAnim = rememberInfiniteTransition(label = "shieldPulse")
            val pulseScale by pulseAnim.animateFloat(
                initialValue = 1f,
                targetValue = if (shieldActive) 1.08f else 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1600, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "shieldScale"
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SetupStateBadge(
                            label = if (dashboardStatus.setupComplete) {
                                stringResource(R.string.dashboard_setup_complete)
                            } else {
                                stringResource(R.string.dashboard_setup_needs_attention)
                            },
                            color = heroAccent
                        )
                        Text(
                            text = heroTitle,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = CatText
                        )
                        Text(
                            text = heroSubtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = CatSubtext
                        )
                    }
                    Icon(
                        imageVector = if (shieldActive) Icons.Default.Shield else Icons.Default.ShieldMoon,
                        contentDescription = null,
                        tint = heroAccent,
                        modifier = Modifier
                            .size(62.dp)
                            .accentGlow(heroAccent, radius = 260f, alpha = 0.1f)
                            .graphicsLayer {
                                scaleX = pulseScale
                                scaleY = pulseScale
                            }
                    )
                }

                LinearProgressIndicator(
                    progress = { requiredSetupComplete / requiredSetupTotal.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = heroAccent,
                    trackColor = CatMuted.copy(alpha = 0.35f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(
                            R.string.dashboard_setup_progress_required,
                            requiredSetupComplete,
                            requiredSetupTotal
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = CatSubtext
                    )
                    Text(
                        text = setupSummary,
                        style = MaterialTheme.typography.labelMedium,
                        color = heroAccent,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val engineCount = activeEngineCount(
                            stirShaken = stirShaken,
                            heuristics = heuristics,
                            smsContent = smsContent,
                            neighborSpoof = neighborSpoof,
                            mlScorer = mlScorer,
                            rcsFilter = rcsFilter,
                            freqEscalation = freqEscalation
                        )
                        Text(
                            text = if (aggressiveMode) {
                                stringResource(R.string.dashboard_engines_active_aggressive, engineCount)
                            } else {
                                stringResource(R.string.dashboard_engines_active, engineCount)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = CatOverlay
                        )
                        Text(
                            text = syncFreshnessText(lastSync, lastSyncSource),
                            style = MaterialTheme.typography.labelMedium,
                            color = syncFreshnessColor(lastSync, lastSyncSource)
                        )
                    }
                    heroAction?.let { action ->
                        Button(
                            onClick = action.onClick,
                            enabled = syncState !is SyncState.Syncing || action.icon != Icons.Default.Sync,
                            colors = ButtonDefaults.buttonColors(containerColor = heroAccent),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            if (syncState is SyncState.Syncing && action.icon == Icons.Default.Sync) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Black
                                )
                            } else {
                                Icon(action.icon, null, tint = Black, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(action.label, color = Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        PremiumCard(modifier = Modifier.fillMaxWidth(), accentColor = CatYellow) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionHeader(stringResource(R.string.dashboard_setup_checklist), CatYellow)
                    SetupStateBadge(
                        label = if (dashboardStatus.setupComplete) {
                            stringResource(R.string.dashboard_setup_complete)
                        } else {
                            stringResource(R.string.dashboard_setup_needs_attention)
                        },
                        color = if (dashboardStatus.setupComplete) CatGreen else CatYellow
                    )
                }

                Text(
                    text = setupSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = CatSubtext
                )

                SetupChecklistRow(
                    icon = Icons.Default.Security,
                    title = stringResource(R.string.dashboard_setup_permissions_title),
                    detail = if (corePermissionsReady) {
                        stringResource(R.string.dashboard_permissions_ready_detail)
                    } else {
                        stringResource(R.string.dashboard_permissions_needed_detail, missingPerms.size)
                    },
                    ready = corePermissionsReady,
                    accentColor = CatBlue,
                    actionLabel = if (corePermissionsReady) null else stringResource(R.string.dashboard_action_review),
                    onAction = if (corePermissionsReady) null else { { openAppSettings(context) } }
                )

                GradientDivider()

                SetupChecklistRow(
                    icon = Icons.Default.DownloadDone,
                    title = stringResource(R.string.dashboard_setup_database_title),
                    detail = when {
                        syncState is SyncState.Syncing -> stringResource(R.string.dashboard_database_syncing_detail)
                        spamDatabaseReady -> stringResource(R.string.dashboard_database_ready_detail, spamCount)
                        else -> stringResource(R.string.dashboard_database_needed_detail)
                    },
                    ready = spamDatabaseReady,
                    accentColor = CatGreen,
                    actionLabel = if (spamDatabaseReady) null else stringResource(R.string.dashboard_sync),
                    onAction = if (spamDatabaseReady) null else {
                        {
                            hapticTick(context)
                            viewModel.sync()
                        }
                    }
                )

                GradientDivider()

                SetupChecklistRow(
                    icon = Icons.AutoMirrored.Filled.PhoneCallback,
                    title = stringResource(R.string.dashboard_setup_call_screener_title),
                    detail = when {
                        !blockCallsEnabled -> stringResource(R.string.dashboard_screener_optional_detail)
                        callScreenerReady -> stringResource(R.string.dashboard_screener_ready_detail)
                        else -> stringResource(R.string.dashboard_screener_needed_detail)
                    },
                    ready = !blockCallsEnabled || callScreenerReady,
                    accentColor = CatMauve,
                    actionLabel = if (!blockCallsEnabled || callScreenerReady || roleManager == null) null else stringResource(R.string.dashboard_enable_call_screening),
                    onAction = if (!blockCallsEnabled || callScreenerReady || roleManager == null) null else {
                        { requestCallScreening(context, roleManager) }
                    }
                )

                GradientDivider(modifier = Modifier.padding(top = 2.dp))

                Text(
                    text = stringResource(R.string.dashboard_optional_extras),
                    style = MaterialTheme.typography.labelMedium,
                    color = CatOverlay,
                    fontWeight = FontWeight.SemiBold
                )

                SetupChecklistRow(
                    icon = Icons.Default.Layers,
                    title = stringResource(R.string.dashboard_setup_overlay_title),
                    detail = if (overlayGranted) {
                        stringResource(R.string.dashboard_overlay_ready_detail)
                    } else {
                        stringResource(R.string.dashboard_overlay_needed_detail)
                    },
                    ready = overlayGranted,
                    accentColor = CatTeal,
                    actionLabel = if (overlayGranted) null else stringResource(R.string.dashboard_enable_overlay),
                    onAction = if (overlayGranted) null else { { openOverlaySettings(context) } }
                )

                GradientDivider()

                SetupChecklistRow(
                    icon = Icons.Default.Notifications,
                    title = stringResource(R.string.dashboard_setup_notifications_title),
                    detail = if (notificationsGranted) {
                        stringResource(R.string.dashboard_notifications_ready_detail)
                    } else {
                        stringResource(R.string.dashboard_notifications_needed_detail)
                    },
                    ready = notificationsGranted,
                    accentColor = CatBlue,
                    actionLabel = if (notificationsGranted) null else stringResource(R.string.dashboard_enable_notifications),
                    onAction = if (notificationsGranted) null else { { openNotificationSettings(context) } }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.dashboard_stat_today),
                value = blockedToday.toString(),
                icon = Icons.Default.Today,
                color = CatBlue
            )
            StatCard(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.dashboard_stat_this_week),
                value = blockedThisWeek.toString(),
                icon = Icons.Default.DateRange,
                color = CatMauve
            )
            StatCard(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.dashboard_stat_total),
                value = totalBlocked.toString(),
                icon = Icons.Default.Block,
                color = CatPeach
            )
        }

        if (blockedThisWeek > 0 || blockedLastWeek > 0) {
            val diff = blockedThisWeek - blockedLastWeek
            val trendIcon = when {
                diff > 0 -> Icons.AutoMirrored.Filled.TrendingUp
                diff < 0 -> Icons.AutoMirrored.Filled.TrendingDown
                else -> Icons.AutoMirrored.Filled.TrendingFlat
            }
            val trendColor = when {
                diff > 0 -> CatRed
                diff < 0 -> CatGreen
                else -> CatSubtext
            }
            val trendText = when {
                diff > 0 -> stringResource(R.string.dashboard_trend_more, diff)
                diff < 0 -> stringResource(R.string.dashboard_trend_fewer, -diff)
                else -> stringResource(R.string.dashboard_trend_same)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
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
                cornerRadius = 14.dp,
                onClick = { viewModel.openNumberDetail(lastBlocked.number) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (lastBlocked.isCall) Icons.Default.PhoneDisabled else Icons.Default.SpeakerNotesOff,
                        null,
                        tint = if (lastBlocked.isCall) CatRed else CatMauve,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(
                                R.string.dashboard_last_blocked,
                                PhoneFormatter.format(lastBlocked.number)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "${relativeTimeText(lastBlocked.timestamp)} · ${lastBlocked.matchReason.replace("_", " ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = CatOverlay
                        )
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = CatOverlay, modifier = Modifier.size(16.dp))
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
                    checked = blockCallsEnabled
                ) { viewModel.setBlockCalls(it) }
                GradientDivider(modifier = Modifier.padding(vertical = 4.dp))
                QuickToggle(
                    icon = Icons.Default.Sms,
                    label = stringResource(R.string.dashboard_block_sms),
                    checked = blockSmsEnabled
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
                        activeProfile == BlockingProfiles.Profile.WORK
                    ) { viewModel.applyProfile(BlockingProfiles.Profile.WORK) }
                    ProfileChip(
                        Modifier.weight(1f),
                        stringResource(R.string.dashboard_profile_personal),
                        CatGreen,
                        activeProfile == BlockingProfiles.Profile.PERSONAL
                    ) { viewModel.applyProfile(BlockingProfiles.Profile.PERSONAL) }
                    ProfileChip(
                        Modifier.weight(1f),
                        stringResource(R.string.dashboard_profile_sleep),
                        CatMauve,
                        activeProfile == BlockingProfiles.Profile.SLEEP
                    ) { viewModel.applyProfile(BlockingProfiles.Profile.SLEEP) }
                }
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileChip(
                        Modifier.weight(1f),
                        stringResource(R.string.dashboard_profile_maximum),
                        CatRed,
                        activeProfile == BlockingProfiles.Profile.MAX
                    ) { viewModel.applyProfile(BlockingProfiles.Profile.MAX) }
                    ProfileChip(
                        Modifier.weight(1f),
                        stringResource(R.string.dashboard_profile_off),
                        CatOverlay,
                        activeProfile == BlockingProfiles.Profile.OFF
                    ) { viewModel.applyProfile(BlockingProfiles.Profile.OFF) }
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        PremiumCard(modifier = Modifier.fillMaxWidth(), accentColor = CatGreen) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SectionHeader(stringResource(R.string.dashboard_quick_actions), CatGreen)
                DashboardActionRow(
                    icon = Icons.Default.Sync,
                    title = stringResource(R.string.dashboard_sync_database),
                    subtitle = if (spamDatabaseReady) {
                        stringResource(R.string.dashboard_action_sync_subtitle_ready, spamCount)
                    } else {
                        stringResource(R.string.dashboard_action_sync_subtitle)
                    },
                    accentColor = CatGreen,
                    actionLabel = stringResource(R.string.dashboard_sync),
                    loading = syncState is SyncState.Syncing,
                    enabled = syncState !is SyncState.Syncing
                ) {
                    hapticTick(context)
                    viewModel.sync()
                }
                GradientDivider()
                DashboardActionRow(
                    icon = Icons.Default.Call,
                    title = stringResource(R.string.dashboard_scan_calls),
                    subtitle = if (callLogReady) {
                        stringResource(R.string.dashboard_action_scan_calls_subtitle)
                    } else {
                        stringResource(R.string.dashboard_action_calls_permissions_subtitle)
                    },
                    accentColor = CatBlue,
                    actionLabel = if (callLogReady) {
                        stringResource(R.string.dashboard_action_run)
                    } else {
                        stringResource(R.string.dashboard_action_review)
                    },
                    loading = scanningCalls,
                    enabled = !scanningCalls
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
                    subtitle = if (smsInboxReady) {
                        stringResource(R.string.dashboard_action_scan_sms_subtitle)
                    } else {
                        stringResource(R.string.dashboard_action_sms_permissions_subtitle)
                    },
                    accentColor = CatMauve,
                    actionLabel = if (smsInboxReady) {
                        stringResource(R.string.dashboard_action_run)
                    } else {
                        stringResource(R.string.dashboard_action_review)
                    },
                    loading = scanningSms,
                    enabled = !scanningSms
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
                syncState is SyncState.Error
        ) {
            val accentColor = when (syncState) {
                is SyncState.Success -> CatGreen
                is SyncState.Warning -> CatYellow
                else -> CatRed
            }
            val message = when (syncState) {
                is SyncState.Success -> (syncState as SyncState.Success).message
                is SyncState.Warning -> (syncState as SyncState.Warning).message
                is SyncState.Error -> (syncState as SyncState.Error).message
                else -> ""
            }
            PremiumCard(
                modifier = Modifier.fillMaxWidth(),
                accentColor = accentColor
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (syncState is SyncState.Success) Icons.Default.CheckCircle else Icons.Default.Warning,
                        null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        message,
                        color = accentColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        scanResult?.let { result ->
            PremiumCard(
                modifier = Modifier.fillMaxWidth(),
                accentColor = if (result.error != null) CatRed else if (result.spamFound > 0) CatRed else CatGreen
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
                            Text(
                        stringResource(
                            R.string.dashboard_scan_result,
                            result.totalScanned,
                            result.spamFound
                        ),
                        color = if (result.spamFound > 0) CatRed else CatGreen
                    )
                    for (spam in result.spamNumbers.take(5)) {
                        Spacer(Modifier.height(6.dp))
                        GradientDivider()
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    PhoneFormatter.format(spam.number),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "${spam.callCount}x | ${spam.matchReason.replace("_", " ")}",
                                    style = MaterialTheme.typography.bodySmall, color = CatSubtext
                                )
                            }
                            TextButton(onClick = { viewModel.blockNumber(spam.number, spam.type) }) {
                                Text(stringResource(R.string.dashboard_block), color = CatRed)
                            }
                        }
                    }
                    if (result.spamNumbers.size > 5) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.dashboard_scan_more, result.spamNumbers.size - 5),
                            color = CatOverlay,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    } // else (no error)
                }
            }
        }

        smsScanResult?.let { result ->
            PremiumCard(
                modifier = Modifier.fillMaxWidth(),
                accentColor = if (result.error != null) CatRed else if (result.spamFound > 0) CatRed else CatGreen
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
                            Text(
                        stringResource(
                            R.string.dashboard_sms_scan_result,
                            result.totalScanned,
                            result.spamFound
                        ),
                        color = if (result.spamFound > 0) CatRed else CatGreen
                    )
                    for (sms in result.spamMessages.take(5)) {
                        Spacer(Modifier.height(6.dp))
                        GradientDivider()
                        Spacer(Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    PhoneFormatter.format(sms.number),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(sms.body, style = MaterialTheme.typography.bodySmall, color = CatSubtext, maxLines = 1)
                                Text(sms.matchReason.replace("_", " "), style = MaterialTheme.typography.labelSmall, color = CatPeach)
                            }
                            TextButton(onClick = { viewModel.blockNumber(sms.number, sms.type) }) {
                                Text(stringResource(R.string.dashboard_block), color = CatRed)
                            }
                        }
                    }
                    if (result.spamMessages.size > 5) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.dashboard_scan_more, result.spamMessages.size - 5),
                            color = CatOverlay,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    } // else (no error)
                }
            }
        }

        val topAreaCodes = remember(blockedCalls) {
            blockedCalls.mapNotNull { AreaCodeLookup.getAreaCode(it.number) }
                .groupBy { it }.mapValues { it.value.size }
                .filter { it.value >= 5 }
                .entries.sortedByDescending { it.value }.take(3)
        }
        if (topAreaCodes.isNotEmpty()) {
            PremiumCard(
                modifier = Modifier.fillMaxWidth(),
                accentColor = CatYellow
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lightbulb, null, tint = CatYellow, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.dashboard_smart_suggestions),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
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
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.addWildcardRule("+1$ac*", false, "Block $ac ($loc)") }) {
                                Text(
                                    stringResource(R.string.dashboard_block_area, ac),
                                    color = CatYellow,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupChecklistRow(
    icon: ImageVector,
    title: String,
    detail: String,
    ready: Boolean,
    accentColor: Color,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(accentColor.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = accentColor, modifier = Modifier.size(20.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
        }
        when {
            ready -> SetupStateBadge(stringResource(R.string.dashboard_status_ready), CatGreen)
            actionLabel != null && onAction != null -> {
                OutlinedButton(
                    onClick = onAction,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text(actionLabel, color = accentColor, style = MaterialTheme.typography.labelMedium)
                }
            }
            else -> SetupStateBadge(stringResource(R.string.dashboard_status_needed), CatYellow)
        }
    }
}

@Composable
private fun SetupStateBadge(label: String, color: Color) {
    StatusPill(
        text = label,
        color = color,
        modifier = Modifier.wrapContentWidth(),
        horizontalPadding = 10.dp,
        verticalPadding = 6.dp
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
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(accentColor.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = accentColor, modifier = Modifier.size(20.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
        }
        Button(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = accentColor),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.height(36.dp)
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 2.dp,
                    color = Black
                )
            } else {
                Text(actionLabel, color = Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun relativeTimeText(timestamp: Long): String {
    val ago = System.currentTimeMillis() - timestamp
    return when {
        ago < 60_000 -> stringResource(R.string.dashboard_time_just_now)
        ago < 3_600_000 -> stringResource(R.string.dashboard_time_minutes_ago, (ago / 60_000).toInt())
        ago < 86_400_000 -> stringResource(R.string.dashboard_time_hours_ago, (ago / 3_600_000).toInt())
        else -> stringResource(R.string.dashboard_time_days_ago, (ago / 86_400_000).toInt())
    }
}

@Composable
private fun syncFreshnessText(lastSync: Long, lastSyncSource: String): String {
    if (lastSyncSource == SpamRepository.SYNC_SOURCE_BUNDLED) {
        return stringResource(R.string.dashboard_bundled_snapshot_ready)
    }
    if (lastSync <= 0L) {
        return stringResource(R.string.dashboard_database_needed_detail)
    }
    val ago = System.currentTimeMillis() - lastSync
    return when {
        ago < 3_600_000 -> stringResource(R.string.dashboard_synced_just_now)
        ago < 86_400_000 -> stringResource(R.string.dashboard_synced_hours_ago, (ago / 3_600_000).toInt())
        else -> stringResource(R.string.dashboard_synced_days_ago, (ago / 86_400_000).toInt())
    }
}

@Composable
private fun syncFreshnessColor(lastSync: Long, lastSyncSource: String): Color {
    if (lastSyncSource == SpamRepository.SYNC_SOURCE_BUNDLED) return CatBlue
    if (lastSync <= 0L) return CatYellow
    val ago = System.currentTimeMillis() - lastSync
    return when {
        ago < 86_400_000 -> CatGreen
        ago < 172_800_000 -> CatYellow
        else -> CatRed
    }
}

private data class HeroAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

private fun activeEngineCount(
    stirShaken: Boolean,
    heuristics: Boolean,
    smsContent: Boolean,
    neighborSpoof: Boolean,
    mlScorer: Boolean,
    rcsFilter: Boolean,
    freqEscalation: Boolean
): Int = listOf(
    true,
    stirShaken,
    heuristics,
    smsContent,
    neighborSpoof,
    mlScorer,
    rcsFilter,
    freqEscalation
).count { it }

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
    )
}

private fun openOverlaySettings(context: Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    )
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    context.startActivity(intent)
}

private fun requestCallScreening(context: Context, roleManager: RoleManager) {
    try {
        context.startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
    } catch (_: Exception) {
        // Some OEM ROMs remove ROLE_CALL_SCREENING entirely — fall back to app settings
        // so the user can at least see what's available.
        openAppSettings(context)
    }
}

@Composable
fun QuickToggle(icon: ImageVector, label: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val tintColor = if (checked) CatGreen else CatSubtext
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(tintColor.copy(alpha = 0.08f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = tintColor, modifier = Modifier.size(20.dp))
        }
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Switch(
            checked = checked,
            onCheckedChange = {
                hapticTick(context)
                onChanged(it)
            },
            colors = SwitchDefaults.colors(checkedTrackColor = CatGreen, checkedThumbColor = Black)
        )
    }
}

@Composable
fun ProfileChip(modifier: Modifier, label: String, color: Color, isActive: Boolean = false, onClick: () -> Unit) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = { hapticConfirm(context); onClick() },
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (isActive) color.copy(alpha = 0.6f) else color.copy(alpha = 0.2f)),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = color,
            containerColor = if (isActive) color.copy(alpha = 0.12f) else Color.Transparent
        )
    ) {
        if (isActive) {
            Icon(Icons.Default.Check, null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun StatCard(modifier: Modifier, title: String, value: String, icon: ImageVector, color: Color) {
    val targetValue = value.toIntOrNull() ?: 0
    val animatedValue by animateIntAsState(
        targetValue = targetValue,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "counter"
    )

    PremiumCard(modifier = modifier, accentColor = color.copy(alpha = 0.5f)) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                animatedValue.toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(title, style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 1.sp), color = CatSubtext)
        }
    }
}
