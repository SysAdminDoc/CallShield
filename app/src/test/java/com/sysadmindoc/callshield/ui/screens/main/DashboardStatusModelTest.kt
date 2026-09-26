package com.sysadmindoc.callshield.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardStatusModelTest {
    @Test
    fun `paused protection keeps completed setup complete`() {
        val status =
            buildDashboardStatusModel(
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
        val status =
            buildDashboardStatusModel(
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
        val status =
            buildDashboardStatusModel(
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

    @Test
    fun `Home counts the same two steps as Settings, and a missing database still needs setup`() {
        val status =
            buildDashboardStatusModel(
                blockCallsEnabled = true,
                blockSmsEnabled = true,
                callPermissionsReady = true,
                smsPermissionsReady = true,
                permissionsReady = true,
                spamDatabaseReady = false,
                callScreenerReady = true,
                overlayGranted = true,
                notificationsGranted = true,
            )

        assertEquals(requiredSetupProgress(permissionsReady = true, screenerReadyForCurrentMode = true), SetupProgress(2, 2))
        assertEquals(2, status.requiredSetupTotal)
        assertEquals(2, status.requiredSetupComplete)
        assertFalse(status.setupComplete)
        assertEquals(DashboardHeroMode.SetupNeeded, status.heroMode)
    }

    private val allOn =
        EngineToggles(
            stirShaken = true,
            heuristics = true,
            smsContent = true,
            neighborSpoof = true,
            mlScorer = true,
            rcsFilter = true,
            freqEscalation = true,
        )

    private fun paths(
        screener: Boolean = true,
        sms: Boolean = true,
        access: Boolean = true,
        blockCalls: Boolean = true,
        blockSms: Boolean = true,
    ) = protectionPaths(
        blockCallsEnabled = blockCalls,
        callScreenerReady = screener,
        blockSmsEnabled = blockSms,
        smsPermissionsReady = sms,
        rcsFilter = true,
        notificationAccessGranted = access,
    )

    @Test
    fun `every engine counts only when a call or message can reach it`() {
        assertEquals(8, runnableEngineCount(allOn, paths(), spamDatabaseReady = true))
        // Default-on toggles with nothing granted used to read as eight engines.
        assertEquals(0, runnableEngineCount(allOn, paths(screener = false, sms = false, access = false), spamDatabaseReady = true))
        // Without the screener, STIR/SHAKEN, neighbor spoofing and repeat-caller escalation can't run.
        assertEquals(5, runnableEngineCount(allOn, paths(screener = false), spamDatabaseReady = true))
        // Without notification access, the RCS filter can't run.
        assertEquals(7, runnableEngineCount(allOn, paths(access = false), spamDatabaseReady = true))
        // Texts only: database, heuristics, content rules, the model and RCS.
        assertEquals(5, runnableEngineCount(allOn, paths(blockCalls = false), spamDatabaseReady = true))
        assertEquals(7, runnableEngineCount(allOn, paths(), spamDatabaseReady = false))
        // Neighbor spoofing is part of the heuristic checker.
        assertEquals(6, runnableEngineCount(allOn.copy(heuristics = false), paths(), spamDatabaseReady = true))
    }

    @Test
    fun `Home asks for notification access only when a feature that reads notifications is on`() {
        assertTrue(notificationAccessNeeded(rcsFilter = true, blockSmsEnabled = true, pushAlert = false, meetingMode = false, notificationAccessGranted = false))
        assertTrue(notificationAccessNeeded(rcsFilter = false, blockSmsEnabled = false, pushAlert = true, meetingMode = false, notificationAccessGranted = false))
        assertTrue(notificationAccessNeeded(rcsFilter = false, blockSmsEnabled = false, pushAlert = false, meetingMode = true, notificationAccessGranted = false))
        assertFalse(notificationAccessNeeded(rcsFilter = true, blockSmsEnabled = false, pushAlert = false, meetingMode = false, notificationAccessGranted = false))
        assertFalse(notificationAccessNeeded(rcsFilter = true, blockSmsEnabled = true, pushAlert = true, meetingMode = true, notificationAccessGranted = true))
    }
}
