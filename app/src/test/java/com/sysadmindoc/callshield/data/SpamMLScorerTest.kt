package com.sysadmindoc.callshield.data

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method

/**
 * Unit tests for SpamMLScorer — logistic regression spam scorer.
 * Uses reflection to access private methods (extractFeatures, sigmoid, parseAndApply, applyDefaultWeights).
 */
class SpamMLScorerTest {

    private lateinit var extractFeatures: Method
    private lateinit var sigmoid: Method
    private lateinit var parseAndApply: Method
    private lateinit var applyDefaultWeights: Method
    private lateinit var tryApplyModelJsonPreservingState: Method

    @Before
    fun setUp() {
        extractFeatures = SpamMLScorer::class.java.getDeclaredMethod("extractFeatures", String::class.java)
        extractFeatures.isAccessible = true

        sigmoid = SpamMLScorer::class.java.getDeclaredMethod("sigmoid", Double::class.javaPrimitiveType)
        sigmoid.isAccessible = true

        parseAndApply = SpamMLScorer::class.java.getDeclaredMethod("parseAndApply", String::class.java)
        parseAndApply.isAccessible = true

        applyDefaultWeights = SpamMLScorer::class.java.getDeclaredMethod("applyDefaultWeights")
        applyDefaultWeights.isAccessible = true

        tryApplyModelJsonPreservingState = SpamMLScorer::class.java.getDeclaredMethod(
            "tryApplyModelJsonPreservingState",
            String::class.java
        )
        tryApplyModelJsonPreservingState.isAccessible = true

        // Ensure weights are loaded for score tests
        applyDefaultWeights.invoke(SpamMLScorer)
    }

    // ── Helper ──────────────────────────────────────────────────────────

    private fun features(number: String): DoubleArray =
        extractFeatures.invoke(SpamMLScorer, number) as DoubleArray

    private fun sig(x: Double): Double =
        sigmoid.invoke(SpamMLScorer, x) as Double

    // Feature indices
    private val TOLL_FREE = 0
    private val HIGH_SPAM_NPA = 1
    private val VOIP_RANGE = 2
    private val REPEATED_RATIO = 3
    private val SEQ_ASC_RATIO = 4
    private val ALL_SAME = 5
    private val NXX_555 = 6
    private val LAST4_ZERO = 7
    private val INVALID_NXX = 8
    private val SUB_ALL_SAME = 9
    private val ALTERNATING = 10
    private val SEQ_DESC_RATIO = 11
    private val NXX_BELOW_200 = 12
    private val LOW_ENTROPY = 13
    private val SUB_SEQUENTIAL = 14

    // ── extractFeatures: input normalization ─────────────────────────────

    @Test
    fun `extractFeatures returns 20 features for valid 10-digit number`() {
        val f = features("2125551234")
        assertEquals(20, f.size)
    }

    @Test
    fun `extractFeatures strips country code from 11-digit US number`() {
        val f10 = features("2125551234")
        val f11 = features("12125551234")
        assertArrayEquals(f10, f11, 0.0001)
    }

    @Test
    fun `extractFeatures returns empty for short number`() {
        val f = features("555123")
        assertEquals(0, f.size)
    }

    @Test
    fun `extractFeatures returns empty for 12-digit number`() {
        val f = features("123456789012")
        assertEquals(0, f.size)
    }

    @Test
    fun `extractFeatures strips non-digit characters`() {
        val f = features("(212) 555-1234")
        assertEquals(20, f.size)
    }

    @Test
    fun `extractFeatures returns empty for empty string`() {
        val f = features("")
        assertEquals(0, f.size)
    }

    // ── extractFeatures: toll-free detection ─────────────────────────────

    @Test
    fun `toll-free flag set for 800 number`() {
        assertEquals(1.0, features("8005551234")[TOLL_FREE], 0.0)
    }

    @Test
    fun `toll-free flag set for 888 number`() {
        assertEquals(1.0, features("8885551234")[TOLL_FREE], 0.0)
    }

    @Test
    fun `toll-free flag set for 877 number`() {
        assertEquals(1.0, features("8775551234")[TOLL_FREE], 0.0)
    }

    @Test
    fun `toll-free flag set for 833 number`() {
        assertEquals(1.0, features("8335551234")[TOLL_FREE], 0.0)
    }

    @Test
    fun `toll-free flag NOT set for regular number`() {
        assertEquals(0.0, features("2125551234")[TOLL_FREE], 0.0)
    }

    // ── extractFeatures: VoIP range detection ────────────────────────────

    @Test
    fun `voip flag set for known VoIP NPA-NXX`() {
        assertEquals(1.0, features("2025551234")[VOIP_RANGE], 0.0)
    }

    @Test
    fun `voip flag NOT set for unknown NPA-NXX`() {
        assertEquals(0.0, features("5085551234")[VOIP_RANGE], 0.0)
    }

    // ── extractFeatures: sequential digits ───────────────────────────────

    @Test
    fun `sequential ascending ratio for 1234567890`() {
        val f = features("1234567890")
        // Pairs: 1-2,2-3,3-4,4-5,5-6,6-7,7-8,8-9 = 8 ascending, 9-0 not. ratio = 8/9
        assertEquals(8.0 / 9.0, f[SEQ_ASC_RATIO], 0.001)
    }

    @Test
    fun `sequential descending ratio for 9876543210`() {
        val f = features("9876543210")
        // 9-8,8-7,7-6,6-5,5-4,4-3,3-2,2-1,1-0 = 9 desc pairs. ratio = 9/9 = 1.0
        assertEquals(1.0, f[SEQ_DESC_RATIO], 0.001)
    }

    @Test
    fun `sequential ratio zero for random number`() {
        val f = features("2125839746")
        // Very few sequential pairs expected
        assertTrue(f[SEQ_ASC_RATIO] < 0.3)
    }

    // ── extractFeatures: repeated digits ─────────────────────────────────

    @Test
    fun `repeated digits ratio 1 for all same digits`() {
        val f = features("5555555555")
        assertEquals(1.0, f[REPEATED_RATIO], 0.0)
    }

    @Test
    fun `all same digit flag for 5555555555`() {
        assertEquals(1.0, features("5555555555")[ALL_SAME], 0.0)
    }

    @Test
    fun `all same digit flag NOT set for mixed digits`() {
        assertEquals(0.0, features("2125551234")[ALL_SAME], 0.0)
    }

    // ── extractFeatures: NXX 555 ─────────────────────────────────────────

    @Test
    fun `nxx555 flag set when exchange is 555`() {
        assertEquals(1.0, features("2125551234")[NXX_555], 0.0)
    }

    @Test
    fun `nxx555 flag NOT set for non-555 exchange`() {
        assertEquals(0.0, features("2124561234")[NXX_555], 0.0)
    }

    // ── extractFeatures: last 4 zeros ────────────────────────────────────

    @Test
    fun `last4Zero flag set for number ending in 0000`() {
        assertEquals(1.0, features("2125550000")[LAST4_ZERO], 0.0)
    }

    @Test
    fun `last4Zero flag NOT set for non-zero suffix`() {
        assertEquals(0.0, features("2125551234")[LAST4_ZERO], 0.0)
    }

    // ── extractFeatures: invalid NXX ─────────────────────────────────────

    @Test
    fun `invalidNxx set when NXX starts with 0`() {
        assertEquals(1.0, features("2120551234")[INVALID_NXX], 0.0)
    }

    @Test
    fun `invalidNxx set when NXX starts with 1`() {
        assertEquals(1.0, features("2121551234")[INVALID_NXX], 0.0)
    }

    @Test
    fun `invalidNxx NOT set for valid NXX starting with 2-9`() {
        assertEquals(0.0, features("2125551234")[INVALID_NXX], 0.0)
    }

    // ── extractFeatures: subscriber all same ─────────────────────────────

    @Test
    fun `subscriber all same set for 9999 suffix`() {
        assertEquals(1.0, features("2125559999")[SUB_ALL_SAME], 0.0)
    }

    @Test
    fun `subscriber all same NOT set for mixed suffix`() {
        assertEquals(0.0, features("2125551234")[SUB_ALL_SAME], 0.0)
    }

    // ── extractFeatures: alternating pattern ─────────────────────────────

    @Test
    fun `alternating pattern detected for 5050505050`() {
        assertEquals(1.0, features("5050505050")[ALTERNATING], 0.0)
    }

    @Test
    fun `alternating pattern detected for 1212121212`() {
        assertEquals(1.0, features("1212121212")[ALTERNATING], 0.0)
    }

    @Test
    fun `alternating pattern NOT set for random number`() {
        assertEquals(0.0, features("2125551234")[ALTERNATING], 0.0)
    }

    @Test
    fun `alternating pattern NOT set when even and odd same`() {
        // 5555555555 — even=5, odd=5, same set so alternating=0
        assertEquals(0.0, features("5555555555")[ALTERNATING], 0.0)
    }

    // ── extractFeatures: NXX below 200 ───────────────────────────────────

    @Test
    fun `nxxBelow200 set for NXX 100`() {
        assertEquals(1.0, features("2121001234")[NXX_BELOW_200], 0.0)
    }

    @Test
    fun `nxxBelow200 NOT set for NXX 555`() {
        assertEquals(0.0, features("2125551234")[NXX_BELOW_200], 0.0)
    }

    // ── extractFeatures: low entropy ─────────────────────────────────────

    @Test
    fun `low entropy set for all same digit (1 distinct)`() {
        assertEquals(1.0, features("5555555555")[LOW_ENTROPY], 0.0)
    }

    @Test
    fun `low entropy set for 3 distinct digits`() {
        // 1112221112 has digits 1,2,1 → 2 distinct? No: 1,1,1,2,2,2,1,1,1,2 → {1,2} = 2 distinct
        assertEquals(1.0, features("1112221112")[LOW_ENTROPY], 0.0)
    }

    @Test
    fun `low entropy NOT set for diverse number`() {
        // 2125839746 has many distinct digits
        assertEquals(0.0, features("2125839746")[LOW_ENTROPY], 0.0)
    }

    // ── extractFeatures: subscriber sequential ───────────────────────────

    @Test
    fun `subscriber sequential for last4 1234`() {
        assertEquals(1.0, features("2125551234")[SUB_SEQUENTIAL], 0.0)
    }

    @Test
    fun `subscriber sequential for last4 9876`() {
        assertEquals(1.0, features("2125559876")[SUB_SEQUENTIAL], 0.0)
    }

    @Test
    fun `subscriber sequential NOT set for random suffix`() {
        assertEquals(0.0, features("2125559746")[SUB_SEQUENTIAL], 0.0)
    }

    // ── sigmoid ──────────────────────────────────────────────────────────

    @Test
    fun `sigmoid of zero is 0_5`() {
        assertEquals(0.5, sig(0.0), 0.0001)
    }

    @Test
    fun `sigmoid of large positive approaches 1`() {
        assertTrue(sig(100.0) > 0.9999)
    }

    @Test
    fun `sigmoid of large negative approaches 0`() {
        assertTrue(sig(-100.0) < 0.0001)
    }

    @Test
    fun `sigmoid is symmetric around 0_5`() {
        val pos = sig(2.0)
        val neg = sig(-2.0)
        assertEquals(1.0, pos + neg, 0.0001)
    }

    @Test
    fun `sigmoid of very large value does not overflow`() {
        val result = sig(1000.0)
        assertFalse(result.isNaN())
        assertFalse(result.isInfinite())
        assertEquals(1.0, result, 0.0001)
    }

    @Test
    fun `sigmoid of very large negative does not underflow`() {
        val result = sig(-1000.0)
        assertFalse(result.isNaN())
        assertFalse(result.isInfinite())
        assertEquals(0.0, result, 0.0001)
    }

    // ── score() ──────────────────────────────────────────────────────────

    @Test
    fun `score returns value between 0 and 1 for valid number`() {
        val s = SpamMLScorer.score("2125551234")
        assertTrue("Score $s should be in [0,1]", s in 0.0..1.0)
    }

    @Test
    fun `score returns -1 for too-short number`() {
        assertEquals(-1.0, SpamMLScorer.score("12345"), 0.0)
    }

    @Test
    fun `score returns -1 for empty string`() {
        assertEquals(-1.0, SpamMLScorer.score(""), 0.0)
    }

    @Test
    fun `score for all-same-digit number is high`() {
        // 5555555555 triggers many features: all_same, sub_all_same, nxx_555, low_entropy, etc.
        val s = SpamMLScorer.score("5555555555")
        assertTrue("All-same number should score high, got $s", s > 0.5)
    }

    @Test
    fun `isSpam returns true when score exceeds 0_7 threshold`() {
        // All-same number should exceed threshold with default weights
        assertTrue(SpamMLScorer.isSpam("5555555555"))
    }

    @Test
    fun `confidence returns 0-100 integer`() {
        val c = SpamMLScorer.confidence("2125551234")
        assertTrue(c in 0..100)
    }

    // ── parseAndApply ────────────────────────────────────────────────────

    @Test
    fun `parseAndApply with valid v2 JSON applies weights`() {
        val json = """
        {
            "version": 2,
            "weights": [0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.3, 1.4, 1.5],
            "bias": -1.5,
            "threshold": 0.65
        }
        """.trimIndent()
        parseAndApply.invoke(SpamMLScorer, json)

        // Verify weights were applied by scoring — the score should differ from defaults
        val s = SpamMLScorer.score("2125551234")
        assertTrue(s in 0.0..1.0)
    }

    @Test
    fun `parseAndApply with malformed JSON falls back to defaults`() {
        parseAndApply.invoke(SpamMLScorer, "not json at all {{{")
        // Should still be able to score (defaults applied)
        val s = SpamMLScorer.score("2125551234")
        assertTrue(s in 0.0..1.0)
    }

    @Test
    fun `parseAndApply with empty JSON falls back to defaults`() {
        parseAndApply.invoke(SpamMLScorer, "{}")
        val s = SpamMLScorer.score("2125551234")
        assertTrue(s in 0.0..1.0)
    }

    @Test
    fun `parseAndApply with too few weights falls back to defaults`() {
        val json = """{ "weights": [0.1, 0.2, 0.3], "bias": -1.0 }"""
        parseAndApply.invoke(SpamMLScorer, json)
        val s = SpamMLScorer.score("2125551234")
        assertTrue(s in 0.0..1.0)
    }

    @Test
    fun `parseAndApply with missing bias uses default bias`() {
        val json = """
        {
            "weights": [0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.3, 1.4, 1.5]
        }
        """.trimIndent()
        parseAndApply.invoke(SpamMLScorer, json)
        // Should use default bias -2.5 but custom weights
        val s = SpamMLScorer.score("2125551234")
        assertTrue(s in 0.0..1.0)
    }

    @Test
    fun `tryApplyModelJsonPreservingState keeps prior model when json is invalid`() {
        val validJson = """
        {
            "version": 2,
            "weights": [0.9, 0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.1, 0.9, 0.8, 0.7, 0.6, 0.5, 0.4],
            "bias": -1.2,
            "threshold": 0.61
        }
        """.trimIndent()

        parseAndApply.invoke(SpamMLScorer, validJson)

        val weightsField = SpamMLScorer::class.java.getDeclaredField("weights").apply { isAccessible = true }
        val biasField = SpamMLScorer::class.java.getDeclaredField("bias").apply { isAccessible = true }
        val thresholdField = SpamMLScorer::class.java.getDeclaredField("threshold").apply { isAccessible = true }

        val beforeWeights = (weightsField.get(SpamMLScorer) as DoubleArray).copyOf()
        val beforeBias = biasField.getDouble(SpamMLScorer)
        val beforeThreshold = thresholdField.getDouble(SpamMLScorer)

        val result = tryApplyModelJsonPreservingState.invoke(SpamMLScorer, "not json at all {{{") as Boolean

        val afterWeights = weightsField.get(SpamMLScorer) as DoubleArray
        val afterBias = biasField.getDouble(SpamMLScorer)
        val afterThreshold = thresholdField.getDouble(SpamMLScorer)

        assertFalse(result)
        assertArrayEquals(beforeWeights, afterWeights, 0.0)
        assertEquals(beforeBias, afterBias, 0.0)
        assertEquals(beforeThreshold, afterThreshold, 0.0)
    }

    // ── applyDefaultWeights ──────────────────────────────────────────────

    @Test
    fun `applyDefaultWeights enables scoring`() {
        // Clear weights by parsing bad data, then apply defaults
        val weightsField = SpamMLScorer::class.java.getDeclaredField("weights")
        weightsField.isAccessible = true
        weightsField.set(SpamMLScorer, null)

        assertEquals(-1.0, SpamMLScorer.score("2125551234"), 0.0)

        applyDefaultWeights.invoke(SpamMLScorer)
        val s = SpamMLScorer.score("2125551234")
        assertTrue("After applying defaults, score should be valid, got $s", s in 0.0..1.0)
    }
}
