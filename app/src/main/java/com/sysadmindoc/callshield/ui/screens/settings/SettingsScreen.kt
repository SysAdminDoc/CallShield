@file:Suppress("TooManyFunctions", "ktlint:standard:function-naming")

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.PhoneCallback
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sysadmindoc.callshield.BuildConfig
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.BackupRestore
import com.sysadmindoc.callshield.data.CallCategory
import com.sysadmindoc.callshield.data.CategoryCallAction
import com.sysadmindoc.callshield.data.PortableBackupCrypto
import com.sysadmindoc.callshield.data.model.ExternalBlocklistPreview
import com.sysadmindoc.callshield.data.model.ExternalBlocklistSubscription
import com.sysadmindoc.callshield.data.repository.ANSWERED_CALLER_THRESHOLD_MAX
import com.sysadmindoc.callshield.data.repository.ANSWERED_CALLER_THRESHOLD_MIN
import com.sysadmindoc.callshield.data.repository.ANSWERED_CALLER_WINDOW_DAYS_MAX
import com.sysadmindoc.callshield.data.repository.ANSWERED_CALLER_WINDOW_DAYS_MIN
import com.sysadmindoc.callshield.data.repository.EMERGENCY_CALLBACK_WINDOW_MINUTES_MAX
import com.sysadmindoc.callshield.data.repository.EMERGENCY_CALLBACK_WINDOW_MINUTES_MIN
import com.sysadmindoc.callshield.data.repository.FREQ_THRESHOLD_MAX
import com.sysadmindoc.callshield.data.repository.FREQ_THRESHOLD_MIN
import com.sysadmindoc.callshield.permissions.CallShieldPermissions
import com.sysadmindoc.callshield.ui.AppLanguage
import com.sysadmindoc.callshield.ui.DurationTtsText
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.StatusMessage
import com.sysadmindoc.callshield.ui.theme.*
import com.sysadmindoc.callshield.util.startActivitySafely

internal const val SETTINGS_QUIET_HOURS_TOGGLE_TAG = "settings_quiet_hours_toggle"
internal const val SETTINGS_ANSWERED_CALLER_TOGGLE_TAG = "settings_answered_caller_toggle"
internal const val SETTINGS_RESTORE_PREVIEW_TAG = "settings_restore_preview"
internal const val SETTINGS_THEME_ROW_TAG = "settings_theme_row"
internal const val SETTINGS_BACKUP_ENCRYPTION_TOGGLE_TAG = "settings_backup_encryption_toggle"
internal const val SETTINGS_BACKUP_PASSPHRASE_TAG = "settings_backup_passphrase"
internal const val SETTINGS_BACKUP_CONFIRM_TAG = "settings_backup_confirm"
internal const val SETTINGS_RESTORE_PASSPHRASE_TAG = "settings_restore_passphrase"
internal const val SETTINGS_CONTACT_SCOPE_TAG = "settings_contact_scope"

private val backupSectionOrder =
    listOf(
        BackupRestore.BackupSection.BLOCKED_NUMBERS,
        BackupRestore.BackupSection.WHITELIST,
        BackupRestore.BackupSection.WILDCARD_RULES,
        BackupRestore.BackupSection.RANGE_RULES,
        BackupRestore.BackupSection.KEYWORD_RULES,
        BackupRestore.BackupSection.SETTINGS,
        BackupRestore.BackupSection.LOGS,
    )

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val appTheme by viewModel.appTheme.collectAsStateWithLifecycle()
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
    val urlhausRemoteLookup by viewModel.urlhausRemoteLookupEnabled.collectAsStateWithLifecycle()
    val liveCallerEnrichment by viewModel.liveCallerEnrichmentEnabled.collectAsStateWithLifecycle()
    val contactWhitelist by viewModel.contactWhitelistEnabled.collectAsStateWithLifecycle()
    val contactsOnly by viewModel.contactsOnlyEnabled.collectAsStateWithLifecycle()
    val selectedContactGroups by viewModel.selectedContactGroups.collectAsStateWithLifecycle()
    val outgoingRiskWarning by viewModel.outgoingRiskWarningEnabled.collectAsStateWithLifecycle()
    val contactGroups by viewModel.contactGroups.collectAsStateWithLifecycle()
    val contactGroupsLoading by viewModel.contactGroupsLoading.collectAsStateWithLifecycle()
    val regionBlockEnabled by viewModel.regionBlockEnabled.collectAsStateWithLifecycle()
    val allowedRegions by viewModel.allowedRegions.collectAsStateWithLifecycle()
    val cnapTrustPatterns by viewModel.cnapTrustPatterns.collectAsStateWithLifecycle()
    val cnapBlockPatterns by viewModel.cnapBlockPatterns.collectAsStateWithLifecycle()
    val categoryCallActions by viewModel.categoryCallActions.collectAsStateWithLifecycle()
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
    val freqThreshold by viewModel.freqThreshold.collectAsStateWithLifecycle()
    val mlScorer by viewModel.mlScorerEnabled.collectAsStateWithLifecycle()
    val rcsFilter by viewModel.rcsFilterEnabled.collectAsStateWithLifecycle()
    val postCallScreen by viewModel.postCallScreenEnabled.collectAsStateWithLifecycle()
    val notificationScreeningPackages by viewModel.notificationScreeningPackages.collectAsStateWithLifecycle()
    val silentVoicemail by viewModel.silentVoicemailEnabled.collectAsStateWithLifecycle()
    val pushAlertEnabled by viewModel.pushAlertEnabled.collectAsStateWithLifecycle()
    val pushAlertDisabledPackages by viewModel.pushAlertDisabledPackages.collectAsStateWithLifecycle()
    val externalBlocklists by viewModel.externalBlocklistSubscriptions.collectAsStateWithLifecycle()
    val externalBlocklistPreview by viewModel.externalBlocklistPreview.collectAsStateWithLifecycle()
    val externalBlocklistResult by viewModel.externalBlocklistResult.collectAsStateWithLifecycle()
    var showPushAlertSources by rememberSaveable { mutableStateOf(false) }
    var showNotificationScreeningSources by rememberSaveable { mutableStateOf(false) }
    var showRegionCnapRules by rememberSaveable { mutableStateOf(false) }
    var showCategoryCallActions by rememberSaveable { mutableStateOf(false) }
    var showContactGroups by rememberSaveable { mutableStateOf(false) }
    var showRawSmsExportDialog by rememberSaveable { mutableStateOf(false) }
    var showThemeDialog by rememberSaveable { mutableStateOf(false) }
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var externalBlocklistUrl by rememberSaveable { mutableStateOf("") }
    var externalBlocklistLabel by rememberSaveable { mutableStateOf("") }

    val roleManager =
        remember(context) {
            context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
        }
    var missingCorePermissions by remember(context, blockCalls, blockSms) {
        mutableStateOf(
            CallShieldPermissions.missingEnabledProtectionPermissions(
                context = context,
                callsEnabled = blockCalls,
                smsEnabled = blockSms,
            ),
        )
    }
    var notificationsGranted by remember(context) { mutableStateOf(CallShieldPermissions.hasNotificationPermission(context)) }
    var overlayGranted by remember(context) { mutableStateOf(CallShieldPermissions.canDrawOverlays(context)) }
    var screenerGranted by remember(roleManager) { mutableStateOf(CallShieldPermissions.hasCallScreeningRole(roleManager)) }
    var contactsPermissionGranted by remember(context) {
        mutableStateOf(CallShieldPermissions.isPermissionGranted(context, Manifest.permission.READ_CONTACTS))
    }
    val corePermissionsGranted = missingCorePermissions.isEmpty()
    val screenerReadyForCurrentMode = !blockCalls || screenerGranted
    // Overlay and notifications add polish, but they are not required for the
    // protection engine. Keep optional enhancements out of the core setup
    // score so a configured blocker never looks unfinished.
    val setupReadyCount = listOf(corePermissionsGranted, screenerReadyForCurrentMode).count { it }
    val setupTotal = 2
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            missingCorePermissions =
                CallShieldPermissions.missingEnabledProtectionPermissions(
                    context = context,
                    callsEnabled = blockCalls,
                    smsEnabled = blockSms,
                )
        }
    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationsGranted = CallShieldPermissions.hasNotificationPermission(context)
        }
    val screeningLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            screenerGranted = CallShieldPermissions.hasCallScreeningRole(roleManager)
        }
    val contactsPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            contactsPermissionGranted = granted
            viewModel.refreshContactGroups()
            if (granted) showContactGroups = true
        }

    LaunchedEffect(contactsPermissionGranted) {
        if (contactsPermissionGranted) viewModel.refreshContactGroups()
    }

    DisposableEffect(lifecycleOwner, context, roleManager, blockCalls, blockSms) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    missingCorePermissions =
                        CallShieldPermissions.missingEnabledProtectionPermissions(
                            context = context,
                            callsEnabled = blockCalls,
                            smsEnabled = blockSms,
                        )
                    notificationsGranted = CallShieldPermissions.hasNotificationPermission(context)
                    overlayGranted = CallShieldPermissions.canDrawOverlays(context)
                    screenerGranted = CallShieldPermissions.hasCallScreeningRole(roleManager)
                    contactsPermissionGranted =
                        CallShieldPermissions.isPermissionGranted(context, Manifest.permission.READ_CONTACTS)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader(stringResource(R.string.settings_permissions_access))
                StatusPill(
                    text =
                        if (setupReadyCount == setupTotal) {
                            stringResource(R.string.settings_setup_ready_summary)
                        } else {
                            stringResource(R.string.settings_setup_attention_summary)
                        },
                    color = if (setupReadyCount == setupTotal) CatGreen else CatYellow,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_setup_progress, setupReadyCount, setupTotal),
                style = MaterialTheme.typography.bodySmall,
                color = CatSubtext,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { setupReadyCount / setupTotal.toFloat() },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = if (setupReadyCount == setupTotal) CatGreen else CatYellow,
                trackColor = CatMuted.copy(alpha = 0.32f),
            )
            Spacer(Modifier.height(10.dp))
            PermissionAccessRow(
                title = stringResource(R.string.settings_access_calls_messages),
                icon = Icons.Default.Security,
                ready = corePermissionsGranted,
                readyLabel = stringResource(R.string.settings_access_ready),
                actionLabel = stringResource(R.string.settings_access_grant),
                onAction = { permissionLauncher.launch(CallShieldPermissions.corePermissions.toTypedArray()) },
            )
            GradientDivider()
            PermissionAccessRow(
                title = stringResource(R.string.settings_access_call_screening),
                icon = Icons.AutoMirrored.Filled.PhoneCallback,
                ready = screenerReadyForCurrentMode,
                readyLabel =
                    stringResource(
                        if (blockCalls) R.string.settings_access_ready else R.string.settings_access_optional,
                    ),
                actionLabel = stringResource(R.string.settings_access_enable),
                onAction = {
                    try {
                        if (roleManager != null) {
                            screeningLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                        } else {
                            context.startActivitySafely(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }
                    } catch (_: Exception) {
                        context.startActivitySafely(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.settings_access_optional_title),
                style = MaterialTheme.typography.labelLarge,
                color = CatSubtext,
            )
            Spacer(Modifier.height(4.dp))
            PermissionAccessRow(
                title = stringResource(R.string.settings_access_caller_id),
                icon = Icons.Default.Layers,
                ready = overlayGranted,
                readyLabel = stringResource(R.string.settings_access_ready),
                actionLabel = stringResource(R.string.settings_access_enable),
                onAction = {
                    context.startActivitySafely(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        ),
                        onFailure = {
                            context.startActivitySafely(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        },
                    )
                },
            )
            GradientDivider()
            PermissionAccessRow(
                title = stringResource(R.string.settings_notifications),
                icon = Icons.Default.Notifications,
                ready = notificationsGranted,
                readyLabel = stringResource(R.string.settings_access_ready),
                actionLabel = stringResource(R.string.settings_access_enable),
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.startActivitySafely(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            },
                        )
                    }
                },
            )
            TextButton(
                onClick = {
                    context.startActivitySafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                },
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                Text(stringResource(R.string.settings_open_app_settings), color = CatSubtext)
            }
            GradientDivider()
        }

        // Appearance
        val languageOptions = AppLanguage.options()
        val currentLanguageTag = AppLanguage.currentLanguageTag()
        val currentLanguage =
            languageOptions.firstOrNull { it.languageTag == currentLanguageTag }
                ?: languageOptions.first()
        SettingsCard(stringResource(R.string.settings_appearance)) {
            SettingsLinkRow(
                title = stringResource(R.string.settings_theme),
                value = stringResource(appTheme.labelResource()),
                icon = Icons.Default.Palette,
                tintColor = CatGreen,
                modifier = Modifier.testTag(SETTINGS_THEME_ROW_TAG),
                onClick = { showThemeDialog = true },
            )
            GradientDivider()
            SettingsLinkRow(
                title = stringResource(R.string.settings_language),
                value = stringResource(currentLanguage.labelRes),
                icon = Icons.Default.Language,
                tintColor = CatBlue,
                onClick = { showLanguageDialog = true },
            )
        }

        // Blocking
        SettingsCard(stringResource(R.string.settings_blocking)) {
            SettingsToggle(stringResource(R.string.settings_block_spam_calls), stringResource(R.string.settings_block_spam_calls_desc), Icons.Default.PhoneDisabled, blockCalls) { viewModel.setBlockCalls(it) }
            GradientDivider()
            SettingsToggle(stringResource(R.string.settings_block_spam_sms), stringResource(R.string.settings_block_spam_sms_desc), Icons.Default.SpeakerNotesOff, blockSms) { viewModel.setBlockSms(it) }
            GradientDivider()
            SettingsToggle(stringResource(R.string.settings_block_unknown), stringResource(R.string.settings_block_unknown_desc), Icons.Default.QuestionMark, blockUnknown) { viewModel.setBlockUnknown(it) }
            GradientDivider()
            SettingsLinkRow(
                title = stringResource(R.string.settings_category_actions),
                value = stringResource(R.string.settings_category_actions_summary, categoryCallActions.size),
                icon = Icons.AutoMirrored.Filled.CallSplit,
                tintColor = CatBlue,
                onClick = { showCategoryCallActions = true },
            )
        }

        // Safety
        SettingsCard(stringResource(R.string.settings_safety)) {
            SettingsToggle(stringResource(R.string.settings_contact_whitelist), stringResource(R.string.settings_contact_whitelist_desc), Icons.Default.Contacts, contactWhitelist) { viewModel.setContactWhitelist(it) }
            if (contactWhitelist) {
                SettingsLinkRow(
                    title = stringResource(R.string.settings_contact_scope),
                    value =
                        when {
                            !contactsPermissionGranted -> {
                                stringResource(R.string.settings_permission_required)
                            }

                            selectedContactGroups.isEmpty() -> {
                                stringResource(R.string.settings_all_contacts)
                            }

                            else -> {
                                pluralStringResource(
                                    R.plurals.settings_contact_groups_selected,
                                    selectedContactGroups.size,
                                    selectedContactGroups.size,
                                )
                            }
                        },
                    icon = Icons.Default.Groups,
                    tintColor = CatGreen,
                    modifier = Modifier.testTag(SETTINGS_CONTACT_SCOPE_TAG),
                    onClick = {
                        if (contactsPermissionGranted) {
                            viewModel.refreshContactGroups()
                            showContactGroups = true
                        } else {
                            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                        }
                    },
                )
                if (!contactsPermissionGranted) {
                    Text(
                        stringResource(R.string.settings_contact_scope_degraded),
                        style = MaterialTheme.typography.labelSmall,
                        color = CatPeach,
                        modifier = Modifier.padding(start = 44.dp, end = 4.dp, bottom = 4.dp),
                    )
                }
            }
            GradientDivider()
            SettingsToggle(
                stringResource(R.string.settings_contacts_only),
                stringResource(R.string.settings_contacts_only_desc),
                Icons.Default.PhoneLocked,
                contactsOnly,
            ) { viewModel.setContactsOnly(it) }
            GradientDivider()
            SettingsToggle(
                stringResource(R.string.settings_outgoing_risk_warning),
                stringResource(R.string.settings_outgoing_risk_warning_desc),
                Icons.Default.WarningAmber,
                outgoingRiskWarning,
                onCheckedChange = viewModel::setOutgoingRiskWarning,
            )
            if (outgoingRiskWarning && !overlayGranted) {
                Text(
                    stringResource(R.string.settings_outgoing_risk_warning_overlay_required),
                    style = MaterialTheme.typography.labelSmall,
                    color = CatPeach,
                    modifier = Modifier.padding(start = 44.dp, end = 4.dp, bottom = 4.dp),
                )
            }
            GradientDivider()
            PremiumActionButton(
                label = stringResource(R.string.settings_region_cnap_rules),
                icon = Icons.Default.Public,
                color = CatBlue,
                onClick = { showRegionCnapRules = true },
                modifier = Modifier.fillMaxWidth(),
                outlined = true,
            )
            Text(
                stringResource(
                    R.string.settings_region_cnap_summary,
                    if (regionBlockEnabled) allowedRegions.size else 0,
                    cnapTrustPatterns.size,
                    cnapBlockPatterns.size,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = CatSubtext,
                modifier = Modifier.padding(start = 4.dp),
            )
            GradientDivider()
            SettingsToggle(
                stringResource(R.string.settings_post_call_screen),
                stringResource(R.string.settings_post_call_screen_desc),
                Icons.AutoMirrored.Filled.PhoneCallback,
                postCallScreen,
                onCheckedChange = viewModel::setPostCallScreen,
            )
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
                    valueText =
                        pluralStringResource(
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
                stringResource(R.string.settings_urlhaus_remote_lookup),
                stringResource(R.string.settings_urlhaus_remote_lookup_desc),
                Icons.Default.Security,
                urlhausRemoteLookup,
            ) { viewModel.setUrlhausRemoteLookup(it) }
            GradientDivider()
            SettingsToggle(
                stringResource(R.string.settings_live_caller_enrichment),
                stringResource(R.string.settings_live_caller_enrichment_desc),
                Icons.Default.TravelExplore,
                liveCallerEnrichment,
            ) { viewModel.setLiveCallerEnrichment(it) }
            GradientDivider()
            SettingsToggle(
                stringResource(R.string.settings_sms_burst),
                stringResource(R.string.settings_sms_burst_desc),
                Icons.Default.SmsFailed,
                smsBurst,
            ) { viewModel.setSmsBurst(it) }
            GradientDivider()
            SettingsToggle(
                stringResource(R.string.settings_repeat_caller),
                stringResource(R.string.settings_repeat_caller_desc, freqThreshold),
                Icons.Default.Repeat,
                freqEscalation,
            ) { viewModel.setFreqEscalation(it) }
            if (freqEscalation) {
                Spacer(Modifier.height(8.dp))
                SettingsNumberStepper(
                    label = stringResource(R.string.settings_repeat_caller_threshold),
                    valueText =
                        pluralStringResource(
                            R.plurals.settings_repeat_caller_threshold_value,
                            freqThreshold,
                            freqThreshold,
                        ),
                    value = freqThreshold,
                    minValue = FREQ_THRESHOLD_MIN,
                    maxValue = FREQ_THRESHOLD_MAX,
                    onValueChange = { viewModel.setFreqThreshold(it) },
                )
            }
            GradientDivider()
            SettingsToggle(stringResource(R.string.settings_ml_scorer), stringResource(R.string.settings_ml_scorer_desc), Icons.Default.SmartToy, mlScorer) { viewModel.setMlScorer(it) }
            GradientDivider()
            SettingsToggle(
                stringResource(R.string.settings_db_prefix_expansion),
                stringResource(R.string.settings_db_prefix_expansion_desc),
                Icons.AutoMirrored.Filled.CallSplit,
                dbPrefixExpansion,
            ) { viewModel.setDbPrefixExpansion(it) }
            GradientDivider()
            SettingsToggle(stringResource(R.string.settings_rcs_filter), stringResource(R.string.settings_rcs_filter_desc), Icons.Default.MarkChatRead, rcsFilter) { viewModel.setRcsFilter(it) }
            if (rcsFilter) {
                Spacer(Modifier.height(4.dp))
                PremiumActionButton(
                    label = stringResource(R.string.settings_notification_screening_sources),
                    icon = Icons.Default.Tune,
                    color = CatMauve,
                    onClick = { showNotificationScreeningSources = true },
                    modifier = Modifier.fillMaxWidth(),
                    outlined = true,
                )
                Text(
                    stringResource(
                        R.string.settings_notification_screening_sources_count,
                        notificationScreeningPackages.size,
                        com.sysadmindoc.callshield.data.NotificationScreeningSources.catalog.size,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = CatSubtext,
                    modifier = Modifier.padding(start = 4.dp),
                )
                PremiumActionButton(
                    label = stringResource(R.string.settings_grant_notification_access),
                    icon = Icons.Default.NotificationsActive,
                    color = CatMauve,
                    onClick = { context.startActivitySafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth(),
                    outlined = true,
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
                pushAlertEnabled,
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
                    outlined = true,
                )
                Text(
                    stringResource(
                        R.string.settings_push_alert_sources_count,
                        activeSources,
                        totalSources,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = CatSubtext,
                    modifier = Modifier.padding(start = 4.dp),
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
                silentVoicemail,
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
                            selected = cleanupDays == days,
                            onClick = { viewModel.setCleanupDays(days) },
                            label = { Text(stringResource(R.string.settings_days, days)) },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, if (cleanupDays == days) CatGreen.copy(alpha = 0.3f) else CatMuted.copy(alpha = 0.3f)),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CatGreen.copy(alpha = 0.2f), selectedLabelColor = CatGreen),
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
                onClick = {
                    hapticTick(context)
                    viewModel.exportLog()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.settings_export_csv_desc), style = MaterialTheme.typography.labelSmall, color = CatSubtext)
            Spacer(Modifier.height(8.dp))
            PremiumActionButton(
                label = stringResource(R.string.settings_export_raw_sms_csv),
                icon = Icons.Default.Warning,
                color = CatPeach,
                onClick = {
                    hapticTick(context)
                    showRawSmsExportDialog = true
                },
                modifier = Modifier.fillMaxWidth(),
                outlined = true,
            )
            Text(
                stringResource(R.string.settings_export_raw_sms_csv_desc),
                style = MaterialTheme.typography.labelSmall,
                color = CatSubtext,
            )
        }

        // Backup/restore
        SettingsCard(stringResource(R.string.settings_backup_restore)) {
            // Section choices must survive recreation: the document picker is a
            // separate activity, so rotating (or being killed in the background)
            // while it is open otherwise silently reverts these to the defaults
            // and restores sections the user had explicitly deselected.
            var backupSections by
                rememberSaveable(stateSaver = BackupSectionSetSaver) {
                    mutableStateOf(BackupRestore.defaultExportSections)
                }
            var restoreSections by
                rememberSaveable(stateSaver = BackupSectionSetSaver) {
                    mutableStateOf(BackupRestore.defaultRestoreSections)
                }
            // Passphrases are deliberately NOT saved: saved instance state is
            // persisted to disk. If recreation drops one, the restore reports
            // "passphrase required" and the user re-enters it.
            var backupProtection by remember { mutableStateOf(BackupProtectionForm()) }
            var restorePassphrase by remember { mutableStateOf("") }
            val restoreLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let {
                        viewModel.restore(
                            it,
                            restoreSections,
                            restorePassphrase.toCharArray().takeIf(CharArray::isNotEmpty),
                        )
                        restorePassphrase = ""
                    }
                }
            val restoreResult by viewModel.restoreResult.collectAsStateWithLifecycle()
            val restorePreview by viewModel.restorePreview.collectAsStateWithLifecycle()

            BackupSectionPicker(
                title = stringResource(R.string.settings_backup_sections_title),
                selectedSections = backupSections,
                onSelectedSectionsChange = { backupSections = it },
            )
            Spacer(Modifier.height(8.dp))
            BackupProtectionControls(
                form = backupProtection,
                onFormChange = { backupProtection = it },
            )
            Spacer(Modifier.height(8.dp))
            BackupSectionPicker(
                title = stringResource(R.string.settings_restore_sections_title),
                selectedSections = restoreSections,
                onSelectedSectionsChange = {
                    restoreSections = it
                    viewModel.clearRestorePreview()
                },
            )
            Spacer(Modifier.height(8.dp))
            RestorePassphraseField(
                passphrase = restorePassphrase,
                onPassphraseChange = {
                    restorePassphrase = it.take(PortableBackupCrypto.MAX_PASSPHRASE_LENGTH)
                    viewModel.clearRestorePreview()
                },
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PremiumActionButton(
                    label = stringResource(R.string.settings_backup),
                    icon = Icons.Default.Backup,
                    color = CatGreen,
                    onClick = {
                        hapticTick(context)
                        viewModel.backup(
                            backupSections,
                            backupProtection.passphrase.toCharArray().takeIf { backupProtection.enabled },
                        )
                        backupProtection = backupProtection.copy(passphrase = "", confirmation = "")
                    },
                    enabled = backupSections.isNotEmpty() && backupProtection.isValid,
                    modifier = Modifier.weight(1f),
                )
                PremiumActionButton(
                    label = stringResource(R.string.settings_restore),
                    icon = Icons.Default.Restore,
                    color = CatBlue,
                    onClick = {
                        hapticTick(context)
                        restoreLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    },
                    enabled = restoreSections.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    outlined = true,
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
            restoreResult?.let { status ->
                Spacer(Modifier.height(4.dp))
                Text(
                    status.text,
                    // Announce the restore outcome: it is the only feedback for
                    // a destructive, data-replacing operation, and it used to
                    // appear and disappear silently.
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.success) CatGreen else CatPeach,
                )
                LaunchedEffect(status) {
                    // Long enough for a screen reader to reach and read it.
                    kotlinx.coroutines.delay(12_000)
                    viewModel.clearRestoreResult()
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.settings_backup_includes), style = MaterialTheme.typography.labelSmall, color = CatSubtext)
            if (
                BackupRestore.BackupSection.LOGS in backupSections ||
                BackupRestore.BackupSection.LOGS in restoreSections
            ) {
                Text(
                    stringResource(R.string.settings_backup_logs_privacy),
                    style = MaterialTheme.typography.labelSmall,
                    color = CatPeach,
                )
            }
        }

        // External blocklist subscriptions (Pi-hole-style URL feeds)
        ExternalBlocklistSettings(
            url = externalBlocklistUrl,
            label = externalBlocklistLabel,
            subscriptions = externalBlocklists,
            preview = externalBlocklistPreview,
            result = externalBlocklistResult,
            onUrlChange = { externalBlocklistUrl = it },
            onLabelChange = { externalBlocklistLabel = it },
            onPreview = {
                hapticTick(context)
                viewModel.previewExternalBlocklist(externalBlocklistUrl, externalBlocklistLabel)
            },
            onApply = {
                hapticTick(context)
                viewModel.applyExternalBlocklist(externalBlocklistUrl, externalBlocklistLabel)
            },
            onApplyPreview = { preview ->
                hapticTick(context)
                viewModel.applyExternalBlocklist(preview.url, preview.label)
            },
            onToggle = { subscription, enabled ->
                hapticTick(context)
                viewModel.setExternalBlocklistEnabled(subscription, enabled)
            },
            onRemove = { subscription ->
                hapticTick(context)
                viewModel.removeExternalBlocklist(subscription)
            },
            onClearResult = viewModel::clearExternalBlocklistResult,
        )

        // About
        PremiumCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("${stringResource(R.string.app_name)} v${BuildConfig.VERSION_NAME}", color = CatSubtext, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.settings_about_desc), style = MaterialTheme.typography.labelSmall, color = CatSubtext)
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

    if (showNotificationScreeningSources) {
        NotificationScreeningSourcesSheet(
            enabledPackages = notificationScreeningPackages,
            onToggle = viewModel::setNotificationScreeningPackage,
            onReset = viewModel::resetNotificationScreeningPackages,
            onDismiss = { showNotificationScreeningSources = false },
        )
    }

    if (showRegionCnapRules) {
        RegionCnapRulesSheet(
            regionBlockEnabled = regionBlockEnabled,
            allowedRegions = allowedRegions,
            cnapTrustPatterns = cnapTrustPatterns,
            cnapBlockPatterns = cnapBlockPatterns,
            onSave = viewModel::saveRegionAndCnapRules,
            onDismiss = { showRegionCnapRules = false },
        )
    }

    if (showCategoryCallActions) {
        CategoryCallActionsSheet(
            actions = categoryCallActions,
            onActionChange = viewModel::setCategoryCallAction,
            onDismiss = { showCategoryCallActions = false },
        )
    }

    if (showContactGroups) {
        ContactGroupPickerSheet(
            groups = contactGroups,
            selectedKeys = selectedContactGroups,
            loading = contactGroupsLoading,
            permissionGranted = contactsPermissionGranted,
            onSelectionChange = viewModel::setSelectedContactGroups,
            onDismiss = { showContactGroups = false },
        )
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            containerColor = SurfaceBright,
            icon = { Icon(Icons.Default.Palette, contentDescription = null, tint = CatGreen) },
            title = { Text(stringResource(R.string.settings_theme_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AppThemeMode.entries.forEach { option ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = option == appTheme,
                                        role = Role.RadioButton,
                                        onClick = {
                                            viewModel.setAppTheme(option)
                                            showThemeDialog = false
                                        },
                                    ).testTag("settings_theme_option_${option.storageValue}")
                                    .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = option == appTheme,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = CatGreen),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(option.labelResource()),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }

    if (showLanguageDialog) {
        val languageOptions = AppLanguage.options()
        val currentLanguageTag = AppLanguage.currentLanguageTag()
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            containerColor = SurfaceBright,
            icon = { Icon(Icons.Default.Language, contentDescription = null, tint = CatBlue) },
            title = { Text(stringResource(R.string.settings_language_dialog_title)) },
            text = {
                Column {
                    languageOptions.forEach { option ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    // selectable (not clickable) so TalkBack
                                    // announces the radio role and which
                                    // language is currently active — matching
                                    // the theme dialog above.
                                    .selectable(
                                        selected = option.languageTag == currentLanguageTag,
                                        role = Role.RadioButton,
                                        onClick = {
                                            showLanguageDialog = false
                                            AppLanguage.selectLanguage(option.languageTag)
                                        },
                                    ).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = option.languageTag == currentLanguageTag,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = CatBlue),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(option.labelRes))
                        }
                    }
                    if (BuildConfig.DEBUG) {
                        Text(
                            stringResource(R.string.settings_language_debug_only),
                            style = MaterialTheme.typography.labelSmall,
                            color = CatSubtext,
                        )
                    }
                }
            },
            confirmButton = {},
        )
    }

    if (showRawSmsExportDialog) {
        AlertDialog(
            onDismissRequest = { showRawSmsExportDialog = false },
            containerColor = SurfaceBright,
            icon = {
                Icon(
                    Icons.Default.Warning,
                    null,
                    tint = CatPeach,
                    modifier = Modifier.size(32.dp),
                )
            },
            title = { Text(stringResource(R.string.settings_export_raw_sms_confirm_title)) },
            text = { Text(stringResource(R.string.settings_export_raw_sms_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRawSmsExportDialog = false
                        viewModel.exportLog(includeRawSmsBodies = true)
                    },
                ) {
                    Text(stringResource(R.string.settings_export_raw_sms_confirm_action), color = CatPeach)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRawSmsExportDialog = false }) {
                    Text(
                        stringResource(R.string.settings_export_raw_sms_cancel),
                        color = CatSubtext,
                    )
                }
            },
        )
    }
}

/**
 * Persists a backup section selection across activity recreation by name.
 * Unknown names are dropped so a downgrade can't crash the restore form.
 */
private val BackupSectionSetSaver: Saver<Set<BackupRestore.BackupSection>, ArrayList<String>> =
    Saver(
        save = { sections -> ArrayList(sections.map(BackupRestore.BackupSection::name)) },
        restore = { names ->
            names.mapNotNullTo(linkedSetOf()) { name ->
                BackupRestore.BackupSection.entries.firstOrNull { it.name == name }
            }
        },
    )

internal data class BackupProtectionForm(
    val enabled: Boolean = false,
    val passphrase: String = "",
    val confirmation: String = "",
) {
    val isValid: Boolean
        get() =
            !enabled ||
                (
                    passphrase.length in
                        PortableBackupCrypto.MIN_PASSPHRASE_LENGTH..PortableBackupCrypto.MAX_PASSPHRASE_LENGTH &&
                        passphrase == confirmation
                )
}

@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
internal fun BackupProtectionControls(
    form: BackupProtectionForm,
    onFormChange: (BackupProtectionForm) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(SETTINGS_BACKUP_ENCRYPTION_TOGGLE_TAG)
                    .padding(vertical = 4.dp)
                    .toggleable(
                        value = form.enabled,
                        role = Role.Switch,
                        onValueChange = { enabled ->
                            onFormChange(if (enabled) form.copy(enabled = true) else BackupProtectionForm())
                        },
                    ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = if (form.enabled) CatGreen else CatOverlay)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_backup_encrypt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = CatText,
                )
                Text(
                    stringResource(R.string.settings_backup_encrypt_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = CatSubtext,
                )
            }
            Switch(
                checked = form.enabled,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedTrackColor = CatGreen),
            )
        }
        if (form.enabled) {
            BackupPassphraseFields(form, onFormChange)
        }
    }
}

@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
private fun BackupPassphraseFields(
    form: BackupProtectionForm,
    onFormChange: (BackupProtectionForm) -> Unit,
) {
    val tooShort = form.passphrase.isNotEmpty() && form.passphrase.length < PortableBackupCrypto.MIN_PASSPHRASE_LENGTH
    val mismatch = form.confirmation.isNotEmpty() && form.confirmation != form.passphrase
    OutlinedTextField(
        value = form.passphrase,
        onValueChange = {
            onFormChange(form.copy(passphrase = it.take(PortableBackupCrypto.MAX_PASSPHRASE_LENGTH)))
        },
        modifier = Modifier.fillMaxWidth().testTag(SETTINGS_BACKUP_PASSPHRASE_TAG),
        label = { Text(stringResource(R.string.settings_backup_passphrase)) },
        supportingText = {
            Text(
                stringResource(
                    R.string.settings_backup_passphrase_hint,
                    PortableBackupCrypto.MIN_PASSPHRASE_LENGTH,
                ),
            )
        },
        leadingIcon = { Icon(Icons.Default.Password, contentDescription = null) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        isError = tooShort,
    )
    OutlinedTextField(
        value = form.confirmation,
        onValueChange = {
            onFormChange(form.copy(confirmation = it.take(PortableBackupCrypto.MAX_PASSPHRASE_LENGTH)))
        },
        modifier = Modifier.fillMaxWidth().testTag(SETTINGS_BACKUP_CONFIRM_TAG),
        label = { Text(stringResource(R.string.settings_backup_passphrase_confirm)) },
        supportingText = { if (mismatch) Text(stringResource(R.string.settings_backup_passphrase_mismatch)) },
        leadingIcon = { Icon(Icons.Default.Password, contentDescription = null) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        isError = mismatch,
    )
    Text(
        stringResource(R.string.settings_backup_passphrase_not_saved),
        style = MaterialTheme.typography.labelSmall,
        color = CatPeach,
    )
}

@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
private fun RestorePassphraseField(
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = passphrase,
        onValueChange = onPassphraseChange,
        modifier = Modifier.fillMaxWidth().testTag(SETTINGS_RESTORE_PASSPHRASE_TAG),
        label = { Text(stringResource(R.string.settings_restore_passphrase)) },
        supportingText = { Text(stringResource(R.string.settings_restore_passphrase_hint)) },
        leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
    )
}

@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
private fun BackupSectionPicker(
    title: String,
    selectedSections: Set<BackupRestore.BackupSection>,
    onSelectedSectionsChange: (Set<BackupRestore.BackupSection>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = CatSubtext)
        backupSectionOrder.forEach { section ->
            val selected = section in selectedSections
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { checked ->
                        onSelectedSectionsChange(
                            if (checked) {
                                selectedSections + section
                            } else {
                                selectedSections - section
                            },
                        )
                    },
                    colors = CheckboxDefaults.colors(checkedColor = CatGreen, uncheckedColor = CatOverlay),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        backupSectionTitle(section),
                        style = MaterialTheme.typography.bodySmall,
                        color = CatText,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        backupSectionDescription(section),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (section == BackupRestore.BackupSection.LOGS) CatPeach else CatOverlay,
                    )
                }
            }
        }
    }
}

@Composable
private fun backupSectionTitle(section: BackupRestore.BackupSection): String =
    when (section) {
        BackupRestore.BackupSection.BLOCKED_NUMBERS -> stringResource(R.string.backup_section_blocked)
        BackupRestore.BackupSection.WHITELIST -> stringResource(R.string.backup_section_whitelist)
        BackupRestore.BackupSection.WILDCARD_RULES -> stringResource(R.string.backup_section_wildcards)
        BackupRestore.BackupSection.RANGE_RULES -> stringResource(R.string.backup_section_ranges)
        BackupRestore.BackupSection.KEYWORD_RULES -> stringResource(R.string.backup_section_keywords)
        BackupRestore.BackupSection.SETTINGS -> stringResource(R.string.backup_section_settings)
        BackupRestore.BackupSection.LOGS -> stringResource(R.string.backup_section_logs)
    }

@Composable
private fun backupSectionDescription(section: BackupRestore.BackupSection): String =
    when (section) {
        BackupRestore.BackupSection.BLOCKED_NUMBERS -> stringResource(R.string.backup_section_blocked_desc)
        BackupRestore.BackupSection.WHITELIST -> stringResource(R.string.backup_section_whitelist_desc)
        BackupRestore.BackupSection.WILDCARD_RULES -> stringResource(R.string.backup_section_wildcards_desc)
        BackupRestore.BackupSection.RANGE_RULES -> stringResource(R.string.backup_section_ranges_desc)
        BackupRestore.BackupSection.KEYWORD_RULES -> stringResource(R.string.backup_section_keywords_desc)
        BackupRestore.BackupSection.SETTINGS -> stringResource(R.string.backup_section_settings_desc)
        BackupRestore.BackupSection.LOGS -> stringResource(R.string.backup_section_logs_desc)
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
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(SETTINGS_RESTORE_PREVIEW_TAG),
        shape = RoundedCornerShape(12.dp),
        color = CatBlue.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, CatBlue.copy(alpha = 0.20f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                PremiumIconTile(icon = Icons.Default.Restore, color = CatBlue)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
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
                            counts.rangeRules,
                            counts.settings,
                            counts.logs,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = CatSubtext,
                    )
                }
            }
            if (counts.settings > 0) {
                Text(
                    stringResource(R.string.backup_restore_preview_settings_privacy),
                    style = MaterialTheme.typography.labelSmall,
                    color = CatSubtext,
                )
            }
            if (counts.logs > 0) {
                Text(
                    stringResource(R.string.backup_restore_preview_logs_privacy),
                    style = MaterialTheme.typography.labelSmall,
                    color = CatPeach,
                )
            }
            Text(
                if (conflictTotal > 0) {
                    pluralStringResource(
                        R.plurals.backup_restore_preview_conflicts,
                        conflictTotal,
                        conflictTotal,
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
                color = CatSubtext,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PremiumActionButton(
                    label = stringResource(R.string.backup_restore_merge),
                    icon = Icons.Default.Restore,
                    color = CatBlue,
                    onClick = onMerge,
                    modifier = Modifier.weight(1f),
                    outlined = true,
                )
                PremiumActionButton(
                    label = stringResource(R.string.backup_restore_replace),
                    icon = Icons.Default.DeleteSweep,
                    color = CatPeach,
                    onClick = onReplace,
                    modifier = Modifier.weight(1f),
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
@Suppress("FunctionNaming", "LongMethod", "LongParameterList", "ktlint:standard:function-naming")
private fun ExternalBlocklistSettings(
    url: String,
    label: String,
    subscriptions: List<ExternalBlocklistSubscription>,
    preview: ExternalBlocklistPreview?,
    result: StatusMessage?,
    onUrlChange: (String) -> Unit,
    onLabelChange: (String) -> Unit,
    onPreview: () -> Unit,
    onApply: () -> Unit,
    onApplyPreview: (ExternalBlocklistPreview) -> Unit,
    onToggle: (ExternalBlocklistSubscription, Boolean) -> Unit,
    onRemove: (ExternalBlocklistSubscription) -> Unit,
    onClearResult: () -> Unit,
) {
    SettingsCard(stringResource(R.string.settings_external_blocklists)) {
        Text(
            stringResource(R.string.settings_external_blocklists_desc),
            style = MaterialTheme.typography.bodySmall,
            color = CatSubtext,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            label = { Text(stringResource(R.string.settings_external_blocklist_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Default.Link, contentDescription = null, tint = CatBlue) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = RoundedCornerShape(8.dp),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CatBlue,
                    unfocusedBorderColor = CardBorderAccent,
                    focusedLabelColor = CatBlue,
                    cursorColor = CatBlue,
                ),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = label,
            onValueChange = onLabelChange,
            label = { Text(stringResource(R.string.settings_external_blocklist_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null, tint = CatMauve) },
            shape = RoundedCornerShape(8.dp),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CatMauve,
                    unfocusedBorderColor = CardBorderAccent,
                    focusedLabelColor = CatMauve,
                    cursorColor = CatMauve,
                ),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PremiumActionButton(
                label = stringResource(R.string.settings_external_blocklist_preview),
                icon = Icons.Default.Search,
                color = CatBlue,
                onClick = onPreview,
                enabled = url.isNotBlank(),
                modifier = Modifier.weight(1f),
                outlined = true,
            )
            PremiumActionButton(
                label = stringResource(R.string.settings_external_blocklist_apply),
                icon = Icons.Default.Save,
                color = CatGreen,
                onClick = onApply,
                enabled = url.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
        }
        preview?.let {
            // The panel commits the feed it PREVIEWED (url/label captured in
            // the preview object) — not whatever is currently typed in the
            // URL field, which the user may have edited since previewing.
            ExternalBlocklistPreviewPanel(preview = it, onApply = { onApplyPreview(it) })
        }
        result?.let { status ->
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    status.text,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (status.success) {
                            CatGreen
                        } else {
                            CatPeach
                        },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClearResult) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close), tint = CatOverlay)
                }
            }
        }
        if (subscriptions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            GradientDivider()
            Spacer(Modifier.height(8.dp))
            subscriptions.forEachIndexed { index, subscription ->
                ExternalBlocklistSubscriptionRow(
                    subscription = subscription,
                    onToggle = { enabled -> onToggle(subscription, enabled) },
                    onRemove = { onRemove(subscription) },
                )
                if (index < subscriptions.lastIndex) {
                    GradientDivider()
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
private fun ExternalBlocklistPreviewPanel(
    preview: ExternalBlocklistPreview,
    onApply: () -> Unit,
) {
    Spacer(Modifier.height(10.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = CatBlue.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, CatBlue.copy(alpha = 0.20f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                PremiumIconTile(icon = Icons.Default.Link, color = CatBlue)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.settings_external_blocklist_preview_title, preview.label),
                        style = MaterialTheme.typography.titleSmall,
                        color = CatText,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(
                            R.string.settings_external_blocklist_preview_summary,
                            preview.format.uppercase(),
                            preview.numberCount,
                            preview.added,
                            preview.removed,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = CatSubtext,
                    )
                    Text(
                        stringResource(
                            R.string.settings_external_blocklist_preview_skips,
                            preview.skippedRows,
                            preview.blockedByOtherSources,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = CatSubtext,
                    )
                }
            }
            PremiumActionButton(
                label = stringResource(R.string.settings_external_blocklist_commit_preview),
                icon = Icons.Default.Save,
                color = CatGreen,
                onClick = onApply,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
private fun ExternalBlocklistSubscriptionRow(
    subscription: ExternalBlocklistSubscription,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PremiumIconTile(
            icon = if (subscription.enabled) Icons.Default.Link else Icons.Default.LinkOff,
            color = if (subscription.enabled) CatGreen else CatOverlay,
            size = 38.dp,
            iconSize = 20.dp,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(subscription.label, style = MaterialTheme.typography.bodyMedium, color = CatText)
            Text(subscription.url, style = MaterialTheme.typography.labelSmall, color = CatSubtext)
            Text(
                stringResource(
                    R.string.settings_external_blocklist_subscription_stats,
                    subscription.lastNumberCount,
                    subscription.lastAdded,
                    subscription.lastRemoved,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = if (subscription.enabled) CatGreen else CatOverlay,
            )
            if (subscription.lastError.isNotBlank()) {
                Text(subscription.lastError, style = MaterialTheme.typography.labelSmall, color = CatPeach)
            }
        }
        Switch(
            checked = subscription.enabled,
            onCheckedChange = onToggle,
            // Associate the switch with its feed — enabling or disabling a whole
            // blocklist subscription should never be announced without a target.
            modifier = Modifier.semantics { contentDescription = subscription.label },
            colors =
                SwitchDefaults.colors(
                    checkedTrackColor = CatGreen,
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                ),
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_delete), tint = CatPeach)
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
    Column {
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
                    valueText =
                        pluralStringResource(
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
                    valueText =
                        pluralStringResource(
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
private const val HOURS_PER_DAY = 24
private const val SECONDS_PER_HOUR = 3_600

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
            onCheckedChange = onEnabledChange,
        )
        if (enabled) {
            val durationHours =
                if (startHour == endHour) {
                    HOURS_PER_DAY
                } else {
                    (endHour - startHour + HOURS_PER_DAY) % HOURS_PER_DAY
                }
            val durationText = pluralStringResource(R.plurals.duration_hours, durationHours, durationHours)
            val quietPeriodText = stringResource(R.string.settings_quiet_period_duration, durationText)
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
            Spacer(Modifier.height(8.dp))
            DurationTtsText(
                text = quietPeriodText,
                durationText = durationText,
                durationSeconds = durationHours * SECONDS_PER_HOUR,
                style = MaterialTheme.typography.bodySmall,
                color = CatSubtext,
            )
            if (startHour == endHour) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_quiet_hours_all_day_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = CatYellow,
                )
            }
        }
    }
}

@Composable
fun HourPicker(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val use24Hour =
        android.text.format.DateFormat
            .is24HourFormat(LocalContext.current)
    val label = formatHourLabel(selected, use24Hour)

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = SurfaceBright),
        ) {
            Text(label, color = CatText)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (h in 0..23) {
                val l = formatHourLabel(h, use24Hour)
                DropdownMenuItem(text = { Text(l) }, onClick = {
                    onSelect(h)
                    expanded = false
                })
            }
        }
    }
}

internal fun formatHourLabel(
    hour: Int,
    use24Hour: Boolean = false,
    locale: java.util.Locale = java.util.Locale.getDefault(),
): String =
    java.time.LocalTime
        .of(hour, 0)
        .format(
            java.time.format.DateTimeFormatter
                .ofPattern(if (use24Hour) "HH:mm" else "h a", locale),
        )

@Composable
fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        SectionHeader(title)
        Spacer(Modifier.height(4.dp))
        content()
        Spacer(Modifier.height(4.dp))
        GradientDivider()
    }
}

private fun AppThemeMode.labelResource(): Int =
    when (this) {
        AppThemeMode.System -> R.string.settings_theme_system
        AppThemeMode.Light -> R.string.settings_theme_light
        AppThemeMode.Graphite -> R.string.settings_theme_graphite
        AppThemeMode.Amoled -> R.string.settings_theme_amoled
    }

@Composable
@Suppress("FunctionNaming", "LongParameterList")
private fun SettingsLinkRow(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tintColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PremiumIconTile(icon = icon, color = tintColor, size = 34.dp, iconSize = 18.dp)
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = CatSubtext)
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = CatOverlay,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
fun SettingsToggle(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    tintColor: androidx.compose.ui.graphics.Color = CatSubtext,
    toggleTag: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    // Row-level toggleable + onCheckedChange = null on the Switch: the whole
    // row is tappable and TalkBack reads title, subtitle, and switch state as
    // ONE node instead of inert text plus an unlabeled switch. Same pattern
    // as ContactGroupPickerSheet / BackupProtectionControls.
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = {
                        hapticTick(context)
                        onCheckedChange(it)
                    },
                ).let { if (toggleTag != null) it.testTag(toggleTag) else it }
                .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PremiumIconTile(icon = icon, color = tintColor, size = 34.dp, iconSize = 18.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = CatSubtext,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
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

@Suppress("LongParameterList")
@Composable
private fun PermissionAccessRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    ready: Boolean,
    readyLabel: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PremiumIconTile(
            icon = icon,
            color = if (ready) CatGreen else CatSubtext,
            size = 36.dp,
            iconSize = 19.dp,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = CatText,
        )
        if (ready) {
            val badgeColor =
                if (readyLabel == stringResource(R.string.settings_access_optional)) {
                    CatOverlay
                } else {
                    CatGreen
                }
            StatusPill(readyLabel, badgeColor)
        } else {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) {
                Text(actionLabel, color = CatGreen, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
