package com.sysadmindoc.callshield.data

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method

/**
 * Unit tests for CampaignDetector — NPA-NXX prefix clustering.
 */
class CampaignDetectorTest {

    /** Reflection handle for private extractNpaNxx(String): String? */
    private lateinit var extractNpaNxx: Method

    /** Reflection handle for the private recentPrefixes map so we can reset state between tests. */
    private val recentPrefixesField by lazy {
        CampaignDetector::class.java.getDeclaredField("recentPrefixes").also {
            it.isAccessible = true
        }
    }

    @Before
    fun setUp() {
        extractNpaNxx = CampaignDetector::class.java.getDeclaredMethod("extractNpaNxx", String::class.java)
        extractNpaNxx.isAccessible = true
        clearState()
    }

    @After
    fun tearDown() {
        clearState()
    }

    @Suppress("UNCHECKED_CAST")
    private fun clearState() {
        val map = recentPrefixesField.get(CampaignDetector) as MutableMap<*, *>
        map.clear()
    }

    // ─── extractNpaNxx tests ─────────────────────────────────────────

    @Test
    fun extractNpaNxx_10digit_returnsFirst6() {
        assertEquals("212555", callExtract("2125551234"))
    }

    @Test
    fun extractNpaNxx_11digit_plus1_returnsFirst6AfterCountryCode() {
        assertEquals("212555", callExtract("12125551234"))
    }

    @Test
    fun extractNpaNxx_formattedWithDashes() {
        assertEquals("212555", callExtract("212-555-1234"))
    }

    @Test
    fun extractNpaNxx_formattedWithParensAndSpaces() {
        assertEquals("212555", callExtract("(212) 555-1234"))
    }

    @Test
    fun extractNpaNxx_formattedWithPlusOne() {
        assertEquals("212555", callExtract("+1 (212) 555-1234"))
    }

    @Test
    fun extractNpaNxx_internationalNumber_returnsNull() {
        // 12 digits (not 10 or 11): international
        assertNull(callExtract("+44 20 7946 0958"))
    }

    @Test
    fun extractNpaNxx_shortCode_returnsNull() {
        assertNull(callExtract("72345"))
    }

    @Test
    fun extractNpaNxx_emptyString_returnsNull() {
        assertNull(callExtract(""))
    }

    @Test
    fun extractNpaNxx_tooFewDigits_returnsNull() {
        assertNull(callExtract("123"))
    }

    @Test
    fun extractNpaNxx_tooManyDigits_returnsNull() {
        // 12 digits that do NOT start with 1 => length != 10 and != 11
        assertNull(callExtract("123456789012"))
    }

    @Test
    fun extractNpaNxx_11digit_notStartingWith1_returnsNull() {
        // 11 digits but starts with 2 — not a valid +1 prefix
        assertNull(callExtract("22125551234"))
    }

    // ─── recordCall tests ────────────────────────────────────────────

    @Test
    fun recordCall_recordsCorrectPrefix() {
        CampaignDetector.recordCall("2125551234")
        // After 1 call, should NOT be active campaign (threshold=5)
        assertFalse(CampaignDetector.isActiveCampaign("2125559999"))
    }

    @Test
    fun recordCall_ignoresInvalidNumbers() {
        // Should not crash or create entries for invalid numbers
        CampaignDetector.recordCall("")
        CampaignDetector.recordCall("123")
        CampaignDetector.recordCall("+44 20 7946 0958")
        assertFalse(CampaignDetector.isActiveCampaign("2125551234"))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun recordCall_prunesOldEntries() {
        // We cannot easily inject a clock, but we can verify that calling recordCall
        // multiple times populates the map and doesn't throw.
        repeat(10) { i ->
            CampaignDetector.recordCall("212555${1000 + i}")
        }
        val map = recentPrefixesField.get(CampaignDetector) as Map<String, List<Long>>
        val timestamps = map["212555"]
        assertNotNull(timestamps)
        // All 10 calls are recent, so all should be present
        assertEquals(10, timestamps!!.size)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun recordCall_prunesExpiredPrefixesAcrossMap() {
        val map = recentPrefixesField.get(CampaignDetector) as MutableMap<String, MutableList<Long>>
        val staleTimestamp = System.currentTimeMillis() - 4_000_000L

        map["111111"] = mutableListOf(staleTimestamp)
        map["222222"] = mutableListOf(staleTimestamp, staleTimestamp)

        CampaignDetector.recordCall("2125551234")

        assertFalse(map.containsKey("111111"))
        assertFalse(map.containsKey("222222"))
        assertTrue(map.containsKey("212555"))
    }

    // ─── isActiveCampaign tests ──────────────────────────────────────

    @Test
    fun isActiveCampaign_belowThreshold_returnsFalse() {
        repeat(4) { i ->
            CampaignDetector.recordCall("312444${1000 + i}")
        }
        assertFalse(CampaignDetector.isActiveCampaign("3124441234"))
    }

    @Test
    fun isActiveCampaign_atThreshold_returnsTrue() {
        repeat(5) { i ->
            CampaignDetector.recordCall("312444${1000 + i}")
        }
        assertTrue(CampaignDetector.isActiveCampaign("3124441234"))
    }

    @Test
    fun isActiveCampaign_aboveThreshold_returnsTrue() {
        repeat(10) { i ->
            CampaignDetector.recordCall("312444${1000 + i}")
        }
        assertTrue(CampaignDetector.isActiveCampaign("3124449999"))
    }

    @Test
    fun isActiveCampaign_invalidNumber_returnsFalse() {
        assertFalse(CampaignDetector.isActiveCampaign(""))
        assertFalse(CampaignDetector.isActiveCampaign("123"))
    }

    @Test
    fun isActiveCampaign_differentPrefixNotAffected() {
        repeat(5) { i ->
            CampaignDetector.recordCall("312444${1000 + i}")
        }
        // Different prefix — should NOT be active
        assertFalse(CampaignDetector.isActiveCampaign("6465551234"))
    }

    // ─── getActiveCampaigns tests ────────────────────────────────────

    @Test
    fun getActiveCampaigns_returnsSortedByCount() {
        // Prefix A: 5 calls (at threshold)
        repeat(5) { i -> CampaignDetector.recordCall("312444${1000 + i}") }
        // Prefix B: 8 calls (above threshold)
        repeat(8) { i -> CampaignDetector.recordCall("646555${1000 + i}") }
        // Prefix C: 3 calls (below threshold — should be filtered out)
        repeat(3) { i -> CampaignDetector.recordCall("917222${1000 + i}") }

        val campaigns = CampaignDetector.getActiveCampaigns()
        assertEquals(2, campaigns.size)
        // Sorted descending by count
        assertEquals("646555", campaigns[0].first)
        assertEquals(8, campaigns[0].second)
        assertEquals("312444", campaigns[1].first)
        assertEquals(5, campaigns[1].second)
    }

    @Test
    fun getActiveCampaigns_emptyWhenNoCalls() {
        assertTrue(CampaignDetector.getActiveCampaigns().isEmpty())
    }

    @Test
    fun getActiveCampaigns_filtersBelowThreshold() {
        repeat(4) { i -> CampaignDetector.recordCall("312444${1000 + i}") }
        assertTrue(CampaignDetector.getActiveCampaigns().isEmpty())
    }

    // ─── Thread safety smoke test ────────────────────────────────────

    @Test
    fun threadSafety_rapidConcurrentCalls_doNotCrash() {
        val threads = (1..10).map { threadNum ->
            Thread {
                repeat(50) { i ->
                    CampaignDetector.recordCall("${200 + threadNum}555${1000 + i}")
                    CampaignDetector.isActiveCampaign("${200 + threadNum}5551234")
                    CampaignDetector.getActiveCampaigns()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(5000) }
        // If we get here without exceptions, the smoke test passes
    }

    // ─── Prefix extraction from various formats ──────────────────────

    @Test
    fun extractNpaNxx_dotFormatted() {
        assertEquals("212555", callExtract("212.555.1234"))
    }

    @Test
    fun extractNpaNxx_withLeadingSpaces() {
        assertEquals("212555", callExtract("  2125551234"))
    }

    // ─── Helper ──────────────────────────────────────────────────────

    private fun callExtract(number: String): String? {
        return extractNpaNxx.invoke(CampaignDetector, number) as String?
    }
}
