package com.sysadmindoc.callshield.data.checker

import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyCallbackCheckerTest {
    @Test
    fun `emergency-callback grace yields to every explicit block rule`() {
        assertTrue(CheckerPriority.EMERGENCY_CALLBACK < CheckerPriority.USER_BLOCKLIST)
        assertTrue(CheckerPriority.EMERGENCY_CALLBACK < CheckerPriority.SYSTEM_BLOCK_LIST)
        assertTrue(CheckerPriority.EMERGENCY_CALLBACK < CheckerPriority.PREFIX_MATCH)
        assertTrue(CheckerPriority.EMERGENCY_CALLBACK < CheckerPriority.WILDCARD_RULE)
        assertTrue(CheckerPriority.EMERGENCY_CALLBACK < CheckerPriority.HASH_WILDCARD_RULE)
        assertTrue(CheckerPriority.EMERGENCY_CALLBACK < CheckerPriority.STIR_SHAKEN)
    }

    @Test
    fun `emergency-callback grace sits above weaker statistical layers`() {
        assertTrue(CheckerPriority.EMERGENCY_CALLBACK < CheckerPriority.RECENTLY_DIALED)
        assertTrue(CheckerPriority.EMERGENCY_CALLBACK > CheckerPriority.ANSWERED_CALLER)
        assertTrue(CheckerPriority.EMERGENCY_CALLBACK > CheckerPriority.REPEATED_URGENT)
        assertTrue(CheckerPriority.EMERGENCY_CALLBACK > CheckerPriority.TIME_BLOCK)
        assertTrue(CheckerPriority.EMERGENCY_CALLBACK > CheckerPriority.FREQUENCY_ESCALATION)
        assertTrue(CheckerPriority.EMERGENCY_CALLBACK > CheckerPriority.HEURISTIC)
        assertTrue(CheckerPriority.EMERGENCY_CALLBACK > CheckerPriority.CAMPAIGN_BURST)
        assertTrue(CheckerPriority.EMERGENCY_CALLBACK > CheckerPriority.ML_SCORER)
    }
}
