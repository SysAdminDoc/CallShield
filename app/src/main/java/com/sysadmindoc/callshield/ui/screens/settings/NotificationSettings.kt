@file:Suppress("TooManyFunctions", "ktlint:standard:function-naming")

package com.sysadmindoc.callshield.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PhoneCallback
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.*

/** The after-call screen and CallShield's own notifications. */
@Composable
internal fun NotificationSettings(
    viewModel: MainViewModel,
    notificationsGranted: Boolean,
    onEnableNotifications: () -> Unit,
) {
    val postCallScreen by viewModel.postCallScreenEnabled.collectAsStateWithLifecycle()
    SettingsCard(stringResource(R.string.settings_notifications)) {
        SettingsToggle(
            stringResource(R.string.settings_post_call_screen),
            stringResource(R.string.settings_post_call_screen_desc),
            Icons.AutoMirrored.Filled.PhoneCallback,
            postCallScreen,
            onCheckedChange = viewModel::setPostCallScreen,
        )
        GradientDivider()
        PermissionAccessRow(
            title = stringResource(R.string.settings_notifications),
            icon = Icons.Default.Notifications,
            ready = notificationsGranted,
            readyLabel = stringResource(R.string.settings_access_ready),
            actionLabel = stringResource(R.string.settings_access_enable),
            onAction = onEnableNotifications,
        )
    }
}
