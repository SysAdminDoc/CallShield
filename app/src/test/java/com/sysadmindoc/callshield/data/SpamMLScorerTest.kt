package com.sysadmindoc.callshield.data

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method

/**
 * Unit tests for SpamMLScorer — on-device spam scorer.
 *
 * Uses reflection sparingly to cover behavior that isn't reachable from
 * the public surface (feature vector shape, sigmoid numerics, parser).
 * Behavioral tests (score, isSpam, confidence, verdict) go through the
 * public API — those are the contracts callers depend on.
 */
class SpamMLScorerTest {

    private lateinit var extractFeatures: Method
    private lateinit var sigmoid: Method
    private lateinit var parseModel: Method
    private lateinit var stateField: java.lang.reflect.Field

    @Before
    fun setUp() {
        extractFeatures = SpamMLScorer::class.java.getDeclaredMethod("extractFeatures", String::class.java)
        extractFeatures.isAccessible = true

        sigmoid = SpamMLScorer::class.java.getDeclaredMethod("sigmoid", Double::class.javaPrimitiveType)
        sigmoid.isAccessible = true

        parseModel = SpamMLScorer::class.java.getDeclaredMethod("parseModel", String::class.java)
        parseModel.isAccessible = true

        stateField = SpamMLScorer::class.java.getDeclaredField("state")
        stateField.isAccessible = true

        // Force defaults before each test so load order from other suites
        // never leaks a foreign model into this one.
        resetToDefaults()
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun features(number: String): DoubleArray =
        extractFeatures.invoke(SpamMLScorer, number) as DoubleArray

    private fun sig(x: Double): Double =
        sigmoid.invoke(SpamMLScorer, x) as Double

    /** Invoke the pure parser; returns null when the payload is un-parseable. */
    private fun parseOrNull(json: String): Any? = parseModel.invoke(SpamMLScorer, json)

    /** Commit a parsed ModelState (or reset to defaults when null). */
    private fun applyParsed(json: String): Boolean {
        val parsed = parseOrNull(json) ?: return false
        stateField.set(SpamMLScorer, parsed)
        return true
    }

    private fun resetToDefaults() {
        val defaultMethod = SpamMLScorer::class.java.getDeclaredMethod("defaultModelState")
        defaultMethod.isAccessible = true
        stateField.set(SpamMLScorer, defaultMethod.invoke(SpamMLScorer))
    }

    private fun snapshotState(): Any = stateField.get(SpamMLScorer)

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
        // Features 16-20 include plus_one_prefix which differs by leading "+1";
        // the non-prefixed "12125551234" does NOT trip plus_one_prefix, so
        // compare the parts that depend purely on the 10-digit normalization.
        assertArrayEquals(f10.copyOfRange(0, 15), f11.copyOfRange(0, 15), 0.0001)
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
        assertEquals(8.0 / 9.0, f[SEQ_ASC_RATIO], 0.001)
    }

    @Test
    fun `sequential descending ratio for 9876543210`() {
        val f = features("9876543210")
        assertEquals(1.0, f[SEQ_DESC_RATIO], 0.001)
    }

    @Test
    fun `sequential ratio zero for random number`() {
        val f = features("2125839746")
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
    fun `low entropy set for 2 distinct digits`() {
        assertEquals(1.0, features("1112221112")[LOW_ENTROPY], 0.0)
    }

    @Test
    fun `low entropy NOT set for diverse number`() {
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
        val s = SpamMLScorer.score("5555555555")
        assertTrue("All-same number should score high, got $s", s > 0.5)
    }

    @Test
    fun `isSpam returns true when score exceeds 0_7 threshold`() {
        assertTrue(SpamMLScorer.isSpam("5555555555"))
    }

    @Test
    fun `confidence returns 0-100 integer`() {
        val c = SpamMLScorer.confidence("2125551234")
        assertTrue(c in 0..100)
    }

    @Test
    fun `verdict is consistent with score isSpam and confidence`() {
        val number = "5555555555"
        val v = SpamMLScorer.verdict(number)
        assertTrue(v.score in 0.0..1.0)
        assertEquals(SpamMLScorer.isSpam(number), v.isSpam)
        assertEquals(SpamMLScorer.confidence(number), v.confidence)
    }

    // ── parseModel ───────────────────────────────────────────────────────
    //
    // parseModel is pure: it builds a ModelState from JSON without mutating
    // the live scorer. These tests cover the parser through reflection and
    // then commit the result to exercise the full scoring path.

    @Test
    fun `parseModel with valid v2 JSON yields a committable model`() {
        val json = """
        {
            "version": 2,
            "weights": [0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.3, 1.4, 1.5],
            "bias": -1.5,
            "threshold": 0.65
        }
        """.trimIndent()
        assertTrue(applyParsed(json))

        val s = SpamMLScorer.score("2125551234")
        assertTrue(s in 0.0..1.0)
    }

    @Test
    fun `parseModel with malformed JSON returns null`() {
        assertNull(parseOrNull("not json at all {{{"))
    }

    @Test
    fun `parseModel with empty JSON returns null`() {
        assertNull(parseOrNull("{}"))
    }

    @Test
    fun `parseModel with too few weights returns null`() {
        val json = """{ "weights": [0.1, 0.2, 0.3], "bias": -1.0 }"""
        assertNull(parseOrNull(json))
    }

    @Test
    fun `parseModel with missing bias yields a model using default bias`() {
        val json = """
        {
            "weights": [0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.3, 1.4, 1.5]
        }
        """.trimIndent()
        assertTrue(applyParsed(json))
        val s = SpamMLScorer.score("2125551234")
        assertTrue(s in 0.0..1.0)
    }

    @Test
    fun `loadWeights-style failure keeps prior model intact`() {
        // Apply a known-good model, then attempt to parse garbage. Nothing
        // should mutate in the failure path — the whole point of the
        // parseModel/commit split is that a bad payload can't regress to
        // defaults while a working model is in place.
        val validJson = """
        {
            "version": 2,
            "weights": [0.9, 0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.1, 0.9, 0.8, 0.7, 0.6, 0.5, 0.4],
            "bias": -1.2,
            "threshold": 0.61
        }
        """.trimIndent()
        assertTrue(applyParsed(validJson))
        val before = snapshotState()

        val bad = parseOrNull("not json at all {{{")
        assertNull(bad)

        // No commit happened, so state is still the one we just applied.
        assertSame(before, snapshotState())
    }
}
