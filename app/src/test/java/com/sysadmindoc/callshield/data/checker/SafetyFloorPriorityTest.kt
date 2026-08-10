package com.sysadmindoc.callshield.data.checker

import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyFloorPriorityTest {
    @Test
    fun `safety floors outrank every existing checker`() {
        val existingPriorities =
            listOf(
                CheckerPriority.MANUAL_WHITELIST,
                CheckerPriority.CONTACT_WHITELIST,
                CheckerPriority.CONTACTS_ONLY,
                CheckerPriority.STIR_SHAKEN,
                CheckerPriority.USER_BLOCKLIST,
                CheckerPriority.SYSTEM_BLOCK_LIST,
                CheckerPriority.WILDCARD_RULE,
                CheckerPriority.HASH_WILDCARD_RULE,
                CheckerPriority.TEMPORARY_ALLOW,
                CheckerPriority.PREFIX_MATCH,
                CheckerPriority.GITHUB_DATABASE,
                CheckerPriority.DB_PREFIX_EXPANSION,
                CheckerPriority.RECENTLY_DIALED,
                CheckerPriority.EMERGENCY_CALLBACK,
                CheckerPriority.ANSWERED_CALLER,
                CheckerPriority.REPEATED_URGENT,
                CheckerPriority.CALLER_NAME_TRUST,
                CheckerPriority.PUSH_ALERT_BRIDGE,
                CheckerPriority.SMS_BURST,
                CheckerPriority.CAMPAIGN_RECORDER,
                CheckerPriority.REGION_BLOCK,
                CheckerPriority.TIME_BLOCK,
                CheckerPriority.FREQUENCY_ESCALATION,
                CheckerPriority.HEURISTIC,
                CheckerPriority.CAMPAIGN_BURST,
                CheckerPriority.CALLER_NAME_BLOCK,
                CheckerPriority.ML_SCORER,
                CheckerPriority.SMS_KEYWORD,
                CheckerPriority.SMS_CONTEXT_TRUST,
                CheckerPriority.SMS_CONTENT,
            )
        assertTrue(existingPriorities.all { it < CheckerPriority.OTP_FLOOR })
        assertTrue(CheckerPriority.OTP_FLOOR < CheckerPriority.EMERGENCY_FLOOR)
        assertTrue(CheckerPriority.isSafetyFloor(CheckerPriority.EMERGENCY_FLOOR_MATCH_SOURCE))
        assertTrue(CheckerPriority.isSafetyFloor(CheckerPriority.OTP_FLOOR_MATCH_SOURCE))
    }
}
