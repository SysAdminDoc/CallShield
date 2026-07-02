package com.sysadmindoc.callshield.data.checker

import org.junit.Assert.assertTrue
import org.junit.Test

class SmsBurstCheckerTest {
    @Test
    fun `sms burst yields to context trust and explicit rules`() {
        assertTrue(CheckerPriority.SMS_BURST < CheckerPriority.WILDCARD_RULE)
        assertTrue(CheckerPriority.SMS_BURST < CheckerPriority.HASH_WILDCARD_RULE)
        assertTrue(CheckerPriority.SMS_BURST < CheckerPriority.PUSH_ALERT_BRIDGE)
    }

    @Test
    fun `sms burst runs before generic statistical and content layers`() {
        assertTrue(CheckerPriority.SMS_BURST > CheckerPriority.TIME_BLOCK)
        assertTrue(CheckerPriority.SMS_BURST > CheckerPriority.HEURISTIC)
        assertTrue(CheckerPriority.SMS_BURST > CheckerPriority.ML_SCORER)
    }
}
