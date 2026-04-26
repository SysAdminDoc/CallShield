package com.sysadmindoc.callshield.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneShotNoticeGateTest {

    @Test
    fun `shouldShow allows first notice and suppresses duplicate`() {
        val gate = OneShotNoticeGate(retentionMillis = 1_000L)

        assertTrue(gate.shouldShow("caller", nowMillis = 1_000L))
        assertFalse(gate.shouldShow("caller", nowMillis = 1_100L))
    }

    @Test
    fun `shouldShow allows notice again after retention window`() {
        val gate = OneShotNoticeGate(retentionMillis = 1_000L)

        assertTrue(gate.shouldShow("caller", nowMillis = 1_000L))
        assertTrue(gate.shouldShow("caller", nowMillis = 2_001L))
    }
}
