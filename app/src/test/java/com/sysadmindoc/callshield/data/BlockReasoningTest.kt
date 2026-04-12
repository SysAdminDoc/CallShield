package com.sysadmindoc.callshield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockReasoningTest {

    @Test
    fun `user_blocklist explanation names the layer and includes note`() {
        val r = BlockReasoning.explain("user_blocklist", "Spammer, blocked manually", 100)
        assertTrue(r.headline.contains("You blocked"))
        assertTrue(r.bullets.any { it.contains("layer 5") })
        assertTrue(r.bullets.any { it.contains("Spammer") })
    }

    @Test
    fun `heuristic explanation expands comma-separated reasons into bullets`() {
        val r = BlockReasoning.explain("heuristic", "high_spam_npa, voip_spam_range, neighbor_spoof", 78)
        assertTrue(r.headline.contains("78%"))
        assertTrue(r.bullets.any { it.contains("high spam npa") })
        assertTrue(r.bullets.any { it.contains("voip spam range") })
        assertTrue(r.bullets.any { it.contains("neighbor spoof") })
    }

    @Test
    fun `campaign_burst explanation mentions NPA-NXX burst threshold`() {
        val r = BlockReasoning.explain("campaign_burst", "", 75)
        assertTrue(r.headline.contains("active spam campaign"))
        assertTrue(r.bullets.any { it.contains("5+ distinct numbers") })
    }

    @Test
    fun `ml_scorer explanation reassures the user that inference is on-device`() {
        val r = BlockReasoning.explain("ml_scorer", "", 84)
        assertTrue(r.headline.contains("84%"))
        assertTrue(r.bullets.any { it.contains("on your device") })
    }

    @Test
    fun `emergency_contact explanation is an allow-through headline, not a block`() {
        val r = BlockReasoning.explain("emergency_contact", "", 0)
        assertTrue(r.headline.contains("emergency"))
        assertTrue(r.bullets.any { it.contains("bypasses") })
    }

    @Test
    fun `rcs prefix explanation strips the rcs underscore`() {
        val r = BlockReasoning.explain("rcs_database", "", 100)
        assertTrue(r.headline.contains("RCS"))
        assertTrue(r.bullets.any { it.contains("database") })
    }

    @Test
    fun `unknown match reason falls back to a safe default`() {
        val r = BlockReasoning.explain("something_new", "ad-hoc desc", 42)
        assertTrue(r.headline.contains("something_new"))
        assertTrue(r.bullets.any { it.contains("ad-hoc desc") })
        assertTrue(r.bullets.any { it.contains("42%") })
    }

    @Test
    fun `blank match reason reads as allowed, not blocked`() {
        val r = BlockReasoning.explain("", "", 0)
        assertTrue(r.headline.contains("allowed"))
        assertEquals(1, r.bullets.size)
    }
}
