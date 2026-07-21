package com.sysadmindoc.callshield.permissions

import android.Manifest
import com.sysadmindoc.callshield.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallShieldPermissionsTest {
    @Test
    fun `contract covers every user-actionable permission and role`() {
        val expectedIds =
            setOf(
                PermissionCapabilityId.CallScreeningRole,
                PermissionCapabilityId.ReadCallLog,
                PermissionCapabilityId.ReadContacts,
                PermissionCapabilityId.ReadSms,
                PermissionCapabilityId.ReceiveSms,
                PermissionCapabilityId.ReadPhoneState,
                PermissionCapabilityId.AnswerPhoneCalls,
                PermissionCapabilityId.Overlay,
                PermissionCapabilityId.NotificationAccess,
                PermissionCapabilityId.PostNotifications,
            )

        assertEquals(expectedIds, CallShieldPermissions.permissionCapabilityContracts.map { it.id }.toSet())
        assertEquals(
            setOf(
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.SYSTEM_ALERT_WINDOW,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
            CallShieldPermissions.permissionCapabilityContracts.mapNotNull { it.manifestPermission }.toSet(),
        )
    }

    @Test
    fun `all denied states keep names degraded modes and recovery actions`() {
        val states =
            CallShieldPermissions.evaluatePermissionContract(
                PermissionReadinessSnapshot(
                    callScreeningRoleAvailable = true,
                    postNotificationsRuntimeRequired = true,
                ),
            )

        assertEquals(CallShieldPermissions.permissionCapabilityContracts.size, states.size)
        assertTrue(states.all { state -> state.contract.nameRes != 0 })
        assertTrue(states.all { state -> state.contract.degradedModeRes != 0 })
        assertTrue(states.filterNot { state -> state.passed }.all { state -> state.recoveryHintRes != null })
        assertEquals(
            setOf(
                PermissionCapabilityId.CallScreeningRole,
                PermissionCapabilityId.ReadCallLog,
                PermissionCapabilityId.ReadContacts,
                PermissionCapabilityId.ReadSms,
                PermissionCapabilityId.ReceiveSms,
            ),
            states
                .filter { state -> state.contract.priority == PermissionCapabilityPriority.Required }
                .map { state -> state.contract.id }
                .toSet(),
        )
    }

    @Test
    fun `unsupported call screening role has explicit unsupported recovery`() {
        val state =
            CallShieldPermissions
                .evaluatePermissionContract(
                    PermissionReadinessSnapshot(callScreeningRoleAvailable = false),
                ).first { state -> state.contract.id == PermissionCapabilityId.CallScreeningRole }

        assertEquals(PermissionCapabilityStatus.Unsupported, state.status)
        assertEquals(R.string.permission_contract_call_screening_unsupported, state.detailRes)
        assertEquals(R.string.permission_contract_fix_call_screening_unsupported, state.recoveryHintRes)
    }

    @Test
    fun `post notification permission is ready when runtime permission is not required`() {
        val state =
            CallShieldPermissions
                .evaluatePermissionContract(
                    PermissionReadinessSnapshot(postNotificationsRuntimeRequired = false),
                ).first { state -> state.contract.id == PermissionCapabilityId.PostNotifications }

        assertTrue(state.passed)
        assertEquals(R.string.permission_contract_post_notifications_not_required, state.detailRes)
    }

    @Test
    fun `contacts mode degraded when a contacts-dependent mode is on but READ_CONTACTS denied`() {
        // Contact-whitelist on, permission denied → degraded.
        assertTrue(
            CallShieldPermissions.isContactsModeDegraded(
                contactWhitelistEnabled = true,
                contactsOnlyEnabled = false,
                readContactsGranted = false,
            ),
        )
        // Contacts-only on, permission denied → degraded.
        assertTrue(
            CallShieldPermissions.isContactsModeDegraded(
                contactWhitelistEnabled = false,
                contactsOnlyEnabled = true,
                readContactsGranted = false,
            ),
        )
    }

    @Test
    fun `contacts mode not degraded when permission granted or no contacts mode enabled`() {
        // Modes on but permission granted → fine.
        assertFalse(
            CallShieldPermissions.isContactsModeDegraded(
                contactWhitelistEnabled = true,
                contactsOnlyEnabled = true,
                readContactsGranted = true,
            ),
        )
        // No contacts-dependent mode enabled, permission denied → not degraded
        // (nothing depends on contacts).
        assertFalse(
            CallShieldPermissions.isContactsModeDegraded(
                contactWhitelistEnabled = false,
                contactsOnlyEnabled = false,
                readContactsGranted = false,
            ),
        )
    }

    @Test
    fun `fully granted snapshot marks every contract ready`() {
        val grantedPermissions =
            CallShieldPermissions.permissionCapabilityContracts
                .mapNotNull { contract -> contract.manifestPermission }
                .toSet()
        val states =
            CallShieldPermissions.evaluatePermissionContract(
                PermissionReadinessSnapshot(
                    grantedPermissions = grantedPermissions,
                    callScreeningRoleHeld = true,
                    callScreeningRoleAvailable = true,
                    overlayGranted = true,
                    notificationAccessGranted = true,
                    postNotificationsRuntimeRequired = true,
                ),
            )

        assertTrue(states.all { state -> state.passed })
        assertTrue(states.all { state -> state.recoveryHintRes == null })
        assertNotNull(states.first { state -> state.contract.id == PermissionCapabilityId.NotificationAccess })
    }
}
