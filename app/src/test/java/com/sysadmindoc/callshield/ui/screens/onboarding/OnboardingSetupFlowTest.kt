package com.sysadmindoc.callshield.ui.screens.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingSetupFlowTest {
    @Test
    fun `all supported setup checks must pass before onboarding is ready`() {
        val state = readyState().copy(notificationAccessGranted = false)

        assertFalse(state.isReady)
        assertEquals(4, state.completedSetupCount)
        assertFalse(state.isComplete(OnboardingSetupStep.NotificationAccess))
    }

    @Test
    fun `unsupported call screening is explicitly skipped`() {
        val state =
            readyState().copy(
                screenerGranted = false,
                screenerSupported = false,
            )

        assertTrue(state.isComplete(OnboardingSetupStep.CallScreening))
        assertTrue(state.isReady)
    }

    @Test
    fun `intro and review are navigation steps rather than permission checks`() {
        val state =
            OnboardingSetupState(
                runtimePermissionsGranted = false,
                notificationsGranted = false,
                overlayGranted = false,
                notificationAccessGranted = false,
                screenerGranted = false,
                screenerSupported = true,
            )

        assertTrue(state.isComplete(OnboardingSetupStep.Intro))
        assertTrue(state.isComplete(OnboardingSetupStep.Review))
        assertEquals(0, state.completedSetupCount)
        assertFalse(state.isReady)
    }

    private fun readyState() =
        OnboardingSetupState(
            runtimePermissionsGranted = true,
            notificationsGranted = true,
            overlayGranted = true,
            notificationAccessGranted = true,
            screenerGranted = true,
            screenerSupported = true,
        )
}
