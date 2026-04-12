package com.sysadmindoc.callshield.data

import org.junit.Assert.*
import org.junit.After
import org.junit.Test

/**
 * Unit tests for SpamHeuristics — heuristic spam detection engine.
 * Tests only the pure-logic methods that don't require Android Context.
 */
class SpamHeuristicsTest {

    @After
    fun tearDown() {
        // Reset hot campaign ranges after each test
        SpamHeuristics.updateHotRanges(emptyList())
        // Reset the contacts LRU so cache-state doesn't leak between tests
        SpamHeuristics.clearContactCache()
    }

    @Test
    fun `clearContactCache does not throw when cache is empty`() {
        // Smoke test — the cache starts empty and must tolerate a clear
        SpamHeuristics.clearContactCache()
        SpamHeuristics.clearContactCache()
    }

    // ── isTollFree ───────────────────────────────────────────────────────

    @Test
    fun `isTollFree returns true for 800 prefix`() {
        assertTrue(SpamHeuristics.isTollFree("8005551234"))
    }

    @Test
    fun `isTollFree returns true for 888 prefix`() {
        assertTrue(SpamHeuristics.isTollFree("8885551234"))
    }

    @Test
    fun `isTollFree returns true for 877 prefix`() {
        assertTrue(SpamHeuristics.isTollFree("8775551234"))
    }

    @Test
    fun `isTollFree returns true for 866 prefix`() {
        assertTrue(SpamHeuristics.isTollFree("8665551234"))
    }

    @Test
    fun `isTollFree returns true for 855 prefix`() {
        assertTrue(SpamHeuristics.isTollFree("8555551234"))
    }

    @Test
    fun `isTollFree returns true for 844 prefix`() {
        assertTrue(SpamHeuristics.isTollFree("8445551234"))
    }

    @Test
    fun `isTollFree returns true for 833 prefix`() {
        assertTrue(SpamHeuristics.isTollFree("8335551234"))
    }

    @Test
    fun `isTollFree returns false for regular area code`() {
        assertFalse(SpamHeuristics.isTollFree("2125551234"))
    }

    @Test
    fun `isTollFree handles formatted number with parens and dashes`() {
        assertTrue(SpamHeuristics.isTollFree("(800) 555-1234"))
    }

    @Test
    fun `isTollFree handles number with country code prefix`() {
        // takeLast(10) of "18005551234" = "8005551234"
        assertTrue(SpamHeuristics.isTollFree("18005551234"))
    }

    @Test
    fun `isTollFree returns false for short number`() {
        assertFalse(SpamHeuristics.isTollFree("800123"))
    }

    @Test
    fun `isTollFree returns false for empty string`() {
        assertFalse(SpamHeuristics.isTollFree(""))
    }

    // ── isWangiriCountryCode ─────────────────────────────────────────────

    @Test
    fun `isWangiriCountryCode detects Sierra Leone 232`() {
        assertTrue(SpamHeuristics.isWangiriCountryCode("+2321234567"))
    }

    @Test
    fun `isWangiriCountryCode detects Somalia 252`() {
        assertTrue(SpamHeuristics.isWangiriCountryCode("2521234567"))
    }

    @Test
    fun `isWangiriCountryCode detects Belarus 375`() {
        assertTrue(SpamHeuristics.isWangiriCountryCode("+375123456789"))
    }

    @Test
    fun `isWangiriCountryCode detects Jamaica 876 (Caribbean)`() {
        // Jamaica uses +1-876 format but code checks if clean starts with "876"
        assertTrue(SpamHeuristics.isWangiriCountryCode("+8761234567"))
    }

    @Test
    fun `isWangiriCountryCode detects Tuvalu 688`() {
        assertTrue(SpamHeuristics.isWangiriCountryCode("+688123456"))
    }

    @Test
    fun `isWangiriCountryCode returns false for US 11-digit number`() {
        // US number starting with 1 and length 11 is excluded
        assertFalse(SpamHeuristics.isWangiriCountryCode("12125551234"))
    }

    @Test
    fun `isWangiriCountryCode returns false for safe country (UK 44)`() {
        assertFalse(SpamHeuristics.isWangiriCountryCode("+4420123456"))
    }

    @Test
    fun `isWangiriCountryCode returns false for safe country (Germany 49)`() {
        assertFalse(SpamHeuristics.isWangiriCountryCode("+491234567890"))
    }

    @Test
    fun `isWangiriCountryCode handles plus prefix`() {
        // removePrefix("+") works
        assertTrue(SpamHeuristics.isWangiriCountryCode("+2521234567"))
    }

    // ── isInternationalPremium ───────────────────────────────────────────

    @Test
    fun `isInternationalPremium detects 900 prefix`() {
        assertTrue(SpamHeuristics.isInternationalPremium("9001234567"))
    }

    @Test
    fun `isInternationalPremium detects 976 prefix`() {
        assertTrue(SpamHeuristics.isInternationalPremium("9761234567"))
    }

    @Test
    fun `isInternationalPremium detects 1900 with country code`() {
        assertTrue(SpamHeuristics.isInternationalPremium("+19001234567"))
    }

    @Test
    fun `isInternationalPremium returns false for regular number`() {
        assertFalse(SpamHeuristics.isInternationalPremium("2125551234"))
    }

    // ── isHighSpamVoipRange ──────────────────────────────────────────────

    @Test
    fun `isHighSpamVoipRange detects known VoIP NPA-NXX`() {
        assertTrue(SpamHeuristics.isHighSpamVoipRange("2025551234"))
    }

    @Test
    fun `isHighSpamVoipRange detects Twilio range`() {
        assertTrue(SpamHeuristics.isHighSpamVoipRange("2064551234"))
    }

    @Test
    fun `isHighSpamVoipRange returns false for unknown range`() {
        assertFalse(SpamHeuristics.isHighSpamVoipRange("5085551234"))
    }

    @Test
    fun `isHighSpamVoipRange returns false for short number`() {
        assertFalse(SpamHeuristics.isHighSpamVoipRange("12345"))
    }

    @Test
    fun `isHighSpamVoipRange handles formatted number`() {
        assertTrue(SpamHeuristics.isHighSpamVoipRange("(202) 555-1234"))
    }

    // ── isInvalidFormat ──────────────────────────────────────────────────

    @Test
    fun `isInvalidFormat returns true for 4-digit number`() {
        assertTrue(SpamHeuristics.isInvalidFormat("1234"))
    }

    @Test
    fun `isInvalidFormat returns true for 7-digit number`() {
        assertTrue(SpamHeuristics.isInvalidFormat("5551234"))
    }

    @Test
    fun `isInvalidFormat returns false for 10-digit number`() {
        assertFalse(SpamHeuristics.isInvalidFormat("2125551234"))
    }

    @Test
    fun `isInvalidFormat returns false for 11-digit number`() {
        assertFalse(SpamHeuristics.isInvalidFormat("12125551234"))
    }

    @Test
    fun `isInvalidFormat returns false for 5-digit short code`() {
        assertFalse(SpamHeuristics.isInvalidFormat("12345"))
    }

    @Test
    fun `isInvalidFormat returns false for 6-digit short code`() {
        assertFalse(SpamHeuristics.isInvalidFormat("123456"))
    }

    // ── Hot Campaign Range ───────────────────────────────────────────────

    @Test
    fun `isHotCampaignRange returns false when no ranges set`() {
        SpamHeuristics.updateHotRanges(emptyList())
        assertFalse(SpamHeuristics.isHotCampaignRange("2125551234"))
    }

    @Test
    fun `isHotCampaignRange returns true when NPA-NXX matches`() {
        SpamHeuristics.updateHotRanges(listOf("212555"))
        assertTrue(SpamHeuristics.isHotCampaignRange("2125551234"))
    }

    @Test
    fun `isHotCampaignRange returns false for non-matching range`() {
        SpamHeuristics.updateHotRanges(listOf("212555"))
        assertFalse(SpamHeuristics.isHotCampaignRange("3105551234"))
    }

    @Test
    fun `hasHotRanges returns true when ranges are set`() {
        SpamHeuristics.updateHotRanges(listOf("212555"))
        assertTrue(SpamHeuristics.hasHotRanges())
    }

    @Test
    fun `hasHotRanges returns false when empty`() {
        SpamHeuristics.updateHotRanges(emptyList())
        assertFalse(SpamHeuristics.hasHotRanges())
    }

    @Test
    fun `isHotCampaignRange handles formatted number`() {
        SpamHeuristics.updateHotRanges(listOf("212555"))
        assertTrue(SpamHeuristics.isHotCampaignRange("(212) 555-9999"))
    }

    @Test
    fun `isHotCampaignRange returns false for too-short number`() {
        SpamHeuristics.updateHotRanges(listOf("212555"))
        assertFalse(SpamHeuristics.isHotCampaignRange("21255"))
    }

    // ── isRapidFire ──────────────────────────────────────────────────────

    @Test
    fun `isRapidFire returns true when threshold met`() {
        val now = System.currentTimeMillis()
        val recent = listOf(
            "2125551234" to now - 1000,
            "2125551234" to now - 2000,
            "2125551234" to now - 3000
        )
        assertTrue(SpamHeuristics.isRapidFire(recent, "2125551234"))
    }

    @Test
    fun `isRapidFire returns false when below threshold`() {
        val now = System.currentTimeMillis()
        val recent = listOf(
            "2125551234" to now - 1000,
            "2125551234" to now - 2000
        )
        assertFalse(SpamHeuristics.isRapidFire(recent, "2125551234"))
    }

    @Test
    fun `isRapidFire ignores calls outside window`() {
        val now = System.currentTimeMillis()
        val recent = listOf(
            "2125551234" to now - 1000,
            "2125551234" to now - 2000,
            "2125551234" to (now - 7_200_000) // 2 hours ago, outside 1h window
        )
        assertFalse(SpamHeuristics.isRapidFire(recent, "2125551234"))
    }

    @Test
    fun `isRapidFire normalizes number formats`() {
        val now = System.currentTimeMillis()
        val recent = listOf(
            "+12125551234" to now - 1000,
            "12125551234" to now - 2000,
            "(212) 555-1234" to now - 3000
        )
        assertTrue(SpamHeuristics.isRapidFire(recent, "2125551234"))
    }

    // ── HeuristicResult ──────────────────────────────────────────────────

    @Test
    fun `HeuristicResult isSpam true at score 60`() {
        val result = SpamHeuristics.HeuristicResult(60, listOf("test"))
        assertTrue(result.isSpam)
    }

    @Test
    fun `HeuristicResult isSpam false at score 59`() {
        val result = SpamHeuristics.HeuristicResult(59, listOf("test"))
        assertFalse(result.isSpam)
    }

    @Test
    fun `HeuristicResult isSpam true at score 100`() {
        val result = SpamHeuristics.HeuristicResult(100, listOf("test"))
        assertTrue(result.isSpam)
    }
}
