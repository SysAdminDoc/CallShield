package com.sysadmindoc.callshield.data.checker

import org.junit.Assert.assertTrue
import org.junit.Test

class AnsweredCallerCheckerTest {
    @Test
    fun `answered-caller trust yields to every explicit block rule`() {
        assertTrue(CheckerPriority.ANSWERED_CALLER < CheckerPriority.USER_BLOCKLIST)
        assertTrue(CheckerPriority.ANSWERED_CALLER < CheckerPriority.SYSTEM_BLOCK_LIST)
        assertTrue(CheckerPriority.ANSWERED_CALLER < CheckerPriority.PREFIX_MATCH)
        assertTrue(CheckerPriority.ANSWERED_CALLER < CheckerPriority.WILDCARD_RULE)
        assertTrue(CheckerPriority.ANSWERED_CALLER < CheckerPriority.HASH_WILDCARD_RULE)
        assertTrue(CheckerPriority.ANSWERED_CALLER < CheckerPriority.STIR_SHAKEN)
    }

    @Test
    fun `answered-caller trust stays between callback and weak statistical layers`() {
        assertTrue(CheckerPriority.ANSWERED_CALLER < CheckerPriority.RECENTLY_DIALED)
        assertTrue(CheckerPriority.ANSWERED_CALLER > CheckerPriority.REPEATED_URGENT)
        assertTrue(CheckerPriority.ANSWERED_CALLER > CheckerPriority.TIME_BLOCK)
        assertTrue(CheckerPriority.ANSWERED_CALLER > CheckerPriority.FREQUENCY_ESCALATION)
        assertTrue(CheckerPriority.ANSWERED_CALLER > CheckerPriority.HEURISTIC)
        assertTrue(CheckerPriority.ANSWERED_CALLER > CheckerPriority.CAMPAIGN_BURST)
        assertTrue(CheckerPriority.ANSWERED_CALLER > CheckerPriority.ML_SCORER)
    }
}
