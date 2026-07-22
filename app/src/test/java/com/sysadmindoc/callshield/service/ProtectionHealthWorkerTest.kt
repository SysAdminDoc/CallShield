package com.sysadmindoc.callshield.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtectionHealthWorkerTest {
    @Test
    fun `configured call protection notifies when the screening role is lost`() {
        assertEquals(
            ProtectionHealthAction.NOTIFY_ROLE_LOST,
            evaluateProtectionHealth(configuredSnapshot(roleHeld = false)),
        )
    }

    @Test
    fun `a shown loss notice is not repeated`() {
        assertEquals(
            ProtectionHealthAction.NONE,
            evaluateProtectionHealth(
                configuredSnapshot(
                    roleHeld = false,
                    noticeShown = true,
                ),
            ),
        )
    }

    @Test
    fun `restoring the role clears the notice gate`() {
        assertEquals(
            ProtectionHealthAction.CLEAR_ROLE_LOSS_NOTICE,
            evaluateProtectionHealth(
                configuredSnapshot(
                    roleHeld = true,
                    noticeShown = true,
                ),
            ),
        )
    }

    @Test
    fun `intentional protection off clears a stale notice without nagging`() {
        assertEquals(
            ProtectionHealthAction.CLEAR_ROLE_LOSS_NOTICE,
            evaluateProtectionHealth(
                configuredSnapshot(
                    roleHeld = false,
                    noticeShown = true,
                ).copy(callBlockingEnabled = false),
            ),
        )
        assertEquals(
            ProtectionHealthAction.NONE,
            evaluateProtectionHealth(
                configuredSnapshot(roleHeld = false).copy(callBlockingEnabled = false),
            ),
        )
    }

    @Test
    fun `an install that never held the role is not told the role was lost`() {
        // "Continue anyway" onboarding path: the loss alert's copy claims
        // Android *stopped* routing calls through CallShield, which would be
        // false for a never-granted install.
        assertEquals(
            ProtectionHealthAction.NONE,
            evaluateProtectionHealth(
                configuredSnapshot(roleHeld = false).copy(callScreeningRoleEverHeld = false),
            ),
        )
    }

    @Test
    fun `never-onboarded and unsupported devices do not nag`() {
        assertEquals(
            ProtectionHealthAction.NONE,
            evaluateProtectionHealth(configuredSnapshot(roleHeld = false).copy(onboardingDone = false)),
        )
        assertEquals(
            ProtectionHealthAction.NONE,
            evaluateProtectionHealth(
                configuredSnapshot(roleHeld = false).copy(callScreeningRoleAvailable = false),
            ),
        )
    }

    private fun configuredSnapshot(
        roleHeld: Boolean,
        noticeShown: Boolean = false,
    ) = ProtectionHealthSnapshot(
        onboardingDone = true,
        callBlockingEnabled = true,
        callScreeningRoleAvailable = true,
        callScreeningRoleHeld = roleHeld,
        lossNoticeShown = noticeShown,
    )
}
