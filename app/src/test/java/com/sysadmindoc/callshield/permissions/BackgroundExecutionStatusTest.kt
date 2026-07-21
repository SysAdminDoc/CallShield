package com.sysadmindoc.callshield.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BackgroundExecutionStatus.classify] — the pure OEM
 * background-kill risk classifier.
 */
class BackgroundExecutionStatusTest {
    @Test
    fun `background-restricted wins over battery exemption`() {
        assertEquals(
            BackgroundExecutionRisk.BackgroundRestricted,
            BackgroundExecutionStatus.classify(
                backgroundRestricted = true,
                ignoringBatteryOptimizations = true,
            ),
        )
    }

    @Test
    fun `not battery exempt reports NotBatteryExempt`() {
        assertEquals(
            BackgroundExecutionRisk.NotBatteryExempt,
            BackgroundExecutionStatus.classify(
                backgroundRestricted = false,
                ignoringBatteryOptimizations = false,
            ),
        )
    }

    @Test
    fun `exempt and unrestricted is Ok`() {
        assertEquals(
            BackgroundExecutionRisk.Ok,
            BackgroundExecutionStatus.classify(
                backgroundRestricted = false,
                ignoringBatteryOptimizations = true,
            ),
        )
    }

    @Test
    fun `restriction ignored below supported API`() {
        // Pre-API-28: isBackgroundRestricted is unavailable, so a stale true
        // must not be trusted — fall through to the battery-exempt signal.
        assertEquals(
            BackgroundExecutionRisk.Ok,
            BackgroundExecutionStatus.classify(
                backgroundRestricted = true,
                ignoringBatteryOptimizations = true,
                restrictionApiSupported = false,
            ),
        )
        assertEquals(
            BackgroundExecutionRisk.NotBatteryExempt,
            BackgroundExecutionStatus.classify(
                backgroundRestricted = true,
                ignoringBatteryOptimizations = false,
                restrictionApiSupported = false,
            ),
        )
    }

    @Test
    fun `at-risk maps every non-Ok risk`() {
        assertTrue(BackgroundExecutionRisk.BackgroundRestricted != BackgroundExecutionRisk.Ok)
        assertTrue(BackgroundExecutionRisk.NotBatteryExempt != BackgroundExecutionRisk.Ok)
        assertFalse(BackgroundExecutionRisk.Ok != BackgroundExecutionRisk.Ok)
    }
}
