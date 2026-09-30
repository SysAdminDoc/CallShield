@file:Suppress("TooManyFunctions", "ktlint:standard:function-naming")

package com.sysadmindoc.callshield.ui.screens.settings

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sysadmindoc.callshield.BuildConfig
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.BackupRestore
import com.sysadmindoc.callshield.data.BlockingProfiles
import com.sysadmindoc.callshield.permissions.CallShieldPermissions
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.rememberAllowContacts
import com.sysadmindoc.callshield.ui.screens.main.requiredSetupProgress
import com.sysadmindoc.callshield.ui.theme.*
import com.sysadmindoc.callshield.util.startActivitySafely
import kotlinx.coroutines.launch

internal const val SETTINGS_QUIET_HOURS_TOGGLE_TAG = "settings_quiet_hours_toggle"
internal const val SETTINGS_ANSWERED_CALLER_TOGGLE_TAG = "settings_answered_caller_toggle"
internal const val SETTINGS_RESTORE_PREVIEW_TAG = "settings_restore_preview"
internal const val SETTINGS_THEME_ROW_TAG = "settings_theme_row"
internal const val SETTINGS_BACKUP_ENCRYPTION_TOGGLE_TAG = "settings_backup_encryption_toggle"
internal const val SETTINGS_BACKUP_PASSPHRASE_TAG = "settings_backup_passphrase"
internal const val SETTINGS_BACKUP_CONFIRM_TAG = "settings_backup_confirm"
internal const val SETTINGS_RESTORE_PASSPHRASE_TAG = "settings_restore_passphrase"
internal const val SETTINGS_CONTACT_SCOPE_TAG = "settings_contact_scope"
internal const val SETTINGS_REGULATORY_PREFIX_TAG_PREFIX = "settings_regulatory_prefix:"
internal const val SETTINGS_MEETING_MODE_TOGGLE_TAG = "settings_meeting_mode_toggle"
internal const val SETTINGS_OUTGOING_CALL_HOLD_TAG = "settings_outgoing_call_hold_toggle"
internal const val SETTINGS_BASIC_TAB_TAG = "settings_basic_tab"
internal const val SETTINGS_ADVANCED_TAB_TAG = "settings_advanced_tab"

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val blockCalls by viewModel.blockCallsEnabled.collectAsStateWithLifecycle()
    val blockSms by viewModel.blockSmsEnabled.collectAsStateWithLifecycle()
    val blockUnknown by viewModel.blockUnknownEnabled.collectAsStateWithLifecycle()
    val contactsOnly by viewModel.contactsOnlyEnabled.collectAsStateWithLifecycle()
    val callHoldNotGranted = stringResource(R.string.settings_outgoing_call_hold_not_granted)
    val enabledRegulatoryPrefixes by viewModel.enabledRegulatoryPrefixes.collectAsStateWithLifecycle()
    val aggressiveMode by viewModel.aggressiveModeEnabled.collectAsStateWithLifecycle()
    val timeBlock by viewModel.timeBlockEnabled.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val timeStart by viewModel.timeBlockStart.collectAsStateWithLifecycle()
    val timeEnd by viewModel.timeBlockEnd.collectAsStateWithLifecycle()
    val profileSettings =
        BlockingProfiles.Settings(
            blockCalls = blockCalls,
            analyzeSms = blockSms,
            blockHidden = blockUnknown,
            aggressive = aggressiveMode,
            quietHours = timeBlock,
            contactsOnly = contactsOnly,
        )
    val displayedProfile =
        activeProfile?.takeIf { it.settings == profileSettings }
            ?: BlockingProfiles.Profile.entries.firstOrNull { it.settings == profileSettings }
    val profileScope = rememberCoroutineScope()
    val profileSnackbar = remember { SnackbarHostState() }
    val profileRestoredMessage = stringResource(R.string.settings_profile_restored)
    val profileFailedMessage = stringResource(R.string.profile_failed)
    val profileUndoLabel = stringResource(R.string.profile_undo)
    var profileApplying by remember { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    fun restoreRecommended() {
        if (profileApplying) return
        profileApplying = true
        profileScope.launch {
            try {
                val previous = viewModel.applyProfile(BlockingProfiles.Profile.WORK)
                profileApplying = false
                profileSnackbar.currentSnackbarData?.dismiss()
                if (profileSnackbar.showSnackbar(profileRestoredMessage, actionLabel = profileUndoLabel, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                    profileApplying = true
                    viewModel.undoProfile(previous)
                    profileApplying = false
                }
            } catch (_: Exception) {
                profileApplying = false
                profileSnackbar.showSnackbar(profileFailedMessage)
            }
        }
    }
    var externalBlocklistUrl by rememberSaveable { mutableStateOf("") }
    var externalBlocklistLabel by rememberSaveable { mutableStateOf("") }
    val feedMirrorUrl by viewModel.feedMirrorUrl.collectAsStateWithLifecycle()
    // Keyed on the stored value so the field shows what was saved, in its normal form.
    var feedMirrorInput by rememberSaveable(feedMirrorUrl) { mutableStateOf(feedMirrorUrl.orEmpty()) }
    // Backup and restore state lives up here, outside the Advanced branch: state
    // remembered inside it was dropped every time the user switched to Basic.
    // Section choices must also survive recreation: the document picker is a
    // separate activity, so rotating (or being killed in the background) while it
    // is open otherwise silently reverts these to the defaults and restores
    // sections the user had explicitly deselected.
    var backupSections by
        rememberSaveable(stateSaver = BackupSectionSetSaver) {
            mutableStateOf(BackupRestore.defaultExportSections)
        }
    var restoreSections by
        rememberSaveable(stateSaver = BackupSectionSetSaver) {
            mutableStateOf(BackupRestore.defaultRestoreSections)
        }
    // Passphrases are deliberately NOT saved: saved instance state is persisted to
    // disk. If recreation drops one, the restore reports "passphrase required" and
    // the user re-enters it.
    var backupProtection by remember { mutableStateOf(BackupProtectionForm()) }
    var restorePassphrase by remember { mutableStateOf("") }

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
    var notificationAccessGranted by remember(context) {
        mutableStateOf(CallShieldPermissions.hasNotificationListenerAccess(context))
    }
    var screenerGranted by remember(roleManager) { mutableStateOf(CallShieldPermissions.hasCallScreeningRole(roleManager)) }
    var redirectionRoleHeld by remember(roleManager) { mutableStateOf(CallShieldPermissions.hasCallRedirectionRole(roleManager)) }
    val redirectionRoleAvailable = remember(roleManager) { CallShieldPermissions.isCallRedirectionRoleAvailable(roleManager) }
    var contactsPermissionGranted by remember(context) {
        mutableStateOf(CallShieldPermissions.isPermissionGranted(context, Manifest.permission.READ_CONTACTS))
    }
    val corePermissionsGranted = missingCorePermissions.isEmpty()
    val screenerReadyForCurrentMode = !blockCalls || screenerGranted
    // Overlay and notifications add polish, but they are not required for the
    // protection engine. Keep optional enhancements out of the core setup
    // score so a configured blocker never looks unfinished.
    val setupProgress = requiredSetupProgress(corePermissionsGranted, screenerReadyForCurrentMode)
    val setupReadyCount = setupProgress.done
    val setupTotal = setupProgress.total
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
    val redirectionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            redirectionRoleHeld = CallShieldPermissions.hasCallRedirectionRole(roleManager)
            if (redirectionRoleHeld) {
                viewModel.setOutgoingCallHold(true)
            } else {
                Toast.makeText(context, callHoldNotGranted, Toast.LENGTH_SHORT).show()
            }
        }

    val allowContacts =
        rememberAllowContacts {
            contactsPermissionGranted = CallShieldPermissions.isPermissionGranted(context, Manifest.permission.READ_CONTACTS)
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
                    notificationAccessGranted = CallShieldPermissions.hasNotificationListenerAccess(context)
                    screenerGranted = CallShieldPermissions.hasCallScreeningRole(roleManager)
                    redirectionRoleHeld = CallShieldPermissions.hasCallRedirectionRole(roleManager)
                    contactsPermissionGranted =
                        CallShieldPermissions.isPermissionGranted(context, Manifest.permission.READ_CONTACTS)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            context.startActivitySafely(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                },
            )
        }
    }

    fun requestScreening() {
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
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PremiumCard(modifier = Modifier.fillMaxWidth(), accentColor = CatGreen) {
            Column(modifier = Modifier.padding(18.dp)) {
                SectionHeader(stringResource(R.string.settings_protection_level), CatGreen)
                Spacer(Modifier.height(8.dp))
                Text(
                    text =
                        displayedProfile?.let { stringResource(it.labelRes) }
                            ?: stringResource(R.string.settings_custom_profile),
                    style = MaterialTheme.typography.titleLarge,
                    color = CatGreen,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text =
                        displayedProfile?.let { stringResource(it.descriptionRes) }
                            ?: stringResource(R.string.profile_custom_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = CatSubtext,
                )
                TextButton(onClick = ::restoreRecommended, enabled = !profileApplying) {
                    Icon(Icons.Default.Restore, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_restore_recommended))
                }
                SnackbarHost(profileSnackbar)
            }
        }

        // Tabs, not filter chips: FilterChip announces itself as a checkbox, and
        // this is a choice between two views of the same page.
        Row(modifier = Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsViewTab(stringResource(R.string.settings_basic), selected = !showAdvanced, tag = SETTINGS_BASIC_TAB_TAG) {
                showAdvanced = false
            }
            SettingsViewTab(stringResource(R.string.settings_advanced), selected = showAdvanced, tag = SETTINGS_ADVANCED_TAB_TAG) {
                showAdvanced = true
            }
        }

        // Home's Review permissions opens Settings, which starts on Basic. While
        // anything required is missing, the access controls show there too
        // instead of hiding behind Advanced.
        if (showAdvanced || setupReadyCount < setupTotal) {
            PermissionsAccessCard(
                setupReadyCount = setupReadyCount,
                setupTotal = setupTotal,
                corePermissionsGranted = corePermissionsGranted,
                screenerReadyForCurrentMode = screenerReadyForCurrentMode,
                blockCalls = blockCalls,
                overlayGranted = overlayGranted,
                notificationsGranted = notificationsGranted,
                onRunSetupAgain = viewModel::restartOnboarding,
                onGrantCore = { permissionLauncher.launch(CallShieldPermissions.corePermissions.toTypedArray()) },
                onEnableScreening = ::requestScreening,
                onEnableNotifications = ::requestNotifications,
            )
        }

        if (!showAdvanced) {
            BlockingSettings(viewModel)
            SafetySettings(
                viewModel = viewModel,
                contactsPermissionGranted = contactsPermissionGranted,
                onContactsPermissionResult = { contactsPermissionGranted = it },
                allowContacts = allowContacts,
                overlayGranted = overlayGranted,
                redirectionRoleHeld = redirectionRoleHeld,
                redirectionRoleAvailable = redirectionRoleAvailable,
                onRequestRedirectionRole = {
                    roleManager?.let { redirectionLauncher.launch(it.createRequestRoleIntent(RoleManager.ROLE_CALL_REDIRECTION)) }
                },
            )
            NotificationSettings(
                viewModel = viewModel,
                notificationsGranted = notificationsGranted,
                onEnableNotifications = ::requestNotifications,
            )
            AppearanceSettings(viewModel)
        }

        if (showAdvanced) {
            BlockedCallSettings(viewModel)
            QuietHoursSettings(
                enabled = timeBlock,
                startHour = timeStart,
                endHour = timeEnd,
                onEnabledChange = { viewModel.setTimeBlock(it) },
                onStartChange = { viewModel.setTimeBlockStart(it) },
                onEndChange = { viewModel.setTimeBlockEnd(it) },
            )
            RegulatoryPrefixSettings(
                enabled = enabledRegulatoryPrefixes,
                onToggle = viewModel::setRegulatoryPrefix,
            )
            DetectionSettings(viewModel)
            MeetingModeSection(viewModel, notificationAccessGranted = notificationAccessGranted)
            PowerModeSettings(viewModel)
            LogSettings(viewModel)
            BackupRestoreSettings(
                viewModel = viewModel,
                backupSections = backupSections,
                onBackupSectionsChange = { backupSections = it },
                restoreSections = restoreSections,
                onRestoreSectionsChange = { restoreSections = it },
                backupProtection = backupProtection,
                onBackupProtectionChange = { backupProtection = it },
                restorePassphrase = restorePassphrase,
                onRestorePassphraseChange = { restorePassphrase = it },
            )
            ExternalBlocklistSection(
                viewModel = viewModel,
                url = externalBlocklistUrl,
                label = externalBlocklistLabel,
                onUrlChange = { externalBlocklistUrl = it },
                onLabelChange = { externalBlocklistLabel = it },
            )
            FeedMirrorSection(
                viewModel = viewModel,
                input = feedMirrorInput,
                onInputChange = { feedMirrorInput = it },
            )

            // About
            PremiumCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("${stringResource(R.string.app_name)} v${BuildConfig.VERSION_NAME}", color = CatSubtext, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.settings_about_desc), style = MaterialTheme.typography.labelSmall, color = CatSubtext)
                }
            }
        }
    }
}
