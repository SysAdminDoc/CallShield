@file:Suppress("TooManyFunctions", "ktlint:standard:function-naming")

package com.sysadmindoc.callshield.ui.screens.settings

import android.Manifest
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
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
import com.sysadmindoc.callshield.permissions.CallShieldPermissions
import com.sysadmindoc.callshield.service.AnswerHangUpController
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.rememberPermissionRequest
import com.sysadmindoc.callshield.ui.theme.*

/**
 * What happens to a call CallShield blocks. These used to sit among the
 * detection engines, where they read as more ways to catch spam.
 */
@Composable
internal fun BlockedCallSettings(viewModel: MainViewModel) {
    val context = LocalContext.current
    val silentVoicemail by viewModel.silentVoicemailEnabled.collectAsStateWithLifecycle()
    val autoMuteLowConfidence by viewModel.autoMuteLowConfidenceEnabled.collectAsStateWithLifecycle()
    val answerHangUpEnabled by viewModel.answerHangUpEnabled.collectAsStateWithLifecycle()
    val hangUpDelaySeconds by viewModel.hangUpDelaySeconds.collectAsStateWithLifecycle()
    val categoryCallActions by viewModel.categoryCallActions.collectAsStateWithLifecycle()
    var showCategoryCallActions by rememberSaveable { mutableStateOf(false) }
    val answerHangUpPermissions =
        remember {
            listOf(
                Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG,
            )
        }
    val answerHangUpPermissionRequest =
        rememberPermissionRequest(R.string.permission_phone_in_app_info) { grants ->
            val allGranted =
                answerHangUpPermissions.all { permission ->
                    grants[permission] == true || CallShieldPermissions.isPermissionGranted(context, permission)
                }
            viewModel.setAnswerHangUpEnabled(allGranted)
        }

    SettingsCard(stringResource(R.string.settings_when_blocked)) {
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
        GradientDivider()
        SettingsToggle(
            stringResource(R.string.settings_answer_hang_up),
            stringResource(R.string.settings_answer_hang_up_desc),
            Icons.Default.CallEnd,
            answerHangUpEnabled,
        ) { enabled ->
            if (!enabled) {
                viewModel.setAnswerHangUpEnabled(false)
                return@SettingsToggle
            }
            val missingPermissions =
                answerHangUpPermissions.filterNot { permission ->
                    CallShieldPermissions.isPermissionGranted(context, permission)
                }
            if (missingPermissions.isEmpty()) {
                viewModel.setAnswerHangUpEnabled(true)
            } else {
                answerHangUpPermissionRequest(missingPermissions)
            }
        }
        if (answerHangUpEnabled) {
            Spacer(Modifier.height(8.dp))
            SettingsNumberStepper(
                label = stringResource(R.string.settings_hang_up_delay),
                valueText =
                    pluralStringResource(
                        R.plurals.settings_hang_up_delay_value,
                        hangUpDelaySeconds,
                        hangUpDelaySeconds,
                    ),
                value = hangUpDelaySeconds,
                minValue = AnswerHangUpController.MIN_DELAY_SECONDS,
                maxValue = AnswerHangUpController.MAX_DELAY_SECONDS,
                onValueChange = viewModel::setHangUpDelaySeconds,
            )
        }
        GradientDivider()
        SettingsLinkRow(
            title = stringResource(R.string.settings_category_actions),
            value = stringResource(R.string.settings_category_actions_summary, categoryCallActions.size),
            icon = Icons.AutoMirrored.Filled.CallSplit,
            stackValue = true,
            onClick = { showCategoryCallActions = true },
        )
    }

    if (showCategoryCallActions) {
        CategoryCallActionsSheet(
            actions = categoryCallActions,
            onActionChange = viewModel::setCategoryCallAction,
            onDismiss = { showCategoryCallActions = false },
        )
    }
}
