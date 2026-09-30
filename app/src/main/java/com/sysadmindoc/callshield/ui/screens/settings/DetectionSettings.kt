@file:Suppress("TooManyFunctions", "ktlint:standard:function-naming")

package com.sysadmindoc.callshield.ui.screens.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.PhoneCallback
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.RegulatoryPrefix
import com.sysadmindoc.callshield.data.repository.ANSWERED_CALLER_THRESHOLD_MAX
import com.sysadmindoc.callshield.data.repository.ANSWERED_CALLER_THRESHOLD_MIN
import com.sysadmindoc.callshield.data.repository.ANSWERED_CALLER_WINDOW_DAYS_MAX
import com.sysadmindoc.callshield.data.repository.ANSWERED_CALLER_WINDOW_DAYS_MIN
import com.sysadmindoc.callshield.data.repository.EMERGENCY_CALLBACK_WINDOW_MINUTES_MAX
import com.sysadmindoc.callshield.data.repository.EMERGENCY_CALLBACK_WINDOW_MINUTES_MIN
import com.sysadmindoc.callshield.data.repository.FREQ_THRESHOLD_MAX
import com.sysadmindoc.callshield.data.repository.FREQ_THRESHOLD_MIN
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.*
import com.sysadmindoc.callshield.util.startActivitySafely

/** The checks that decide whether a call or text is spam. */
@Composable
internal fun DetectionSettings(viewModel: MainViewModel) {
    val context = LocalContext.current
    val stirShaken by viewModel.stirShakenEnabled.collectAsStateWithLifecycle()
    val stirTrustedAllow by viewModel.stirTrustedAllowEnabled.collectAsStateWithLifecycle()
    val answeredCallerTrust by viewModel.answeredCallerTrustEnabled.collectAsStateWithLifecycle()
    val answeredCallerThreshold by viewModel.answeredCallerThreshold.collectAsStateWithLifecycle()
    val answeredCallerWindowDays by viewModel.answeredCallerWindowDays.collectAsStateWithLifecycle()
    val emergencyCallbackGrace by viewModel.emergencyCallbackGraceEnabled.collectAsStateWithLifecycle()
    val emergencyCallbackWindowMinutes by viewModel.emergencyCallbackWindowMinutes.collectAsStateWithLifecycle()
    val neighborSpoof by viewModel.neighborSpoofEnabled.collectAsStateWithLifecycle()
    val heuristics by viewModel.heuristicsEnabled.collectAsStateWithLifecycle()
    val smsContent by viewModel.smsContentEnabled.collectAsStateWithLifecycle()
    val smsBurst by viewModel.smsBurstEnabled.collectAsStateWithLifecycle()
    val remoteUrlLookup by viewModel.remoteUrlLookupEnabled.collectAsStateWithLifecycle()
    val liveCallerEnrichment by viewModel.liveCallerEnrichmentEnabled.collectAsStateWithLifecycle()
    val freqEscalation by viewModel.freqEscalationEnabled.collectAsStateWithLifecycle()
    val freqThreshold by viewModel.freqThreshold.collectAsStateWithLifecycle()
    val mlScorer by viewModel.mlScorerEnabled.collectAsStateWithLifecycle()
    val dbPrefixExpansion by viewModel.dbPrefixExpansionEnabled.collectAsStateWithLifecycle()
    val rcsFilter by viewModel.rcsFilterEnabled.collectAsStateWithLifecycle()
    val notificationMessageCapabilityStatus by viewModel.notificationMessageCapabilityStatus.collectAsStateWithLifecycle()
    val notificationScreeningPackages by viewModel.notificationScreeningPackages.collectAsStateWithLifecycle()
    val pushAlertEnabled by viewModel.pushAlertEnabled.collectAsStateWithLifecycle()
    val pushAlertDisabledPackages by viewModel.pushAlertDisabledPackages.collectAsStateWithLifecycle()
    var showPushAlertSources by rememberSaveable { mutableStateOf(false) }
    var showNotificationScreeningSources by rememberSaveable { mutableStateOf(false) }
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
            stringResource(R.string.settings_remote_url_lookup),
            stringResource(R.string.settings_remote_url_lookup_desc),
            Icons.Default.Security,
            remoteUrlLookup,
        ) { viewModel.setRemoteUrlLookup(it) }
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
            MessageCapabilityStatusRow(
                title = stringResource(R.string.settings_notification_capability_status),
                status = notificationMessageCapabilityStatus,
            )
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
}

/** Meeting mode and its app picker. */
@Composable
internal fun MeetingModeSection(
    viewModel: MainViewModel,
    notificationAccessGranted: Boolean,
) {
    val context = LocalContext.current
    val meetingModeEnabled by viewModel.meetingModeEnabled.collectAsStateWithLifecycle()
    val meetingModeApps by viewModel.meetingModeApps.collectAsStateWithLifecycle()
    var showMeetingApps by rememberSaveable { mutableStateOf(false) }
    MeetingModeSettings(
        enabled = meetingModeEnabled,
        selectedCount = meetingModeApps.size,
        notificationAccessGranted = notificationAccessGranted,
        onEnabledChange = { enabled ->
            viewModel.setMeetingMode(enabled)
            // Nothing happens until an app is picked, so ask right away.
            if (enabled && meetingModeApps.isEmpty()) showMeetingApps = true
        },
        onChooseApps = { showMeetingApps = true },
        onGrantAccess = { context.startActivitySafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
    )

    if (showMeetingApps) {
        MeetingAppsSheet(
            selectedPackages = meetingModeApps,
            onToggle = viewModel::setMeetingModeApp,
            onDismiss = { showMeetingApps = false },
        )
    }
}

@Composable
internal fun PowerModeSettings(viewModel: MainViewModel) {
    val aggressiveMode by viewModel.aggressiveModeEnabled.collectAsStateWithLifecycle()
    SettingsCard(stringResource(R.string.settings_power_mode)) {
        SettingsToggle(stringResource(R.string.settings_aggressive_blocking), stringResource(R.string.settings_aggressive_blocking_desc), Icons.Default.Security, aggressiveMode, tintColor = CatRed) { viewModel.setAggressiveMode(it) }
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

internal const val EMERGENCY_CALLBACK_WINDOW_MINUTES_STEP = 15

@Composable
internal fun MeetingModeSettings(
    enabled: Boolean,
    selectedCount: Int,
    notificationAccessGranted: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onChooseApps: () -> Unit,
    onGrantAccess: () -> Unit,
) {
    SettingsCard(stringResource(R.string.settings_meeting_mode)) {
        SettingsToggle(
            stringResource(R.string.settings_meeting_mode_toggle),
            stringResource(R.string.settings_meeting_mode_desc),
            Icons.Default.VideoCall,
            enabled,
            toggleTag = SETTINGS_MEETING_MODE_TOGGLE_TAG,
            onCheckedChange = onEnabledChange,
        )
        if (enabled) {
            Spacer(Modifier.height(4.dp))
            PremiumActionButton(
                label = stringResource(R.string.settings_meeting_mode_apps),
                icon = Icons.Default.Tune,
                color = CatMauve,
                onClick = onChooseApps,
                modifier = Modifier.fillMaxWidth(),
                outlined = true,
            )
            Text(
                if (selectedCount == 0) {
                    stringResource(R.string.settings_meeting_mode_apps_none)
                } else {
                    pluralStringResource(R.plurals.settings_meeting_mode_apps_count, selectedCount, selectedCount)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (selectedCount == 0) CatPeach else CatSubtext,
                modifier = Modifier.padding(start = 4.dp),
            )
            if (!notificationAccessGranted) {
                Text(
                    stringResource(R.string.settings_meeting_mode_needs_access),
                    style = MaterialTheme.typography.labelSmall,
                    color = CatPeach,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
                PremiumActionButton(
                    label = stringResource(R.string.settings_grant_notification_access),
                    icon = Icons.Default.NotificationsActive,
                    color = CatMauve,
                    onClick = onGrantAccess,
                    modifier = Modifier.fillMaxWidth(),
                    outlined = true,
                )
            }
        }
    }
}

@Composable
internal fun RegulatoryPrefixSettings(
    enabled: Set<RegulatoryPrefix>,
    onToggle: (RegulatoryPrefix, Boolean) -> Unit,
) {
    SettingsCard(stringResource(R.string.settings_regulatory_prefixes)) {
        Text(
            stringResource(R.string.settings_regulatory_prefixes_desc),
            style = MaterialTheme.typography.bodySmall,
            color = CatSubtext,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        RegulatoryPrefix.entries.forEachIndexed { index, prefix ->
            if (index > 0) GradientDivider()
            SettingsToggle(
                stringResource(prefix.titleRes),
                stringResource(prefix.summaryRes),
                if (prefix.allows) Icons.Default.VerifiedUser else Icons.Default.Campaign,
                prefix in enabled,
                toggleTag = "$SETTINGS_REGULATORY_PREFIX_TAG_PREFIX${prefix.name}",
            ) { onToggle(prefix, it) }
        }
    }
}
