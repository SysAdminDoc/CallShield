@file:Suppress("TooManyFunctions", "ktlint:standard:function-naming")

package com.sysadmindoc.callshield.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.*

/** What CallShield screens: calls, texts and hidden numbers. */
@Composable
internal fun BlockingSettings(viewModel: MainViewModel) {
    val blockCalls by viewModel.blockCallsEnabled.collectAsStateWithLifecycle()
    val blockSms by viewModel.blockSmsEnabled.collectAsStateWithLifecycle()
    val blockUnknown by viewModel.blockUnknownEnabled.collectAsStateWithLifecycle()
    val smsMessageCapabilityStatus by viewModel.smsMessageCapabilityStatus.collectAsStateWithLifecycle()
    SettingsCard(stringResource(R.string.settings_blocking)) {
        SettingsToggle(stringResource(R.string.settings_block_spam_calls), stringResource(R.string.settings_block_spam_calls_desc), Icons.Default.PhoneDisabled, blockCalls) { viewModel.setBlockCalls(it) }
        GradientDivider()
        SettingsToggle(stringResource(R.string.settings_block_spam_sms), stringResource(R.string.settings_block_spam_sms_desc), Icons.Default.SpeakerNotesOff, blockSms) { viewModel.setBlockSms(it) }
        MessageCapabilityStatusRow(
            title = stringResource(R.string.settings_sms_capability_status),
            status = smsMessageCapabilityStatus,
        )
        GradientDivider()
        SettingsToggle(stringResource(R.string.settings_block_unknown), stringResource(R.string.settings_block_unknown_desc), Icons.Default.QuestionMark, blockUnknown) { viewModel.setBlockUnknown(it) }
    }
}
