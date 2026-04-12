package com.sysadmindoc.callshield.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function tests for the dashboard status model. These live in
 * androidTest alongside the UI tests so they run against the same
 * emulator pipeline, but they have no Compose dependency and also
 * get picked up by any future JVM-side test runner.
 *
 * The DashboardStatusModel is the single source of truth behind the
 * hero card, setup checklist, and quick-action wiring. Breaking it
 * silently cascades into every user-visible setup affordance.
 */
class DashboardStatusBadgeTest {

    @Test
    fun shieldActive_only_when_all_enabled_protection_is_ready() {
        val ready = buildDashboardStatusModel(
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
        assertTrue("all ready -> shield active", ready.shieldActive)
        assertEquals(DashboardHeroMode.Active, ready.heroMode)
        assertTrue(ready.setupComplete)
    }

    @Test
    fun disabling_calls_still_counts_screener_as_ready() {
        // If the user turned off call blocking we should NOT demand the
        // call screener role — it's specifically a call-time permission.
        val m = buildDashboardStatusModel(
            blockCallsEnabled = false,
            blockSmsEnabled = true,
            callPermissionsReady = false,
            smsPermissionsReady = true,
            permissionsReady = true,
            spamDatabaseReady = true,
            callScreenerReady = false,
            overlayGranted = false,
            notificationsGranted = false,
        )
        assertTrue("SMS-only protection should still be 'active'", m.shieldActive)
        assertEquals(DashboardHeroMode.Active, m.heroMode)
    }

    @Test
    fun missing_database_puts_hero_in_setup_mode() {
        val m = buildDashboardStatusModel(
            blockCallsEnabled = true,
            blockSmsEnabled = false,
            callPermissionsReady = true,
            smsPermissionsReady = true,
            permissionsReady = true,
            spamDatabaseReady = false,
            callScreenerReady = true,
            overlayGranted = true,
            notificationsGranted = true,
        )
        assertFalse(m.shieldActive)
        assertEquals(DashboardHeroMode.SetupNeeded, m.heroMode)
    }

    @Test
    fun both_disabled_moves_hero_to_disabled_mode() {
        val m = buildDashboardStatusModel(
            blockCallsEnabled = false,
            blockSmsEnabled = false,
            callPermissionsReady = true,
            smsPermissionsReady = true,
            permissionsReady = true,
            spamDatabaseReady = true,
            callScreenerReady = true,
            overlayGranted = true,
            notificationsGranted = true,
        )
        assertFalse(m.protectionEnabled)
        assertEquals(DashboardHeroMode.Disabled, m.heroMode)
    }

    @Test
    fun optional_setup_counts_do_not_affect_shield_active_flag() {
        val m = buildDashboardStatusModel(
            blockCallsEnabled = true,
            blockSmsEnabled = true,
            callPermissionsReady = true,
            smsPermissionsReady = true,
            permissionsReady = true,
            spamDatabaseReady = true,
            callScreenerReady = true,
            overlayGranted = false,
            notificationsGranted = false,
        )
        assertTrue("overlay + notifications are optional — shield still active", m.shieldActive)
        assertEquals(0, m.optionalSetupComplete)
        assertEquals(2, m.optionalSetupTotal)
    }
}
