package com.sysadmindoc.callshield.domain.model

import com.sysadmindoc.callshield.data.checker.BlockResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlockReasonCodeTest {
    @Test
    fun `checker source variants collapse to stable codes`() {
        assertEquals(BlockReasonCode.DATABASE, BlockReasonCode.fromMatchSource("database_exact"))
        assertEquals(BlockReasonCode.HEURISTIC, BlockReasonCode.fromMatchSource("heuristic_neighbor_spoof"))
        assertEquals(BlockReasonCode.RCS_FILTER, BlockReasonCode.fromMatchSource("rcs_database"))
        assertEquals(BlockReasonCode.CATEGORY_POLICY, BlockReasonCode.fromMatchSource("category_policy:scam:block:database"))
        assertEquals(BlockReasonCode.UNKNOWN, BlockReasonCode.fromMatchSource("a_new_unregistered_layer"))
    }

    @Test
    fun `block result carries code and deciding rule id`() {
        val result = BlockResult.block("wildcard", ruleId = 42L)

        assertEquals(BlockReasonCode.WILDCARD, result.reasonCode)
        assertEquals(42L, result.ruleId)
    }

    @Test
    fun `stored legacy text maps without changing the original source`() {
        assertEquals(BlockReasonCode.USER_BLOCKLIST, BlockReasonCode.fromStored("user_blocklist"))
        assertEquals(BlockReasonCode.UNKNOWN, BlockReasonCode.fromStored("old human explanation"))
        assertNull(BlockResult.allow("manual_whitelist").ruleId)
    }
}
