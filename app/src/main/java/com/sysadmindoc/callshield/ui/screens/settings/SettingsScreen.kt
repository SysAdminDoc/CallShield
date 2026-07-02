package com.sysadmindoc.callshield.ui.screens.settings

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PhoneCallback
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.sysadmindoc.callshield.BuildConfig
import com.sysadmindoc.callshield.permissions.CallShieldPermissions
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.BackupRestore
import com.sysadmindoc.callshield.data.repository.ANSWERED_CALLER_THRESHOLD_MAX
import com.sysadmindoc.callshield.data.repository.ANSWERED_CALLER_THRESHOLD_MIN
import com.sysadmindoc.callshield.data.repository.ANSWERED_CALLER_WINDOW_DAYS_MAX
import com.sysadmindoc.callshield.data.repository.ANSWERED_CALLER_WINDOW_DAYS_MIN
import com.sysadmindoc.callshield.data.repository.EMERGENCY_CALLBACK_WINDOW_MINUTES_MAX
import com.sysadmindoc.callshield.data.repository.EMERGENCY_CALLBACK_WINDOW_MINUTES_MIN
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.*

internal const val SETTINGS_QUIET_HOURS_TOGGLE_TAG = "settings_quiet_hours_toggle"
internal const val SETTINGS_ANSWERED_CALLER_TOGGLE_TAG = "settings_answered_caller_toggle"
internal const val SETTINGS_RESTORE_PREVIEW_TAG = "settings_restore_preview"

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val blockCalls by viewModel.blockCallsEnabled.collectAsStateWithLifecycle()
    val blockSms by viewModel.blockSmsEnabled.collectAsStateWithLifecycle()
    val blockUnknown by viewModel.blockUnknownEnabled.collectAsStateWithLifecycle()
    val stirShaken by viewModel.stirShakenEnabled.collectAsStateWithLifecycle()
    val stirTrustedAllow by viewModel.stirTrustedAllowEnabled.collectAsStateWithLifecycle()
    val autoMuteLowConfidence by viewModel.autoMuteLowConfidenceEnabled.collectAsStateWithLifecycle()
    val neighborSpoof by viewModel.neighborSpoofEnabled.collectAsStateWithLifecycle()
    val heuristics by viewModel.heuristicsEnabled.collectAsStateWithLifecycle()
    val smsContent by viewModel.smsContentEnabled.collectAsStateWithLifecycle()
    val smsBurst by viewModel.smsBurstEnabled.collectAsStateWithLifecycle()
    val contactWhitelist by viewModel.contactWhitelistEnabled.collectAsStateWithLifecycle()
    val contactsOnly by viewModel.contactsOnlyEnabled.collectAsStateWithLifecycle()
    val dbPrefixExpansion by viewModel.dbPrefixExpansionEnabled.collectAsStateWithLifecycle()
    val aggressiveMode by viewModel.aggressiveModeEnabled.collectAsStateWithLifecycle()
    val answeredCallerTrust by viewModel.answeredCallerTrustEnabled.collectAsStateWithLifecycle()
    val answeredCallerThreshold by viewModel.answeredCallerThreshold.collectAsStateWithLifecycle()
    val answeredCallerWindowDays by viewModel.answeredCallerWindowDays.collectAsStateWithLifecycle()
    val emergencyCallbackGrace by viewModel.emergencyCallbackGraceEnabled.collectAsStateWithLifecycle()
    val emergencyCallbackWindowMinutes by viewModel.emergencyCallbackWindowMinutes.collectAsStateWithLifecycle()
    val autoCleanup by viewModel.autoCleanupEnabled.collectAsStateWithLifecycle()
    val cleanupDays by viewModel.cleanupDays.collectAsStateWithLifecycle()
    val timeBlock by viewModel.timeBlockEnabled.collectAsStateWithLifecycle()
    val timeStart by viewModel.timeBlockStart.collectAsStateWithLifecycle()
    val timeEnd by viewModel.timeBlockEnd.collectAsStateWithLifecycle()
    val freqEscalation by viewModel.freqEscalationEnabled.collectAsStateWithLifecycle()
    val mlScorer by viewModel.mlScorerEnabled.collectAsStateWithLifecycle()
    val rcsFilter by viewModel.rcsFilterEnabled.collectAsStateWithLifecycle()
    val silentVoicemail by viewModel.silentVoicemailEnabled.collectAsStateWithLifecycle()
    val pushAlertEnabled by viewModel.pushAlertEnabled.collectAsStateWithLifecycle()
    val pushAlertDisabledPackages by viewModel.pushAlertDisabledPackages.collectAsStateWithLifecycle()
    val abstractApiKey by viewModel.abstractApiKey.collectAsStateWithLifecycle()
    var showPushAlertSources by remember { mutableStateOf(false) }
    val apiKeyClearedMessage = stringResource(R.string.settings_api_key_cleared)
    val apiKeySavedMessage = stringResource(R.string.settings_api_key_saved)

    val roleManager = remember(context) {
        context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
    }
    var missingCorePermissions by remember(context, blockCalls, blockSms) {
        mutableStateOf(
            CallShieldPermissions.missingEnabledProtectionPermissions(
                context = context,
                callsEnabled = blockCalls,
                smsEnabled = blockSms
            )
        )
    }
    var notificationsGranted by remember(context) { mutableStateOf(CallShieldPermissions.hasNotificationPermission(context)) }
    var overlayGranted by remember(context) { mutableStateOf(CallShieldPermissions.canDrawOverlays(context)) }
    var screenerGranted by remember(roleManager) { mutableStateOf(CallShieldPermissions.hasCallScreeningRole(roleManager)) }
    val corePermissionsGranted = missingCorePermissions.isEmpty()
    val screenerReadyForCurrentMode = !blockCalls || screenerGranted
    val setupReadyCount = listOf(corePermissionsGranted, screenerReadyForCurrentMode, overlayGranted, notificationsGranted).count { it }
    val setupTotal = 4
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        missingCorePermissions = CallShieldPermissions.missingEnabledProtectionPermissions(
            context = context,
            callsEnabled = blockCalls,
            smsEnabled = blockSms
        )
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsGranted = CallShieldPermissions.hasNotificationPermission(context)
    }
    val screeningLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        screenerGranted = CallShieldPermissions.hasCallScreeningRole(roleManager)
    }

    DisposableEffect(lifecycleOwner, context, roleManager, blockCalls, blockSms) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                missingCorePermissions = CallShieldPermissions.missingEnabledProtectionPermissions(
                    context = context,
                    callsEnabled = blockCalls,
                    smsEnabled = blockSms
                )
                notificationsGranted = CallShieldPermissions.hasNotificationPermission(context)
                overlayGranted = CallShieldPermissions.canDrawOverlays(context)
                screenerGranted = CallShieldPermissions.hasCallScreeningRole(roleManager)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        PremiumCard {
            Column(modifier = Modifier.padding(16.dp)) {
                SectionHeader(stringResource(R.string.settings_permissions_access), CatBlue)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.settings_permissions_access_desc), style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.settings_setup_progress, setupReadyCount, setupTotal),
                        style = MaterialTheme.typography.labelMedium,
                        color = CatSubtext
                    )
                    Text(
                        if (setupReadyCount == setupTotal) {
                            stringResource(R.string.settings_setup_ready_summary)
                        } else {
                            stringResource(R.string.settings_setup_attention_summary)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (setupReadyCount == setupTotal) CatGreen else CatBlue,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { setupReadyCount / setupTotal.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = if (setupReadyCount == setupTotal) CatGreen else CatBlue,
                    trackColor = CatMuted.copy(alpha = 0.25f)
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    PermissionStatusChip(
                        label = stringResource(if (corePermissionsGranted) R.string.settings_permissions_granted else R.string.settings_permissions_needed),
                        color = if (corePermissionsGranted) CatGreen else CatPeach,
                        modifier = Modifier.weight(1f)
                    )
                    PermissionStatusChip(
                        label = stringResource(
                            when {
                                screenerGranted -> R.string.settings_call_screener_enabled
                                blockCalls -> R.string.settings_call_screener_needed
                                else -> R.string.settings_call_screener_optional
                            }
                        ),
                        color = when {
                            screenerGranted -> CatGreen
                            blockCalls -> CatMauve
                            else -> CatOverlay
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    PermissionStatusChip(
                        label = stringResource(if (overlayGranted) R.string.settings_overlay_enabled else R.string.settings_overlay_needed),
                        color = if (overlayGranted) CatGreen else CatBlue,
                        modifier = Modifier.weight(1f)
                    )
                    PermissionStatusChip(
                        label = stringResource(
                            if (notificationsGranted) R.string.settings_notifications_enabled
                            else R.string.settings_notifications_optional
                        ),
                        color = if (notificationsGranted) CatGreen else CatOverlay,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (!corePermissionsGranted || !screenerReadyForCurrentMode || !overlayGranted || !notificationsGranted) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!corePermissionsGranted) {
                            PremiumActionButton(
                                label = stringResource(R.string.settings_grant_permissions),
                                icon = Icons.Default.Security,
                                color = CatBlue,
                                onClick = {
                                    permissionLauncher.launch(CallShieldPermissions.corePermissions.toTypedArray())
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (blockCalls && !screenerGranted && roleManager != null) {
                            PremiumActionButton(
                                label = stringResource(R.string.settings_call_screener),
                                icon = Icons.AutoMirrored.Filled.PhoneCallback,
                                color = CatMauve,
                                onClick = {
                                    try {
                                        screeningLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                                    } catch (_: Exception) {
                                        // Some OEM ROMs remove ROLE_CALL_SCREENING — open app settings instead
                                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (!overlayGranted) {
                            PremiumActionButton(
                                label = stringResource(R.string.settings_overlay),
                                icon = Icons.Default.Layers,
                                color = CatBlue,
                                onClick = {
                                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                outlined = true
                            )
                        }
                        if (!notificationsGranted) {
                            PremiumActionButton(
                                label = stringResource(R.string.settings_notifications),
                                icon = Icons.Default.Notifications,
                                color = CatMauve,
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        }
                                        context.startActivity(intent)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                outlined = true
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                TextButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        context.startActivity(intent)
                    }
                ) {
                    Text(stringResource(R.string.settings_open_app_settings), color = CatSubtext)
                }
            }
        }

        // Blocking
        SettingsCard(stringResource(R.string.settings_blocking)) {
            SettingsToggle(stringResource(R.string.settings_block_spam_calls), stringResource(R.string.settings_block_spam_calls_desc), Icons.Default.PhoneDisabled, blockCalls) { viewModel.setBlockCalls(it) }
            GradientDivider()
            SettingsToggle(stringResource(R.string.settings_block_spam_sms), stringResource(R.string.settings_block_spam_sms_desc), Icons.Default.SpeakerNotesOff, blockSms) { viewModel.setBlockSms(it) }
            GradientDivider()
            SettingsToggle(stringResource(R.string.settings_block_unknown), stringResource(R.string.settings_block_unknown_desc), Icons.Default.QuestionMark, blockUnknown) { viewModel.setBlockUnknown(it) }
        }

        // Safety
        SettingsCard(stringResource(R.string.settings_safety)) {
            SettingsToggle(stringResource(R.string.settings_contact_whitelist), stringResource(R.string.settings_contact_whitelist_desc), Icons.Default.Contacts, contactWhitelist) { viewModel.setContactWhitelist(it) }
            SettingsToggle(
                stringResource(R.string.settings_contacts_only),
                stringResource(R.string.settings_contacts_only_desc),
                Icons.Default.PhoneLocked,
                contactsOnly,
            ) { viewModel.setContactsOnly(it) }
        }

        // Detection engines
        SettingsCard(stringResource(R.string.settings_detection_engines)) {
            SettingsToggle(stringResource(R.string.settings_stir_shaken), stringResource(R.string.settings_stir_shaken_desc), Icons.Default.VerifiedUser, stirShaken) { viewModel.setStirShaken(it) }
            GradientDivider()
            SettingsToggle(
                stringResource(R.string.settings_stir_trusted_allow),
                stringResource(R.string.settings_stir_trusted_allow_desc),
                Icons.Default.VerifiedUser,
                stirTrustedAllow,
            ) { viewModel.setStirTrustedAllow(it) }
            GradientDivider()
            AnsweredCallerTrustSettings(
                enabled = answeredCallerTrust,
                threshold = answeredCallerThreshold,
                windowDays = answeredCallerWindowDays,
                onEnabledChange = viewModel::setAnsweredCallerTrust,
                onThresholdChange = viewModel::setAnsweredCallerThreshold,
                onWindowDaysChange = viewModel::setAnsweredCallerWindowDays,
            )
            GradientDivider()
            SettingsToggle(
                stringResource(R.string.settings_emergency_callback_grace),
                stringResource(R.string.settings_emergency_callback_grace_desc),
                Icons.Default.Emergency,
                emergencyCallbackGrace,
                onCheckedChange = viewModel::setEmergencyCallbackGrace,
            )
            if (emergencyCallbackGrace) {
                Spacer(Modifier.height(8.dp))
                SettingsNumberStepper(
                    label = stringResource(R.string.settings_emergency_callback_window),
                    valueText = pluralStringResource(
                        R.plurals.settings_emergency_callback_window_value,
                        emergencyCallbackWindowMinutes,
                        emergencyCallbackWindowMinutes,
                    ),
                    value = emergencyCallbackWindowMinutes,
                    minValue = EMERGENCY_CALLBACK_WINDOW_MINUTES_MIN,
                    maxValue = EMERGENCY_CALLBACK_WINDOW_MINUTES_MAX,
                    step = EMERGENCY_CALLBACK_WINDOW_MINUTES_STEP,
                    onValueChange = viewModel::setEmergencyCallbackWindowMinutes,
                )
            }
            GradientDivider()
            SettingsToggle(stringResource(R.string.settings_neighbor_spoofing), stringResource(R.string.settings_neighbor_spoofing_desc), Icons.Default.NearMe, neighborSpoof) { viewModel.setNeighborSpoof(it) }
            GradientDivider()
            SettingsToggle(stringResource(R.string.settings_heuristic_analysis), stringResource(R.string.settings_heuristic_analysis_desc), Icons.Default.Psychology, heuristics) { viewModel.setHeuristics(it) }
            GradientDivider()
            SettingsToggle(stringResource(R.string.settings_sms_content), stringResource(R.string.settings_sms_content_desc), Icons.AutoMirrored.Filled.TextSnippet, smsContent) { viewModel.setSmsContent(it) }
            GradientDivider()
            SettingsToggle(
                stringResource(R.string.settings_sms_burst),
                stringResource(R.string.settings_sms_burst_desc),
                Icons.Default.SmsFailed,
                smsBurst,
            ) { viewModel.setSmsBurst(it) }
            GradientDivider()
            SettingsToggle(stringResource(R.string.settings_repeat_caller), stringResource(R.string.settings_repeat_caller_desc), Icons.Default.Repeat, freqEscalation) { viewModel.setFreqEscalation(it) }
            GradientDivider()
            SettingsToggle(stringResource(R.string.settings_ml_scorer), stringResource(R.string.settings_ml_scorer_desc), Icons.Default.SmartToy, mlScorer) { viewModel.setMlScorer(it) }
            GradientDivider()
            SettingsToggle(
                stringResource(R.string.settings_db_prefix_expansion),
                stringResource(R.string.settings_db_prefix_expansion_desc),
                Icons.Default.CallSplit,
                dbPrefixExpansion,
            ) { viewModel.setDbPrefixExpansion(it) }
            GradientDivider()
            SettingsToggle(stringResource(R.string.settings_rcs_filter), stringResource(R.string.settings_rcs_filter_desc), Icons.Default.MarkChatRead, rcsFilter) { viewModel.setRcsFilter(it) }
            if (rcsFilter) {
                Spacer(Modifier.height(4.dp))
                PremiumActionButton(
                    label = stringResource(R.string.settings_grant_notification_access),
                    icon = Icons.Default.NotificationsActive,
                    color = CatMauve,
                    onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth(),
                    outlined = true
                )
            }
            GradientDivider()
            // A3: Push-alert bridge — notification-backed allow-through for
            // unknown callers. Shares the notification-listener grant with
            // the RCS filter, so we show the same "Grant notification access"
            // shortcut when this is on without the permission.
            SettingsToggle(
                stringResource(R.string.settings_push_alert),
                stringResource(R.string.settings_push_alert_desc),
                Icons.Default.NotificationsActive,
                pushAlertEnabled
            ) { viewModel.setPushAlert(it) }
            if (pushAlertEnabled) {
                val totalSources = com.sysadmindoc.callshield.data.PushAlertRegistry.ALERT_SOURCE_PACKAGES.size
                val activeSources = totalSources - pushAlertDisabledPackages.size
                Spacer(Modifier.height(4.dp))
                PremiumActionButton(
                    label = stringResource(R.string.settings_push_alert_sources),
                    icon = Icons.Default.Tune,
                    color = CatMauve,
                    onClick = { showPushAlertSources = true },
                    modifier = Modifier.fillMaxWidth(),
                    outlined = true
                )
                Text(
                    stringResource(
                        R.string.settings_push_alert_sources_count,
                        activeSources,
                        totalSources,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = CatSubtext,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            GradientDivider()
            // Silent voicemail mode — send blocked calls to voicemail silently
            // instead of hard-rejecting. Off by default; users who want the
            // missed-call entry as an audit trail can keep hard reject.
            SettingsToggle(
                stringResource(R.string.settings_silent_voicemail),
                stringResource(R.string.settings_silent_voicemail_desc),
                Icons.Default.Voicemail,
                silentVoicemail
            ) { viewModel.setSilentVoicemail(it) }
            GradientDivider()
            // v1.7.0: auto-mute low-confidence blocks. Independent from
            // Silent Voicemail — Silent always silences; auto-mute only
            // silences blocks scoring below the 60-confidence threshold.
            SettingsToggle(
                stringResource(R.string.settings_automute_low_confidence),
                stringResource(R.string.settings_automute_low_confidence_desc),
                Icons.Default.Voicemail,
                autoMuteLowConfidence,
            ) { viewModel.setAutoMuteLowConfidence(it) }
        }

        // Feature 9: Time-based blocking
        QuietHoursSettings(
            enabled = timeBlock,
            startHour = timeStart,
            endHour = timeEnd,
            onEnabledChange = { viewModel.setTimeBlock(it) },
            onStartChange = { viewModel.setTimeBlockStart(it) },
            onEndChange = { viewModel.setTimeBlockEnd(it) },
        )

        // Power mode
        SettingsCard(stringResource(R.string.settings_power_mode)) {
            SettingsToggle(stringResource(R.string.settings_aggressive_blocking), stringResource(R.string.settings_aggressive_blocking_desc), Icons.Default.Security, aggressiveMode, tintColor = CatRed) { viewModel.setAggressiveMode(it) }
        }

        // Auto-cleanup
        SettingsCard(stringResource(R.string.settings_log_cleanup)) {
            SettingsToggle(stringResource(R.string.settings_auto_cleanup), stringResource(R.string.settings_auto_cleanup_desc), Icons.Default.AutoDelete, autoCleanup) { viewModel.setAutoCleanup(it) }
            if (autoCleanup) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_keep_for), style = MaterialTheme.typography.bodySmall, color = CatSubtext)
                    listOf(7, 14, 30, 90).forEach { days ->
                        FilterChip(
                            selected = cleanupDays == days, onClick = { viewModel.setCleanupDays(days) },
                            label = { Text(stringResource(R.string.settings_days, days)) },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, if (cleanupDays == days) CatGreen.copy(alpha = 0.3f) else CatMuted.copy(alpha = 0.3f)),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CatGreen.copy(alpha = 0.2f), selectedLabelColor = CatGreen)
                        )
                    }
                }
            }
        }

        // Export log
        SettingsCard(stringResource(R.string.settings_export)) {
            PremiumActionButton(
                label = stringResource(R.string.settings_export_csv),
                icon = Icons.Default.FileDownload,
                color = CatBlue,
                onClick = { hapticTick(context); viewModel.exportLog() },
                modifier = Modifier.fillMaxWidth()
            )
            Text(stringResource(R.string.settings_export_csv_desc), style = MaterialTheme.typography.labelSmall, color = CatOverlay)
        }

        // Backup/restore
        SettingsCard(stringResource(R.string.settings_backup_restore)) {
            val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let { viewModel.restore(it) }
            }
            val restoreResult by viewModel.restoreResult.collectAsStateWithLifecycle()
            val restorePreview by viewModel.restorePreview.collectAsStateWithLifecycle()

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PremiumActionButton(
                    label = stringResource(R.string.settings_backup),
                    icon = Icons.Default.Backup,
                    color = CatGreen,
                    onClick = { hapticTick(context); viewModel.backup() },
                    modifier = Modifier.weight(1f)
                )
                PremiumActionButton(
                    label = stringResource(R.string.settings_restore),
                    icon = Icons.Default.Restore,
                    color = CatBlue,
                    onClick = { hapticTick(context); restoreLauncher.launch(arrayOf("application/json", "text/plain")) },
                    modifier = Modifier.weight(1f),
                    outlined = true
                )
            }
            restorePreview?.let { preview ->
                RestorePreviewPanel(
                    preview = preview,
                    onMerge = {
                        hapticTick(context)
                        viewModel.applyRestore(BackupRestore.RestoreMode.MERGE)
                    },
                    onReplace = {
                        hapticTick(context)
                        viewModel.applyRestore(BackupRestore.RestoreMode.REPLACE)
                    },
                    onCancel = {
                        hapticTick(context)
                        viewModel.clearRestorePreview()
                    },
                )
            }
            restoreResult?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("Restored ")) CatGreen else CatPeach
                )
                LaunchedEffect(it) {
                    kotlinx.coroutines.delay(4000)
                    viewModel.clearRestoreResult()
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.settings_backup_includes), style = MaterialTheme.typography.labelSmall, color = CatOverlay)
        }

        // Advanced — optional API key for caller name lookup
        SettingsCard(stringResource(R.string.settings_advanced)) {
            var apiKeyInput by remember { mutableStateOf(abstractApiKey) }
            var showApiKey by remember { mutableStateOf(false) }
            LaunchedEffect(abstractApiKey) {
                apiKeyInput = abstractApiKey
            }
            val trimmedApiKey = apiKeyInput.trim()
            val hasStoredApiKey = abstractApiKey.isNotBlank()
            val hasApiKeyChanges = trimmedApiKey != abstractApiKey
            val apiStatusText = stringResource(
                when {
                    hasApiKeyChanges -> R.string.settings_api_key_unsaved
                    hasStoredApiKey -> R.string.settings_api_key_saved_locally
                    else -> R.string.settings_api_key_not_configured
                }
            )
            val apiStatusColor = when {
                hasApiKeyChanges -> CatYellow
                hasStoredApiKey -> CatGreen
                else -> CatOverlay
            }
            Text(stringResource(R.string.settings_abstract_api_key), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.settings_abstract_api_desc), style = MaterialTheme.typography.bodySmall, color = CatSubtext)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { apiKeyInput = it },
                label = { Text(stringResource(R.string.settings_api_key)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Icon(Icons.Default.Key, contentDescription = null, tint = CatBlue)
                },
                visualTransformation = if (showApiKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            imageVector = if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = stringResource(
                                if (showApiKey) R.string.settings_api_key_hide else R.string.settings_api_key_show
                            ),
                            tint = CatSubtext
                        )
                    }
                },
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CatBlue,
                    unfocusedBorderColor = CardBorderAccent,
                    focusedLabelColor = CatBlue,
                    cursorColor = CatBlue
                )
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(
                    text = apiStatusText,
                    color = apiStatusColor,
                    modifier = Modifier.weight(1f),
                    horizontalPadding = 10.dp,
                    verticalPadding = 6.dp
                )
                PremiumActionButton(
                    label = stringResource(
                        if (trimmedApiKey.isBlank() && hasStoredApiKey) {
                            R.string.settings_api_key_clear
                        } else {
                            R.string.settings_api_key_save
                        }
                    ),
                    icon = if (trimmedApiKey.isBlank() && hasStoredApiKey) {
                        Icons.Default.DeleteSweep
                    } else {
                        Icons.Default.Save
                    },
                    color = CatBlue,
                    onClick = {
                        viewModel.setAbstractApiKey(trimmedApiKey)
                        hapticTick(context)
                        android.widget.Toast.makeText(
                            context,
                            if (trimmedApiKey.isBlank()) {
                                apiKeyClearedMessage
                            } else {
                                apiKeySavedMessage
                            },
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    },
                    enabled = hasApiKeyChanges,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_api_key_local_only),
                style = MaterialTheme.typography.labelSmall,
                color = CatOverlay
            )
        }

        // About
        PremiumCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("${stringResource(R.string.app_name)} v${BuildConfig.VERSION_NAME}", color = CatSubtext, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.settings_about_desc), style = MaterialTheme.typography.labelSmall, color = CatOverlay)
            }
        }
    }

    // A3 allowlist editor — modal sheet only mounts when requested so
    // the PackageManager lookup inside runs lazily.
    if (showPushAlertSources) {
        PushAlertSourcesSheet(
            disabledPackages = pushAlertDisabledPackages,
            onToggle = { pkg, allowed -> viewModel.setPushAlertPackageAllowed(pkg, allowed) },
            onReset = { viewModel.resetPushAlertPackages() },
            onDismiss = { showPushAlertSources = false },
        )
    }
}

@Composable
@Suppress("FunctionNaming", "LongMethod", "ktlint:standard:function-naming")
internal fun RestorePreviewPanel(
    preview: BackupRestore.RestorePreview,
    onMerge: () -> Unit,
    onReplace: () -> Unit,
    onCancel: () -> Unit,
) {
    val counts = preview.counts
    val conflictTotal = preview.conflicts.total

    Spacer(Modifier.height(10.dp))
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SETTINGS_RESTORE_PREVIEW_TAG),
        shape = RoundedCornerShape(12.dp),
        color = CatBlue.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, CatBlue.copy(alpha = 0.20f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                PremiumIconTile(icon = Icons.Default.Restore, color = CatBlue)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        stringResource(R.string.backup_restore_preview_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = CatText,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(
                            R.string.backup_restore_preview_summary,
                            counts.blockedNumbers,
                            counts.whitelistNumbers,
                            counts.wildcardRules,
                            counts.keywordRules,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = CatSubtext,
                    )
                }
            }
            Text(
                if (conflictTotal > 0) {
                    pluralStringResource(
                        R.plurals.backup_restore_preview_conflicts,
                        conflictTotal,
                        conflictTotal
                    )
                } else {
                    stringResource(R.string.backup_restore_preview_no_conflicts)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (conflictTotal > 0) CatPeach else CatGreen,
            )
            Text(
                stringResource(R.string.backup_restore_replace_warning),
                style = MaterialTheme.typography.labelSmall,
                color = CatOverlay,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PremiumActionButton(
                    label = stringResource(R.string.backup_restore_merge),
                    icon = Icons.Default.Restore,
                    color = CatBlue,
                    onClick = onMerge,
                    modifier = Modifier.weight(1f),
                    outlined = true
                )
                PremiumActionButton(
                    label = stringResource(R.string.backup_restore_replace),
                    icon = Icons.Default.DeleteSweep,
                    color = CatPeach,
                    onClick = onReplace,
                    modifier = Modifier.weight(1f)
                )
            }
            TextButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Close, null, tint = CatOverlay, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.backup_restore_cancel), color = CatOverlay)
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun AnsweredCallerTrustSettings(
    enabled: Boolean,
    threshold: Int,
    windowDays: Int,
    onEnabledChange: (Boolean) -> Unit,
    onThresholdChange: (Int) -> Unit,
    onWindowDaysChange: (Int) -> Unit,
) {
    SettingsToggle(
        stringResource(R.string.settings_answered_caller_trust),
        stringResource(R.string.settings_answered_caller_trust_desc),
        Icons.AutoMirrored.Filled.PhoneCallback,
        enabled,
        toggleTag = SETTINGS_ANSWERED_CALLER_TOGGLE_TAG,
        onCheckedChange = onEnabledChange,
    )
    if (enabled) {
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsNumberStepper(
                label = stringResource(R.string.settings_answered_caller_threshold),
                valueText = pluralStringResource(
                    R.plurals.settings_answered_caller_threshold_value,
                    threshold,
                    threshold,
                ),
                value = threshold,
                minValue = ANSWERED_CALLER_THRESHOLD_MIN,
                maxValue = ANSWERED_CALLER_THRESHOLD_MAX,
                onValueChange = onThresholdChange,
            )
            SettingsNumberStepper(
                label = stringResource(R.string.settings_answered_caller_window),
                valueText = pluralStringResource(
                    R.plurals.settings_answered_caller_window_value,
                    windowDays,
                    windowDays,
                ),
                value = windowDays,
                minValue = ANSWERED_CALLER_WINDOW_DAYS_MIN,
                maxValue = ANSWERED_CALLER_WINDOW_DAYS_MAX,
                onValueChange = onWindowDaysChange,
            )
        }
    }
}

@Composable
@Suppress("FunctionNaming", "LongParameterList")
private fun SettingsNumberStepper(
    label: String,
    valueText: String,
    value: Int,
    minValue: Int,
    maxValue: Int,
    step: Int = 1,
    onValueChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = CatSubtext)
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = CatText)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(
                onClick = { onValueChange((value - step).coerceAtLeast(minValue)) },
                enabled = value > minValue,
            ) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = stringResource(R.string.settings_decrease_value),
                    tint = if (value > minValue) CatText else CatOverlay,
                )
            }
            IconButton(
                onClick = { onValueChange((value + step).coerceAtMost(maxValue)) },
                enabled = value < maxValue,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.settings_increase_value),
                    tint = if (value < maxValue) CatText else CatOverlay,
                )
            }
        }
    }
}

private const val EMERGENCY_CALLBACK_WINDOW_MINUTES_STEP = 15

@Composable
internal fun QuietHoursSettings(
    enabled: Boolean,
    startHour: Int,
    endHour: Int,
    onEnabledChange: (Boolean) -> Unit,
    onStartChange: (Int) -> Unit,
    onEndChange: (Int) -> Unit,
) {
    SettingsCard(stringResource(R.string.settings_quiet_hours)) {
        SettingsToggle(
            stringResource(R.string.settings_quiet_hours_toggle),
            stringResource(R.string.settings_quiet_hours_desc),
            Icons.Default.Bedtime,
            enabled,
            toggleTag = SETTINGS_QUIET_HOURS_TOGGLE_TAG,
            onCheckedChange = onEnabledChange
        )
        if (enabled) {
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_time_start), style = MaterialTheme.typography.labelMedium, color = CatSubtext)
                    HourPicker(startHour, onSelect = onStartChange)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_time_end), style = MaterialTheme.typography.labelMedium, color = CatSubtext)
                    HourPicker(endHour, onSelect = onEndChange)
                }
            }
            if (startHour == endHour) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_quiet_hours_all_day_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = CatYellow
                )
            }
        }
    }
}

@Composable
fun HourPicker(selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = formatHourLabel(selected)

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = SurfaceBright)
        ) {
            Text(label, color = CatText)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (h in 0..23) {
                val l = formatHourLabel(h)
                DropdownMenuItem(text = { Text(l) }, onClick = { onSelect(h); expanded = false })
            }
        }
    }
}

internal fun formatHourLabel(hour: Int): String = when {
    hour == 0 -> "12 AM"
    hour < 12 -> "$hour AM"
    hour == 12 -> "12 PM"
    else -> "${hour - 12} PM"
}

@Composable
fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    PremiumCard {
        Column(modifier = Modifier.padding(18.dp)) {
            SectionHeader(title)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun SettingsToggle(
    title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    tintColor: androidx.compose.ui.graphics.Color = CatSubtext,
    toggleTag: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        PremiumIconTile(icon = icon, color = tintColor, size = 38.dp, iconSize = 20.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = { hapticTick(context); onCheckedChange(it) },
            modifier = if (toggleTag != null) Modifier.testTag(toggleTag) else Modifier,
            colors = SwitchDefaults.colors(checkedTrackColor = CatGreen, checkedThumbColor = Black)
        )
    }
}

@Composable
private fun PermissionStatusChip(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}
