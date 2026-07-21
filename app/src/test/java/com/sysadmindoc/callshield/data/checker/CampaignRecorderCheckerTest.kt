package com.sysadmindoc.callshield.data.checker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the campaign-recorder gating fix: SMS checks reuse the
 * voice-call checker chain, so the in-memory call-burst detector must ignore
 * SMS-path invocations (non-null `smsBody`). Otherwise a burst of legitimate
 * SMS from a single carrier prefix could trip campaign-burst blocking of real
 * voice calls from that same NPA-NXX.
 */
class CampaignRecorderCheckerTest {
    @Test
    fun `records live voice calls`() {
        assertTrue(CampaignRecorderChecker.shouldRecord(realtimeCall = true, isSms = false))
    }

    @Test
    fun `ignores SMS on the live path`() {
        assertFalse(CampaignRecorderChecker.shouldRecord(realtimeCall = true, isSms = true))
    }

    @Test
    fun `ignores historical call re-scans`() {
        assertFalse(CampaignRecorderChecker.shouldRecord(realtimeCall = false, isSms = false))
    }

    @Test
    fun `ignores historical SMS re-scans`() {
        assertFalse(CampaignRecorderChecker.shouldRecord(realtimeCall = false, isSms = true))
    }
}
