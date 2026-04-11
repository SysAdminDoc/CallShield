package com.sysadmindoc.callshield.data

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/**
 * Fuzz tests for SpamMLScorer's JSON parsing — parseAndApply, parseGbtTrees,
 * and findMatchingBracket.
 *
 * Every test feeds adversarial JSON through the private parseAndApply method
 * via reflection and asserts that NO exception escapes — the code must fall
 * back to default weights gracefully.
 */
class JsonParsingFuzzTest {

    private val parseAndApplyMethod by lazy {
        SpamMLScorer::class.java.getDeclaredMethod("parseAndApply", String::class.java).also {
            it.isAccessible = true
        }
    }

    private val parseGbtTreesMethod by lazy {
        SpamMLScorer::class.java.getDeclaredMethod("parseGbtTrees", String::class.java).also {
            it.isAccessible = true
        }
    }

    private val findMatchingBracketMethod by lazy {
        SpamMLScorer::class.java.getDeclaredMethod(
            "findMatchingBracket", String::class.java,
            Int::class.java, Char::class.java, Char::class.java
        ).also {
            it.isAccessible = true
        }
    }

    private val applyDefaultWeightsMethod by lazy {
        SpamMLScorer::class.java.getDeclaredMethod("applyDefaultWeights").also {
            it.isAccessible = true
        }
    }

    /** Reset scorer to known state before each test. */
    @Before
    fun resetScorer() {
        applyDefaultWeightsMethod.invoke(SpamMLScorer)
    }

    /**
     * Invoke parseAndApply and assert no exception escapes.
     * After the call, verify score() still returns a sane value (not crashing).
     */
    private fun fuzzParseAndApply(json: String) {
        try {
            parseAndApplyMethod.invoke(SpamMLScorer, json)
        } catch (e: InvocationTargetException) {
            fail("parseAndApply threw ${e.targetException::class.simpleName} for input: ${json.take(120)}")
        }
        // Verify scorer is still functional after parsing adversarial input
        val score = SpamMLScorer.score("2125551234")
        assertTrue(
            "Score should be valid after parsing adversarial JSON, got $score",
            score in -1.0..1.0
        )
    }

    private fun fuzzParseGbtTrees(json: String) {
        try {
            parseGbtTreesMethod.invoke(SpamMLScorer, json)
        } catch (e: InvocationTargetException) {
            fail("parseGbtTrees threw ${e.targetException::class.simpleName} for input: ${json.take(120)}")
        }
    }

    private fun fuzzFindMatchingBracket(s: String, startIdx: Int, open: Char, close: Char): Int {
        return try {
            findMatchingBracketMethod.invoke(SpamMLScorer, s, startIdx, open, close) as Int
        } catch (e: InvocationTargetException) {
            fail("findMatchingBracket threw ${e.targetException::class.simpleName} for input: ${s.take(80)}")
            -1
        }
    }

    // ── Empty and null-like inputs ───────────────────────────────────

    @Test
    fun `parseAndApply empty string`() = fuzzParseAndApply("")

    @Test
    fun `parseAndApply whitespace only`() = fuzzParseAndApply("   \t\n  ")

    @Test
    fun `parseAndApply single character`() = fuzzParseAndApply("x")

    @Test
    fun `parseAndApply null literal`() = fuzzParseAndApply("null")

    @Test
    fun `parseAndApply undefined literal`() = fuzzParseAndApply("undefined")

    @Test
    fun `parseAndApply boolean true`() = fuzzParseAndApply("true")

    @Test
    fun `parseAndApply numeric literal`() = fuzzParseAndApply("42")

    // ── Valid JSON but wrong structure ────────────────────────────────

    @Test
    fun `parseAndApply empty object`() = fuzzParseAndApply("{}")

    @Test
    fun `parseAndApply empty array`() = fuzzParseAndApply("[]")

    @Test
    fun `parseAndApply version only`() = fuzzParseAndApply("""{"version": 3}""")

    @Test
    fun `parseAndApply version with empty trees`() =
        fuzzParseAndApply("""{"version": 3, "model_type": "gbt", "trees": []}""")

    @Test
    fun `parseAndApply version 2 with no weights`() =
        fuzzParseAndApply("""{"version": 2}""")

    @Test
    fun `parseAndApply version 2 with empty weights array`() =
        fuzzParseAndApply("""{"version": 2, "weights": [], "bias": 0.0}""")

    @Test
    fun `parseAndApply version 2 with too few weights`() =
        fuzzParseAndApply("""{"version": 2, "weights": [1.0, 2.0, 3.0], "bias": -1.0}""")

    @Test
    fun `parseAndApply version 2 with exactly 15 weights`() =
        fuzzParseAndApply(
            """{"version": 2, "weights": [1.0,0.8,1.8,1.5,0.6,2.1,1.4,0.9,2.0,1.2,1.8,0.5,1.0,1.5,0.8], "bias": -2.5}"""
        )

    @Test
    fun `parseAndApply version 2 with exactly 20 weights`() =
        fuzzParseAndApply(
            """{"version": 2, "weights": [1.0,0.8,1.8,1.5,0.6,2.1,1.4,0.9,2.0,1.2,1.8,0.5,1.0,1.5,0.8,0.3,0.3,0.6,1.0,0.1], "bias": -2.5}"""
        )

    // ── Version mismatches ───────────────────────────────────────────

    @Test
    fun `parseAndApply version 1`() =
        fuzzParseAndApply("""{"version": 1, "weights": [1.0], "bias": 0.0}""")

    @Test
    fun `parseAndApply version 99`() =
        fuzzParseAndApply("""{"version": 99, "model_type": "gbt", "trees": []}""")

    @Test
    fun `parseAndApply negative version`() =
        fuzzParseAndApply("""{"version": -1}""")

    @Test
    fun `parseAndApply version 0`() =
        fuzzParseAndApply("""{"version": 0}""")

    @Test
    fun `parseAndApply version as string`() =
        fuzzParseAndApply("""{"version": "three"}""")

    @Test
    fun `parseAndApply version very large`() =
        fuzzParseAndApply("""{"version": 999999999}""")

    // ── Deeply nested brackets ───────────────────────────────────────

    @Test
    fun `parseAndApply deeply nested open brackets`() =
        fuzzParseAndApply("[".repeat(100))

    @Test
    fun `parseAndApply deeply nested curly braces`() =
        fuzzParseAndApply("{".repeat(100))

    @Test
    fun `parseAndApply alternating brackets`() =
        fuzzParseAndApply("[{[{[{[{[{" + "}]}]}]}]}]")

    @Test
    fun `parseAndApply mismatched brackets`() =
        fuzzParseAndApply("""{"trees": [}]""")

    @Test
    fun `parseAndApply only closing brackets`() =
        fuzzParseAndApply("]]]]]]]]")

    @Test
    fun `parseAndApply 500 nested arrays`() =
        fuzzParseAndApply("[".repeat(500) + "]".repeat(500))

    // ── Malformed JSON ───────────────────────────────────────────────

    @Test
    fun `parseAndApply truncated JSON`() =
        fuzzParseAndApply("""{"version": 3, "model_type": "gbt", "trees": [{"fea""")

    @Test
    fun `parseAndApply missing closing brace`() =
        fuzzParseAndApply("""{"version": 3, "weights": [1.0, 2.0]""")

    @Test
    fun `parseAndApply missing closing bracket`() =
        fuzzParseAndApply("""{"version": 2, "weights": [1.0, 2.0}""")

    @Test
    fun `parseAndApply extra commas`() =
        fuzzParseAndApply("""{"version": 2,,, "weights": [1.0,,2.0,,], "bias": -1.0,}""")

    @Test
    fun `parseAndApply trailing garbage`() =
        fuzzParseAndApply("""{"version": 2, "weights": [1.0], "bias": 0.0} GARBAGE HERE""")

    @Test
    fun `parseAndApply leading garbage`() =
        fuzzParseAndApply("""GARBAGE {"version": 2}""")

    @Test
    fun `parseAndApply double colons`() =
        fuzzParseAndApply("""{"version":: 2}""")

    @Test
    fun `parseAndApply no quotes on keys`() =
        fuzzParseAndApply("""{version: 2, weights: [1.0]}""")

    @Test
    fun `parseAndApply single quotes`() =
        fuzzParseAndApply("""{'version': 2, 'weights': [1.0]}""")

    // ── Huge arrays ──────────────────────────────────────────────────

    @Test
    fun `parseAndApply huge weights array 1000 elements`() {
        val weights = (1..1000).joinToString(",") { "0.1" }
        fuzzParseAndApply("""{"version": 2, "weights": [$weights], "bias": -2.5}""")
    }

    @Test
    fun `parseAndApply huge trees array many empty objects`() {
        val trees = (1..100).joinToString(",") { "{}" }
        fuzzParseAndApply("""{"version": 3, "model_type": "gbt", "trees": [$trees]}""")
    }

    @Test
    fun `parseAndApply very long string value`() {
        val longStr = "a".repeat(10000)
        fuzzParseAndApply("""{"version": 2, "weights": ["$longStr"]}""")
    }

    // ── Special numeric values ───────────────────────────────────────

    @Test
    fun `parseAndApply NaN in weights`() =
        fuzzParseAndApply("""{"version": 2, "weights": [NaN, NaN, NaN], "bias": NaN}""")

    @Test
    fun `parseAndApply Infinity in weights`() =
        fuzzParseAndApply("""{"version": 2, "weights": [Infinity, -Infinity], "bias": Infinity}""")

    @Test
    fun `parseAndApply very large numbers`() =
        fuzzParseAndApply("""{"version": 2, "weights": [1e308, -1e308, 1e-308], "bias": 1e999}""")

    @Test
    fun `parseAndApply negative zero`() =
        fuzzParseAndApply("""{"version": 2, "weights": [-0.0, -0.0], "bias": -0.0}""")

    @Test
    fun `parseAndApply integer overflow in weights`() =
        fuzzParseAndApply("""{"version": 2, "weights": [99999999999999999999999999999999], "bias": 0}""")

    // ── Injection attempts in JSON values ────────────────────────────

    @Test
    fun `parseAndApply SQL injection in string value`() =
        fuzzParseAndApply("""{"version": 2, "model_type": "'; DROP TABLE models;--"}""")

    @Test
    fun `parseAndApply script injection in value`() =
        fuzzParseAndApply("""{"version": 2, "model_type": "<script>alert(1)</script>"}""")

    @Test
    fun `parseAndApply null byte in JSON string`() =
        fuzzParseAndApply("{\"version\": 2, \"model_type\": \"gbt\u0000evil\"}")

    @Test
    fun `parseAndApply escape sequences in values`() =
        fuzzParseAndApply("""{"version": 2, "model_type": "gbt\n\t\r\\\""}""")

    @Test
    fun `parseAndApply unicode escapes in values`() =
        fuzzParseAndApply("""{"version": 2, "model_type": "\u0067\u0062\u0074"}""")

    // ── GBT tree specific malformed inputs ───────────────────────────

    @Test
    fun `parseAndApply GBT with mismatched array lengths`() =
        fuzzParseAndApply(
            """{"version": 3, "model_type": "gbt", "learning_rate": 0.1, "trees": [{"feature": [0, -2], "threshold": [0.5], "children_left": [-1], "children_right": [-1], "value": [0.0]}]}"""
        )

    @Test
    fun `parseAndApply GBT with empty arrays in tree`() =
        fuzzParseAndApply(
            """{"version": 3, "model_type": "gbt", "trees": [{"feature": [], "threshold": [], "children_left": [], "children_right": [], "value": []}]}"""
        )

    @Test
    fun `parseAndApply GBT with missing tree fields`() =
        fuzzParseAndApply(
            """{"version": 3, "model_type": "gbt", "trees": [{"feature": [0]}]}"""
        )

    @Test
    fun `parseAndApply GBT with negative feature indices`() =
        fuzzParseAndApply(
            """{"version": 3, "model_type": "gbt", "learning_rate": 0.1, "trees": [{"feature": [-99, -2, -2], "threshold": [0.5, 0.0, 0.0], "children_left": [1, -1, -1], "children_right": [2, -1, -1], "value": [0.0, 0.5, -0.3]}]}"""
        )

    @Test
    fun `parseAndApply GBT with very large feature indices`() =
        fuzzParseAndApply(
            """{"version": 3, "model_type": "gbt", "learning_rate": 0.1, "trees": [{"feature": [9999, -2, -2], "threshold": [0.5, 0.0, 0.0], "children_left": [1, -1, -1], "children_right": [2, -1, -1], "value": [0.0, 0.5, -0.3]}]}"""
        )

    @Test
    fun `parseAndApply GBT with circular children references`() =
        fuzzParseAndApply(
            """{"version": 3, "model_type": "gbt", "learning_rate": 0.1, "trees": [{"feature": [0, 0, 0], "threshold": [0.5, 0.5, 0.5], "children_left": [1, 2, 0], "children_right": [2, 0, 1], "value": [0.0, 0.5, -0.3]}]}"""
        )

    @Test
    fun `parseAndApply GBT with string values in int array`() =
        fuzzParseAndApply(
            """{"version": 3, "model_type": "gbt", "trees": [{"feature": ["a", "b"], "threshold": [0.5, 0.5], "children_left": [1, -1], "children_right": [2, -1], "value": [0.0, 0.5]}]}"""
        )

    @Test
    fun `parseAndApply GBT valid single tree then scoring works`() {
        val json = """
        {
            "version": 3,
            "model_type": "gbt",
            "learning_rate": 0.1,
            "threshold": 0.7,
            "fallback_weights": {
                "toll_free": 1.2, "high_spam_npa": 0.8, "voip_range": 1.8,
                "repeated_digits_ratio": 1.5, "sequential_asc_ratio": 0.6,
                "all_same_digit": 2.1, "nxx_555": 1.4, "last4_zero": 0.9,
                "invalid_nxx": 2.0, "subscriber_all_same": 1.2,
                "alternating_pattern": 1.8, "sequential_desc_ratio": 0.5,
                "nxx_below_200": 1.0, "low_digit_entropy": 1.5,
                "subscriber_sequential": 0.8, "time_of_day_sin": 0.3,
                "time_of_day_cos": 0.3, "geographic_distance": 0.6,
                "short_number": 1.0, "plus_one_prefix": 0.1
            },
            "fallback_bias": -2.5,
            "trees": [
                {
                    "feature": [0, -2, -2],
                    "threshold": [0.5, 0.0, 0.0],
                    "children_left": [1, -1, -1],
                    "children_right": [2, -1, -1],
                    "value": [0.0, -0.3, 0.5]
                }
            ]
        }
        """.trimIndent()
        fuzzParseAndApply(json)
        // After loading a valid GBT model, scoring should still work
        val score = SpamMLScorer.score("2125551234")
        assertTrue("Score should be in [0, 1], got $score", score in 0.0..1.0)
    }

    // ── findMatchingBracket edge cases ───────────────────────────────

    @Test
    fun `findMatchingBracket empty string`() {
        val result = fuzzFindMatchingBracket("", 0, '[', ']')
        assertEquals(-1, result)
    }

    @Test
    fun `findMatchingBracket startIdx at end`() {
        val result = fuzzFindMatchingBracket("[", 0, '[', ']')
        assertEquals(-1, result)
    }

    @Test
    fun `findMatchingBracket simple matched`() {
        val result = fuzzFindMatchingBracket("[]", 0, '[', ']')
        assertEquals(1, result)
    }

    @Test
    fun `findMatchingBracket nested`() {
        val result = fuzzFindMatchingBracket("[[]]", 0, '[', ']')
        assertEquals(3, result)
    }

    @Test
    fun `findMatchingBracket with string containing brackets`() {
        val result = fuzzFindMatchingBracket("""["hello]world"]""", 0, '[', ']')
        assertEquals(14, result)
    }

    @Test
    fun `findMatchingBracket with escaped quote`() {
        val result = fuzzFindMatchingBracket("""["hello\"world"]""", 0, '[', ']')
        assertEquals(15, result)
    }

    @Test
    fun `findMatchingBracket unmatched returns -1`() {
        val result = fuzzFindMatchingBracket("[[[", 0, '[', ']')
        assertEquals(-1, result)
    }

    @Test
    fun `findMatchingBracket curly braces`() {
        val result = fuzzFindMatchingBracket("{{}}", 0, '{', '}')
        assertEquals(3, result)
    }

    // ── parseGbtTrees edge cases ─────────────────────────────────────

    @Test
    fun `parseGbtTrees no trees key`() = fuzzParseGbtTrees("""{"version": 3}""")

    @Test
    fun `parseGbtTrees trees not array`() =
        fuzzParseGbtTrees("""{"trees": "not_an_array"}""")

    @Test
    fun `parseGbtTrees trees with nested garbage`() =
        fuzzParseGbtTrees("""{"trees": [{"feature": [garbage]}]}""")

    @Test
    fun `parseGbtTrees empty string`() = fuzzParseGbtTrees("")

    @Test
    fun `parseGbtTrees trees key but no bracket after`() =
        fuzzParseGbtTrees("""{"trees" 42}""")

    // ── Batch adversarial JSON ───────────────────────────────────────

    @Test
    fun `batch fuzz parseAndApply with varied adversarial inputs`() {
        val inputs = listOf(
            // Edge case JSON
            "{}{}",
            "[]{}\n[]",
            """{"version":2,"version":3}""",   // duplicate keys
            """{"weights":[1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20],"bias":-2.5,"version":2}""", // reordered
            "{\"key\": \"value\u0000hidden\"}",
            """{"version": 2, "weights": [1.0e0, 2.0E1, .5, -.5, +1], "bias": -0}""",
            // Binary-ish
            "\u0001\u0002\u0003\u0004\u0005",
            // BOM
            "\uFEFF{\"version\": 2}",
            // Comment-like
            """/* comment */ {"version": 2}""",
            """// line comment\n{"version": 2}""",
            // JSONP-like
            """callback({"version": 2})""",
            // Control characters
            "\u0000\u0001\u001F",
            // Extremely long key
            """{"${"a".repeat(5000)}": 1}""",
            // JSON with only whitespace between tokens
            "{ \t\n\r \"version\" \t\n\r : \t\n\r 2 \t\n\r }",
        )
        for (input in inputs) {
            try {
                fuzzParseAndApply(input)
            } catch (e: Exception) {
                fail("Exception for input '${input.take(60)}': ${e::class.simpleName}: ${e.message}")
            }
        }
    }
}
