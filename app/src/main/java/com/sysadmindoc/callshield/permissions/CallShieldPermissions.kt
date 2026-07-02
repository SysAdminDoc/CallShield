package com.sysadmindoc.callshield.permissions

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.sysadmindoc.callshield.R

enum class PermissionCapabilityId {
    CallScreeningRole,
    ReadCallLog,
    ReadContacts,
    ReadSms,
    ReceiveSms,
    ReadPhoneState,
    AnswerPhoneCalls,
    Overlay,
    NotificationAccess,
    PostNotifications,
}

enum class PermissionCapabilityKind {
    RuntimePermission,
    AndroidRole,
    SpecialAccess,
}

enum class PermissionCapabilityPriority {
    Required,
    Recommended,
}

enum class PermissionCapabilityStatus {
    Ready,
    Degraded,
    Unsupported,
}

data class PermissionCapabilityContract(
    val id: PermissionCapabilityId,
    val kind: PermissionCapabilityKind,
    val nameRes: Int,
    val grantedDetailRes: Int,
    val degradedDetailRes: Int,
    val degradedModeRes: Int,
    val recoveryHintRes: Int,
    val priority: PermissionCapabilityPriority,
    val manifestPermission: String? = null,
    val readyWhenRuntimePermissionNotRequired: Boolean = false,
    val unsupportedDetailRes: Int = degradedDetailRes,
)

data class PermissionReadinessSnapshot(
    val grantedPermissions: Set<String> = emptySet(),
    val callScreeningRoleHeld: Boolean = false,
    val callScreeningRoleAvailable: Boolean = true,
    val overlayGranted: Boolean = false,
    val notificationAccessGranted: Boolean = false,
    val postNotificationsRuntimeRequired: Boolean = true,
)

data class PermissionCapabilityState(
    val contract: PermissionCapabilityContract,
    val status: PermissionCapabilityStatus,
    val detailRes: Int,
    val recoveryHintRes: Int? = null,
) {
    val passed: Boolean = status == PermissionCapabilityStatus.Ready
}

object CallShieldPermissions {
    val callProtectionPermissions =
        listOf(
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
        )

    val smsProtectionPermissions =
        listOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
        )

    val corePermissions = (callProtectionPermissions + smsProtectionPermissions).distinct()

    val compatibilityPermissions =
        listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
        )

    val permissionCapabilityContracts =
        listOf(
            PermissionCapabilityContract(
                id = PermissionCapabilityId.ReadCallLog,
                kind = PermissionCapabilityKind.RuntimePermission,
                nameRes = R.string.protection_test_perm_name_call_log,
                grantedDetailRes = R.string.protection_test_perm_granted,
                degradedDetailRes = R.string.protection_test_perm_not_granted,
                degradedModeRes = R.string.permission_contract_degraded_call_log,
                recoveryHintRes = R.string.protection_test_fix_permissions,
                priority = PermissionCapabilityPriority.Required,
                manifestPermission = Manifest.permission.READ_CALL_LOG,
            ),
            PermissionCapabilityContract(
                id = PermissionCapabilityId.ReadContacts,
                kind = PermissionCapabilityKind.RuntimePermission,
                nameRes = R.string.protection_test_perm_name_contacts,
                grantedDetailRes = R.string.protection_test_perm_granted,
                degradedDetailRes = R.string.protection_test_perm_not_granted,
                degradedModeRes = R.string.permission_contract_degraded_contacts,
                recoveryHintRes = R.string.protection_test_fix_permissions,
                priority = PermissionCapabilityPriority.Required,
                manifestPermission = Manifest.permission.READ_CONTACTS,
            ),
            PermissionCapabilityContract(
                id = PermissionCapabilityId.ReadSms,
                kind = PermissionCapabilityKind.RuntimePermission,
                nameRes = R.string.protection_test_perm_name_sms,
                grantedDetailRes = R.string.protection_test_perm_granted,
                degradedDetailRes = R.string.protection_test_perm_not_granted,
                degradedModeRes = R.string.permission_contract_degraded_read_sms,
                recoveryHintRes = R.string.protection_test_fix_permissions,
                priority = PermissionCapabilityPriority.Required,
                manifestPermission = Manifest.permission.READ_SMS,
            ),
            PermissionCapabilityContract(
                id = PermissionCapabilityId.ReceiveSms,
                kind = PermissionCapabilityKind.RuntimePermission,
                nameRes = R.string.protection_test_perm_name_incoming_sms,
                grantedDetailRes = R.string.protection_test_perm_granted,
                degradedDetailRes = R.string.protection_test_perm_not_granted,
                degradedModeRes = R.string.permission_contract_degraded_receive_sms,
                recoveryHintRes = R.string.protection_test_fix_permissions,
                priority = PermissionCapabilityPriority.Required,
                manifestPermission = Manifest.permission.RECEIVE_SMS,
            ),
            PermissionCapabilityContract(
                id = PermissionCapabilityId.CallScreeningRole,
                kind = PermissionCapabilityKind.AndroidRole,
                nameRes = R.string.protection_test_call_screener_role,
                grantedDetailRes = R.string.protection_test_screener_yes,
                degradedDetailRes = R.string.protection_test_screener_no,
                degradedModeRes = R.string.permission_contract_degraded_call_screening,
                recoveryHintRes = R.string.protection_test_fix_screener,
                priority = PermissionCapabilityPriority.Required,
                unsupportedDetailRes = R.string.permission_contract_call_screening_unsupported,
            ),
            PermissionCapabilityContract(
                id = PermissionCapabilityId.ReadPhoneState,
                kind = PermissionCapabilityKind.RuntimePermission,
                nameRes = R.string.protection_test_perm_name_phone_state,
                grantedDetailRes = R.string.protection_test_perm_granted,
                degradedDetailRes = R.string.protection_test_perm_not_granted,
                degradedModeRes = R.string.permission_contract_degraded_phone_state,
                recoveryHintRes = R.string.protection_test_fix_permissions,
                priority = PermissionCapabilityPriority.Recommended,
                manifestPermission = Manifest.permission.READ_PHONE_STATE,
            ),
            PermissionCapabilityContract(
                id = PermissionCapabilityId.AnswerPhoneCalls,
                kind = PermissionCapabilityKind.RuntimePermission,
                nameRes = R.string.protection_test_perm_name_answer_calls,
                grantedDetailRes = R.string.protection_test_perm_granted,
                degradedDetailRes = R.string.protection_test_perm_not_granted,
                degradedModeRes = R.string.permission_contract_degraded_answer_calls,
                recoveryHintRes = R.string.protection_test_fix_permissions,
                priority = PermissionCapabilityPriority.Recommended,
                manifestPermission = Manifest.permission.ANSWER_PHONE_CALLS,
            ),
            PermissionCapabilityContract(
                id = PermissionCapabilityId.Overlay,
                kind = PermissionCapabilityKind.SpecialAccess,
                nameRes = R.string.protection_test_overlay_permission,
                grantedDetailRes = R.string.protection_test_overlay_pass,
                degradedDetailRes = R.string.protection_test_overlay_fail,
                degradedModeRes = R.string.permission_contract_degraded_overlay,
                recoveryHintRes = R.string.protection_test_fix_overlay,
                priority = PermissionCapabilityPriority.Recommended,
                manifestPermission = Manifest.permission.SYSTEM_ALERT_WINDOW,
            ),
            PermissionCapabilityContract(
                id = PermissionCapabilityId.NotificationAccess,
                kind = PermissionCapabilityKind.SpecialAccess,
                nameRes = R.string.protection_test_notification_access,
                grantedDetailRes = R.string.protection_test_notif_pass,
                degradedDetailRes = R.string.protection_test_notif_fail,
                degradedModeRes = R.string.permission_contract_degraded_notification_access,
                recoveryHintRes = R.string.protection_test_fix_notifications,
                priority = PermissionCapabilityPriority.Recommended,
            ),
            PermissionCapabilityContract(
                id = PermissionCapabilityId.PostNotifications,
                kind = PermissionCapabilityKind.RuntimePermission,
                nameRes = R.string.permission_contract_post_notifications_name,
                grantedDetailRes = R.string.protection_test_perm_granted,
                degradedDetailRes = R.string.permission_contract_post_notifications_denied,
                degradedModeRes = R.string.permission_contract_degraded_post_notifications,
                recoveryHintRes = R.string.protection_test_fix_notifications,
                priority = PermissionCapabilityPriority.Recommended,
                manifestPermission = Manifest.permission.POST_NOTIFICATIONS,
                readyWhenRuntimePermissionNotRequired = true,
                unsupportedDetailRes = R.string.permission_contract_post_notifications_not_required,
            ),
        )

    val protectionTestPermissions =
        permissionCapabilityContracts
            .mapNotNull { contract ->
                contract.manifestPermission?.let { permission -> contract.id.name to permission }
            }

    fun hasCorePermissions(context: Context): Boolean = missingPermissions(context, corePermissions).isEmpty()

    fun hasCallProtectionPermissions(
        context: Context,
    ): Boolean = missingPermissions(context, callProtectionPermissions).isEmpty()

    fun hasSmsProtectionPermissions(
        context: Context,
    ): Boolean = missingPermissions(context, smsProtectionPermissions).isEmpty()

    fun missingCorePermissions(context: Context): List<String> = missingPermissions(context, corePermissions)

    fun missingEnabledProtectionPermissions(
        context: Context,
        callsEnabled: Boolean,
        smsEnabled: Boolean,
    ): List<String> {
        val permissions =
            buildList {
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

    fun hasCallScreeningRole(
        roleManager: RoleManager?,
    ): Boolean = roleManager?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true

    fun isCallScreeningRoleAvailable(
        roleManager: RoleManager?,
    ): Boolean = roleManager?.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) == true

    fun hasNotificationListenerAccess(context: Context): Boolean =
        Settings.Secure
            .getString(
                context.contentResolver,
                "enabled_notification_listeners",
            )?.contains(context.packageName) == true

    fun permissionContractStates(
        context: Context,
        roleManager: RoleManager? = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager,
    ): List<PermissionCapabilityState> =
        evaluatePermissionContract(
            PermissionReadinessSnapshot(
                grantedPermissions =
                    permissionCapabilityContracts
                        .mapNotNull { contract -> contract.manifestPermission }
                        .filter { permission -> isPermissionGranted(context, permission) }
                        .toSet(),
                callScreeningRoleHeld = hasCallScreeningRole(roleManager),
                callScreeningRoleAvailable = isCallScreeningRoleAvailable(roleManager),
                overlayGranted = canDrawOverlays(context),
                notificationAccessGranted = hasNotificationListenerAccess(context),
                postNotificationsRuntimeRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            ),
        )

    fun evaluatePermissionContract(snapshot: PermissionReadinessSnapshot): List<PermissionCapabilityState> =
        permissionCapabilityContracts.map { contract ->
            val ready = isCapabilityReady(contract, snapshot)

            when {
                ready -> {
                    PermissionCapabilityState(
                        contract = contract,
                        status = PermissionCapabilityStatus.Ready,
                        detailRes = readyDetailRes(contract, snapshot),
                    )
                }

                contract.id == PermissionCapabilityId.CallScreeningRole &&
                    !snapshot.callScreeningRoleAvailable -> {
                    PermissionCapabilityState(
                        contract = contract,
                        status = PermissionCapabilityStatus.Unsupported,
                        detailRes = contract.unsupportedDetailRes,
                        recoveryHintRes = R.string.permission_contract_fix_call_screening_unsupported,
                    )
                }

                else -> {
                    PermissionCapabilityState(
                        contract = contract,
                        status = PermissionCapabilityStatus.Degraded,
                        detailRes = contract.degradedDetailRes,
                        recoveryHintRes = contract.recoveryHintRes,
                    )
                }
            }
        }

    private fun isCapabilityReady(
        contract: PermissionCapabilityContract,
        snapshot: PermissionReadinessSnapshot,
    ): Boolean =
        when (contract.kind) {
            PermissionCapabilityKind.RuntimePermission -> {
                val notificationPermissionNotRequired =
                    contract.id == PermissionCapabilityId.PostNotifications &&
                        !snapshot.postNotificationsRuntimeRequired &&
                        contract.readyWhenRuntimePermissionNotRequired

                contract.manifestPermission in snapshot.grantedPermissions ||
                    notificationPermissionNotRequired
            }

            PermissionCapabilityKind.AndroidRole -> {
                snapshot.callScreeningRoleAvailable && snapshot.callScreeningRoleHeld
            }

            PermissionCapabilityKind.SpecialAccess -> {
                when (contract.id) {
                    PermissionCapabilityId.Overlay -> snapshot.overlayGranted
                    PermissionCapabilityId.NotificationAccess -> snapshot.notificationAccessGranted
                    else -> false
                }
            }
        }

    private fun readyDetailRes(
        contract: PermissionCapabilityContract,
        snapshot: PermissionReadinessSnapshot,
    ): Int =
        if (
            contract.id == PermissionCapabilityId.PostNotifications &&
            !snapshot.postNotificationsRuntimeRequired
        ) {
            contract.unsupportedDetailRes
        } else {
            contract.grantedDetailRes
        }

    fun isPermissionGranted(
        context: Context,
        permission: String,
    ): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun missingPermissions(
        context: Context,
        permissions: List<String>,
    ): List<String> = permissions.filterNot { permission -> isPermissionGranted(context, permission) }
}
