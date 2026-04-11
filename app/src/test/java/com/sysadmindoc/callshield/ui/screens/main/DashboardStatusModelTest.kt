package com.sysadmindoc.callshield.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardStatusModelTest {

    @Test
    fun `paused protection keeps completed setup complete`() {
        val status = buildDashboardStatusModel(
            blockCallsEnabled = false,
            blockSmsEnabled = false,
            callPermissionsReady = true,
            smsPermissionsReady = true,
            permissionsReady = true,
            spamDatabaseReady = true,
            callScreenerReady = false,
            overlayGranted = true,
            notificationsGranted = true,
        )

        assertEquals(DashboardHeroMode.Disabled, status.heroMode)
        assertTrue(status.setupComplete)
        assertFalse(status.shieldActive)
    }

    @Test
    fun `missing setup while protection enabled shows setup needed`() {
        val status = buildDashboardStatusModel(
            blockCallsEnabled = true,
            blockSmsEnabled = true,
            callPermissionsReady = true,
            smsPermissionsReady = false,
            permissionsReady = false,
            spamDatabaseReady = false,
            callScreenerReady = false,
            overlayGranted = false,
            notificationsGranted = false,
        )

        assertEquals(DashboardHeroMode.SetupNeeded, status.heroMode)
        assertEquals(0, status.optionalSetupComplete)
        assertEquals(0, status.requiredSetupComplete)
        assertFalse(status.setupComplete)
        assertFalse(status.shieldActive)
    }

    @Test
    fun `fully configured active protection shows active state`() {
        val status = buildDashboardStatusModel(
            blockCallsEnabled = true,
            blockSmsEnabled = true,
            callPermissionsReady = true,
            smsPermissionsReady = true,
            permissionsReady = true,
            spamDatabaseReady = true,
            callScreenerReady = true,
            overlayGranted = true,
            notificationsGranted = true,
        )

        assertEquals(DashboardHeroMode.Active, status.heroMode)
        assertTrue(status.setupComplete)
        assertTrue(status.shieldActive)
    }
}
