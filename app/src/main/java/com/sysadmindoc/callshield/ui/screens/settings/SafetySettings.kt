@file:Suppress("TooManyFunctions", "ktlint:standard:function-naming")

package com.sysadmindoc.callshield.ui.screens.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.ui.ContactsOnlyPausedNote
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.*

/** Who always gets through, and checks on calls you place. */
@Composable
internal fun SafetySettings(
    viewModel: MainViewModel,
    contactsPermissionGranted: Boolean,
    onContactsPermissionResult: (Boolean) -> Unit,
    allowContacts: () -> Unit,
    overlayGranted: Boolean,
    redirectionRoleHeld: Boolean,
    redirectionRoleAvailable: Boolean,
    onRequestRedirectionRole: () -> Unit,
) {
    val contactWhitelist by viewModel.contactWhitelistEnabled.collectAsStateWithLifecycle()
    val contactsOnly by viewModel.contactsOnlyEnabled.collectAsStateWithLifecycle()
    val selectedContactGroups by viewModel.selectedContactGroups.collectAsStateWithLifecycle()
    val contactGroups by viewModel.contactGroups.collectAsStateWithLifecycle()
    val contactGroupsLoading by viewModel.contactGroupsLoading.collectAsStateWithLifecycle()
    val outgoingRiskWarning by viewModel.outgoingRiskWarningEnabled.collectAsStateWithLifecycle()
    val outgoingCallHold by viewModel.outgoingCallHoldEnabled.collectAsStateWithLifecycle()
    val regionBlockEnabled by viewModel.regionBlockEnabled.collectAsStateWithLifecycle()
    val allowedRegions by viewModel.allowedRegions.collectAsStateWithLifecycle()
    val cnapTrustPatterns by viewModel.cnapTrustPatterns.collectAsStateWithLifecycle()
    val cnapBlockPatterns by viewModel.cnapBlockPatterns.collectAsStateWithLifecycle()
    var showContactGroups by rememberSaveable { mutableStateOf(false) }
    var showRegionCnapRules by rememberSaveable { mutableStateOf(false) }
    val contactsPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            onContactsPermissionResult(granted)
            viewModel.refreshContactGroups()
            if (granted) showContactGroups = true
        }

    LaunchedEffect(contactsPermissionGranted) {
        if (contactsPermissionGranted) viewModel.refreshContactGroups()
    }

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
        if (contactsOnly && !contactsPermissionGranted) {
            ContactsOnlyPausedNote(onAllow = allowContacts, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        }
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
        SettingsToggle(
            stringResource(R.string.settings_outgoing_call_hold),
            stringResource(
                if (redirectionRoleAvailable) {
                    R.string.settings_outgoing_call_hold_desc
                } else {
                    R.string.settings_outgoing_call_hold_unavailable
                },
            ),
            Icons.Default.PhonePaused,
            outgoingCallHold && redirectionRoleHeld,
            toggleTag = SETTINGS_OUTGOING_CALL_HOLD_TAG,
            onCheckedChange = { enable ->
                when {
                    !enable -> {
                        viewModel.setOutgoingCallHold(false)
                    }

                    redirectionRoleHeld -> {
                        viewModel.setOutgoingCallHold(true)
                    }

                    redirectionRoleAvailable -> {
                        onRequestRedirectionRole()
                    }
                }
            },
        )
        if (outgoingCallHold && !redirectionRoleHeld && redirectionRoleAvailable) {
            Text(
                stringResource(R.string.settings_outgoing_call_hold_role_missing),
                style = MaterialTheme.typography.labelSmall,
                color = CatPeach,
                modifier = Modifier.padding(start = 44.dp, end = 4.dp, bottom = 4.dp),
            )
        }
        GradientDivider()
        SettingsLinkRow(
            title = stringResource(R.string.settings_region_cnap_rules),
            value =
                stringResource(
                    R.string.settings_region_cnap_summary,
                    if (regionBlockEnabled) allowedRegions.size else 0,
                    cnapTrustPatterns.size,
                    cnapBlockPatterns.size,
                ),
            icon = Icons.Default.Public,
            stackValue = true,
            onClick = { showRegionCnapRules = true },
        )
    }

    if (showRegionCnapRules) {
        val cnapUnavailable by produceState(initialValue = false) {
            value = viewModel.readCallerNameUnavailable()
        }
        RegionCnapRulesSheet(
            regionBlockEnabled = regionBlockEnabled,
            allowedRegions = allowedRegions,
            cnapTrustPatterns = cnapTrustPatterns,
            cnapBlockPatterns = cnapBlockPatterns,
            callerNameUnavailable = cnapUnavailable,
            onSave = viewModel::saveRegionAndCnapRules,
            onDismiss = { showRegionCnapRules = false },
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
}
