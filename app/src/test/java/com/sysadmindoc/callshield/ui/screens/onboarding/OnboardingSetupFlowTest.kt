package com.sysadmindoc.callshield.ui.screens.onboarding

import com.sysadmindoc.callshield.permissions.InstallSourceKind
import com.sysadmindoc.callshield.ui.screens.main.requiredSetupProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `SMS and call log from another installer point at a reinstall on every release`() {
        val state = readyState().copy(runtimePermissionsGranted = false, overlayGranted = false)
        val step = OnboardingSetupStep.RuntimePermissions

        (29..37).forEach { sdk ->
            assertEquals("sdk $sdk", SetupHint.ReinstallOverThisCopy, setupHint(step, step, state, sdk, InstallSourceKind.OtherInstaller))
        }
        // The phone's own installer allowlists them, so only restricted settings is left to point at.
        assertEquals(SetupHint.RestrictedSettings, setupHint(step, step, state, 35, InstallSourceKind.SystemInstaller))
        assertNull(setupHint(step, step, state, 34, InstallSourceKind.SystemInstaller))
        assertEquals(SetupHint.RestrictedSettings, setupHint(step, step, state, 36, InstallSourceKind.Shell))
        assertNull(setupHint(step, step, state, 34, InstallSourceKind.PlayStore))
        // The overlay isn't held back by the installer.
        assertEquals(
            SetupHint.RestrictedSettings,
            setupHint(OnboardingSetupStep.Overlay, OnboardingSetupStep.Overlay, state, 35, InstallSourceKind.OtherInstaller),
        )
    }

    @Test
    fun `no reinstall hint before the user tried or once the grant stuck`() {
        val step = OnboardingSetupStep.RuntimePermissions

        assertNull(setupHint(step, null, readyState().copy(runtimePermissionsGranted = false), 36, InstallSourceKind.OtherInstaller))
        assertNull(setupHint(step, step, readyState(), 36, InstallSourceKind.OtherInstaller))
    }

    @Test
    fun `required grants make onboarding ready when optional access is declined`() {
        val state = readyState().copy(notificationsGranted = false, overlayGranted = false, notificationAccessGranted = false)

        assertTrue(state.isReady)
        assertEquals(2, state.completedSetupCount)
        assertEquals(2, state.requiredSetupCount)
        assertEquals(2, state.requiredSetupTotal)
        assertEquals(requiredSetupProgress(permissionsReady = true, screenerReadyForCurrentMode = true).total, state.requiredSetupTotal)
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
    fun `intro review and profile are navigation steps rather than permission checks`() {
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
        assertTrue(state.isComplete(OnboardingSetupStep.Profile))
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
