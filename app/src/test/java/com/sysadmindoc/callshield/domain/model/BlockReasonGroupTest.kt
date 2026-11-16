package com.sysadmindoc.callshield.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlockReasonGroupTest {
    @Test
    fun `every reason code sits in exactly one group`() {
        BlockReasonCode.entries.forEach { code ->
            val holders = BlockReasonGroup.entries.filter { code in it.codes }
            assertEquals("$code", listOf(BlockReasonGroup.of(code)), holders)
        }
    }

    @Test
    fun `the named groups hold the checks the roadmap names`() {
        val expected =
            mapOf(
                BlockReasonCode.DATABASE to BlockReasonGroup.DATABASE,
                BlockReasonCode.DB_PREFIX_EXPANSION to BlockReasonGroup.DATABASE,
                BlockReasonCode.HOT_LIST to BlockReasonGroup.COMMUNITY,
                BlockReasonCode.USER_BLOCKLIST to BlockReasonGroup.YOUR_RULES,
                BlockReasonCode.WILDCARD to BlockReasonGroup.YOUR_RULES,
                BlockReasonCode.PREFIX to BlockReasonGroup.YOUR_RULES,
                BlockReasonCode.KEYWORD to BlockReasonGroup.YOUR_RULES,
                BlockReasonCode.TIME_BLOCK to BlockReasonGroup.YOUR_RULES,
                BlockReasonCode.ML_SCORER to BlockReasonGroup.ML,
                BlockReasonCode.CAMPAIGN_BURST to BlockReasonGroup.CAMPAIGNS,
                BlockReasonCode.SMS_BURST to BlockReasonGroup.CAMPAIGNS,
                BlockReasonCode.HEURISTIC to BlockReasonGroup.OTHER,
                BlockReasonCode.SMS_CONTENT to BlockReasonGroup.OTHER,
                BlockReasonCode.UNKNOWN to BlockReasonGroup.OTHER,
            )
        assertEquals(expected, expected.keys.associateWith(BlockReasonGroup::of))
    }

    @Test
    fun `totals add each stored code to its group and keep empty groups at zero`() {
        val totals =
            BlockReasonGroup.totals(
                mapOf(
                    "wildcard" to 3,
                    "user_blocklist" to 2,
                    "database" to 4,
                    "db_prefix_expansion" to 1,
                    "something an old version wrote" to 5,
                ),
            )

        assertEquals(
            mapOf(
                BlockReasonGroup.DATABASE to 5,
                BlockReasonGroup.COMMUNITY to 0,
                BlockReasonGroup.YOUR_RULES to 5,
                BlockReasonGroup.ML to 0,
                BlockReasonGroup.CAMPAIGNS to 0,
                BlockReasonGroup.OTHER to 5,
            ),
            totals,
        )
    }

    @Test
    fun `a saved key finds its group again`() {
        BlockReasonGroup.entries.forEach { assertEquals(it, BlockReasonGroup.fromKey(it.key)) }
        assertNull(BlockReasonGroup.fromKey(""))
        assertNull(BlockReasonGroup.fromKey(null))
    }
}
