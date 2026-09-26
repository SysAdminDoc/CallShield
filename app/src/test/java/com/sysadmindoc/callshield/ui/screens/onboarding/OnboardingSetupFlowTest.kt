package com.sysadmindoc.callshield.ui.screens.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingSetupFlowTest {
    @Test
    fun `a step tried and still not granted points at restricted settings where Android can restrict it`() {
        val state = readyState().copy(notificationAccessGranted = false, overlayGranted = false, runtimePermissionsGranted = false)

        // Notification access is restricted from Android 13, the overlay and SMS from Android 15.
        assertTrue(restrictedSettingsHintApplies(OnboardingSetupStep.NotificationAccess, OnboardingSetupStep.NotificationAccess, state, 33))
        assertFalse(restrictedSettingsHintApplies(OnboardingSetupStep.NotificationAccess, OnboardingSetupStep.NotificationAccess, state, 32))
        assertTrue(restrictedSettingsHintApplies(OnboardingSetupStep.Overlay, OnboardingSetupStep.Overlay, state, 35))
        assertFalse(restrictedSettingsHintApplies(OnboardingSetupStep.Overlay, OnboardingSetupStep.Overlay, state, 34))
        assertTrue(restrictedSettingsHintApplies(OnboardingSetupStep.RuntimePermissions, OnboardingSetupStep.RuntimePermissions, state, 35))
    }

    @Test
    fun `no hint before the user tried, after it was granted, or on steps Android never restricts`() {
        val missing = readyState().copy(notificationAccessGranted = false, screenerGranted = false, notificationsGranted = false)

        assertFalse(restrictedSettingsHintApplies(OnboardingSetupStep.NotificationAccess, null, missing, 36))
        assertFalse(restrictedSettingsHintApplies(OnboardingSetupStep.NotificationAccess, OnboardingSetupStep.Overlay, missing, 36))
        assertFalse(restrictedSettingsHintApplies(OnboardingSetupStep.NotificationAccess, OnboardingSetupStep.NotificationAccess, readyState(), 36))
        assertFalse(restrictedSettingsHintApplies(OnboardingSetupStep.CallScreening, OnboardingSetupStep.CallScreening, missing, 36))
        assertFalse(restrictedSettingsHintApplies(OnboardingSetupStep.Notifications, OnboardingSetupStep.Notifications, missing, 36))
    }

    @Test
    fun `required grants make onboarding ready when optional access is declined`() {
        val state = readyState().copy(notificationsGranted = false, overlayGranted = false, notificationAccessGranted = false)

        assertTrue(state.isReady)
        assertEquals(2, state.completedSetupCount)
        assertFalse(state.isComplete(OnboardingSetupStep.NotificationAccess))
    }

    @Test
    fun `missing runtime access or supported screening role prevents completion`() {
        assertFalse(readyState().copy(runtimePermissionsGranted = false).isReady)
        assertFalse(readyState().copy(screenerGranted = false).isReady)
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
