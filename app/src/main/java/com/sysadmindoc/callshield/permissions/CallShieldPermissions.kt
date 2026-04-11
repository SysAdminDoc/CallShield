package com.sysadmindoc.callshield.permissions

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

object CallShieldPermissions {
    val callProtectionPermissions = listOf(
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
    )

    val smsProtectionPermissions = listOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS,
    )

    val corePermissions = (callProtectionPermissions + smsProtectionPermissions).distinct()

    val compatibilityPermissions = listOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ANSWER_PHONE_CALLS,
    )

    val protectionTestPermissions = listOf(
        "Call Log" to Manifest.permission.READ_CALL_LOG,
        "Contacts" to Manifest.permission.READ_CONTACTS,
        "SMS Inbox" to Manifest.permission.READ_SMS,
        "Incoming SMS" to Manifest.permission.RECEIVE_SMS,
        "Phone State" to Manifest.permission.READ_PHONE_STATE,
        "Answer Calls" to Manifest.permission.ANSWER_PHONE_CALLS,
    )

    fun hasCorePermissions(context: Context): Boolean = missingPermissions(context, corePermissions).isEmpty()

    fun hasCallProtectionPermissions(context: Context): Boolean =
        missingPermissions(context, callProtectionPermissions).isEmpty()

    fun hasSmsProtectionPermissions(context: Context): Boolean =
        missingPermissions(context, smsProtectionPermissions).isEmpty()

    fun missingCorePermissions(context: Context): List<String> =
        missingPermissions(context, corePermissions)

    fun missingEnabledProtectionPermissions(
        context: Context,
        callsEnabled: Boolean,
        smsEnabled: Boolean
    ): List<String> {
        val permissions = buildList {
            if (callsEnabled) addAll(callProtectionPermissions)
            if (smsEnabled) addAll(smsProtectionPermissions)
        }.distinct()

        return if (permissions.isEmpty()) {
            missingCorePermissions(context)
        } else {
            missingPermissions(context, permissions)
        }
    }

    fun canReadSmsInbox(context: Context): Boolean = isPermissionGranted(context, Manifest.permission.READ_SMS)

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            isPermissionGranted(context, Manifest.permission.POST_NOTIFICATIONS)

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun hasCallScreeningRole(roleManager: RoleManager?): Boolean =
        roleManager?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true

    fun isPermissionGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun missingPermissions(context: Context, permissions: List<String>): List<String> =
        permissions.filterNot { permission -> isPermissionGranted(context, permission) }
}
