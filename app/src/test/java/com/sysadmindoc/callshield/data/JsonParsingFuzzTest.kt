package com.sysadmindoc.callshield.data

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Fuzz tests for SpamMLScorer's JSON parsing.
 *
 * Every input is fed through the private parser and we assert that NO
 * exception escapes — a corrupt weights file must never tear down the
 * scoring pipeline. We invoke parseModel (pure parser) and then score()
 * on the live instance to verify scoring still works after the hostile
 * payload is offered to the scorer.
 */
class JsonParsingFuzzTest {

    private val parseModel: Method by lazy {
        SpamMLScorer::class.java.getDeclaredMethod("parseModel", String::class.java).also {
            it.isAccessible = true
        }
    }

    private val parseGbtTreesList: Method by lazy {
        SpamMLScorer::class.java.getDeclaredMethod("parseGbtTreesList", String::class.java).also {
            it.isAccessible = true
        }
    }

    private val findMatchingBracket: Method by lazy {
        SpamMLScorer::class.java.getDeclaredMethod(
            "findMatchingBracket", String::class.java,
            Int::class.java, Char::class.java, Char::class.java
        ).also {
            it.isAccessible = true
        }
    }

    private val defaultModelState: Method by lazy {
        SpamMLScorer::class.java.getDeclaredMethod("defaultModelState").also {
            it.isAccessible = true
        }
    }

    private val stateField: java.lang.reflect.Field by lazy {
        SpamMLScorer::class.java.getDeclaredField("state").also {
            it.isAccessible = true
        }
    }

    /** Reset scorer to known defaults before each test. */
    @Before
    fun resetScorer() {
        stateField.set(SpamMLScorer, defaultModelState.invoke(SpamMLScorer))
    }

    /**
     * Feed the input to parseModel and assert nothing escapes. If the
     * parser produces a state, commit it so score() exercises the freshly
     * parsed path; otherwise confirm score() still works on the default
     * model that the @Before step installed.
     */
    private fun fuzzParse(json: String) {
        val parsed = try {
            parseModel.invoke(SpamMLScorer, json)
        } catch (e: InvocationTargetException) {
            fail("parseModel threw ${e.targetException::class.simpleName} for input: ${json.take(120)}")
            return
        }
        if (parsed != null) {
            stateField.set(SpamMLScorer, parsed)
        }
        val score = SpamMLScorer.score("2125551234")
        assertTrue(
            "Score should be valid after parsing adversarial JSON, got $score",
            score in -1.0..1.0
        )
    }

    private fun fuzzParseGbtTrees(json: String) {
        try {
            parseGbtTreesList.invoke(SpamMLScorer, json)
        } catch (e: InvocationTargetException) {
            fail("parseGbtTreesList threw ${e.targetException::class.simpleName} for input: ${json.take(120)}")
        }
    }

    private fun fuzzFindMatchingBracket(s: String, startIdx: Int, open: Char, close: Char): Int {
        return try {
            findMatchingBracket.invoke(SpamMLScorer, s, startIdx, open, close) as Int
        } catch (e: InvocationTargetException) {
            fail("findMatchingBracket threw ${e.targetException::class.simpleName} for input: ${s.take(80)}")
            -1
        }
    }

    // ── Empty and null-like inputs ───────────────────────────────────

    @Test
    fun `parse empty string`() = fuzzParse("")

    @Test
    fun `parse whitespace only`() = fuzzParse("   \t\n  ")

    @Test
    fun `parse single character`() = fuzzParse("x")

    @Test
    fun `parse null literal`() = fuzzParse("null")

    @Test
    fun `parse undefined literal`() = fuzzParse("undefined")

    @Test
    fun `parse boolean true`() = fuzzParse("true")

    @Test
    fun `parse numeric literal`() = fuzzParse("42")

    // ── Valid JSON but wrong structure ────────────────────────────────

    @Test
    fun `parse empty object`() = fuzzParse("{}")

    @Test
    fun `parse empty array`() = fuzzParse("[]")

    @Test
    fun `parse version only`() = fuzzParse("""{"version": 3}""")

    @Test
    fun `parse version with empty trees`() =
        fuzzParse("""{"version": 3, "model_type": "gbt", "trees": []}""")

    @Test
    fun `parse version 2 with no weights`() =
        fuzzParse("""{"version": 2}""")

    @Test
    fun `parse version 2 with empty weights array`() =
        fuzzParse("""{"version": 2, "weights": [], "bias": 0.0}""")

    @Test
    fun `parse version 2 with too few weights`() =
        fuzzParse("""{"version": 2, "weights": [1.0, 2.0, 3.0], "bias": -1.0}""")

    @Test
    fun `parse version 2 with exactly 15 weights`() =
        fuzzParse(
            """{"version": 2, "weights": [1.0,0.8,1.8,1.5,0.6,2.1,1.4,0.9,2.0,1.2,1.8,0.5,1.0,1.5,0.8], "bias": -2.5}"""
        )

    @Test
    fun `parse version 2 with exactly 20 weights`() =
        fuzzParse(
            """{"version": 2, "weights": [1.0,0.8,1.8,1.5,0.6,2.1,1.4,0.9,2.0,1.2,1.8,0.5,1.0,1.5,0.8,0.3,0.3,0.6,1.0,0.1], "bias": -2.5}"""
        )

    // ── Version mismatches ───────────────────────────────────────────

    @Test
    fun `parse version 1`() =
        fuzzParse("""{"version": 1, "weights": [1.0], "bias": 0.0}""")

    @Test
    fun `parse version 99`() =
        fuzzParse("""{"version": 99, "model_type": "gbt", "trees": []}""")

    @Test
    fun `parse negative version`() =
        fuzzParse("""{"version": -1}""")

    @Test
    fun `parse version 0`() =
        fuzzParse("""{"version": 0}""")

    @Test
    fun `parse version as string`() =
        fuzzParse("""{"version": "three"}""")

    @Test
    fun `parse version very large`() =
        fuzzParse("""{"version": 999999999}""")

    // ── Deeply nested brackets ───────────────────────────────────────

    @Test
    fun `parse deeply nested open brackets`() =
        fuzzParse("[".repeat(100))

    @Test
    fun `parse deeply nested curly braces`() =
        fuzzParse("{".repeat(100))

    @Test
    fun `parse alternating brackets`() =
        fuzzParse("[{[{[{[{[{" + "}]}]}]}]}]")

    @Test
    fun `parse mismatched brackets`() =
        fuzzParse("""{"trees": [}]""")

    @Test
    fun `parse only closing brackets`() =
        fuzzParse("]]]]]]]]")

    @Test
    fun `parse 500 nested arrays`() =
        fuzzParse("[".repeat(500) + "]".repeat(500))

    // ── Malformed JSON ───────────────────────────────────────────────

    @Test
    fun `parse truncated JSON`() =
        fuzzParse("""{"version": 3, "model_type": "gbt", "trees": [{"fea""")

    @Test
    fun `parse missing closing brace`() =
        fuzzParse("""{"version": 3, "weights": [1.0, 2.0]""")

    @Test
    fun `parse missing closing bracket`() =
        fuzzParse("""{"version": 2, "weights": [1.0, 2.0}""")

    @Test
    fun `parse extra commas`() =
        fuzzParse("""{"version": 2,,, "weights": [1.0,,2.0,,], "bias": -1.0,}""")

    @Test
    fun `parse trailing garbage`() =
        fuzzParse("""{"version": 2, "weights": [1.0], "bias": 0.0} GARBAGE HERE""")

    @Test
    fun `parse leading garbage`() =
        fuzzParse("""GARBAGE {"version": 2}""")

    @Test
    fun `parse double colons`() =
        fuzzParse("""{"version":: 2}""")

    @Test
    fun `parse no quotes on keys`() =
        fuzzParse("""{version: 2, weights: [1.0]}""")

    @Test
    fun `parse single quotes`() =
        fuzzParse("""{'version': 2, 'weights': [1.0]}""")

    // ── Huge arrays ──────────────────────────────────────────────────

    @Test
    fun `parse huge weights array 1000 elements`() {
        val weights = (1..1000).joinToString(",") { "0.1" }
        fuzzParse("""{"version": 2, "weights": [$weights], "bias": -2.5}""")
    }

    @Test
    fun `parse huge trees array many empty objects`() {
        val trees = (1..100).joinToString(",") { "{}" }
        fuzzParse("""{"version": 3, "model_type": "gbt", "trees": [$trees]}""")
    }

    @Test
    fun `parse very long string value`() {
        val longStr = "a".repeat(10000)
        fuzzParse("""{"version": 2, "weights": ["$longStr"]}""")
    }

    // ── Special numeric values ───────────────────────────────────────

    @Test
    fun `parse NaN in weights`() =
        fuzzParse("""{"version": 2, "weights": [NaN, NaN, NaN], "bias": NaN}""")

    @Test
    fun `parse Infinity in weights`() =
        fuzzParse("""{"version": 2, "weights": [Infinity, -Infinity], "bias": Infinity}""")

    @Test
    fun `parse very large numbers`() =
        fuzzParse("""{"version": 2, "weights": [1e308, -1e308, 1e-308], "bias": 1e999}""")

    @Test
    fun `parse negative zero`() =
        fuzzParse("""{"version": 2, "weights": [-0.0, -0.0], "bias": -0.0}""")

    @Test
    fun `parse integer overflow in weights`() =
        fuzzParse("""{"version": 2, "weights": [99999999999999999999999999999999], "bias": 0}""")

    // ── Injection attempts in JSON values ────────────────────────────

    @Test
    fun `parse SQL injection in string value`() =
        fuzzParse("""{"version": 2, "model_type": "'; DROP TABLE models;--"}""")

    @Test
    fun `parse script injection in value`() =
        fuzzParse("""{"version": 2, "model_type": "<script>alert(1)</script>"}""")

    @Test
    fun `parse null byte in JSON string`() =
        fuzzParse("{\"version\": 2, \"model_type\": \"gbt\u0000evil\"}")

    @Test
    fun `parse escape sequences in values`() =
        fuzzParse("""{"version": 2, "model_type": "gbt\n\t\r\\\""}""")

    @Test
    fun `parse unicode escapes in values`() =
        fuzzParse("""{"version": 2, "model_type": "\u0067\u0062\u0074"}""")

    // ── GBT tree specific malformed inputs ───────────────────────────

    @Test
    fun `parse GBT with mismatched array lengths`() =
        fuzzParse(
            """{"version": 3, "model_type": "gbt", "learning_rate": 0.1, "trees": [{"feature": [0, -2], "threshold": [0.5], "children_left": [-1], "children_right": [-1], "value": [0.0]}]}"""
        )

    @Test
    fun `parse GBT with empty arrays in tree`() =
        fuzzParse(
            """{"version": 3, "model_type": "gbt", "trees": [{"feature": [], "threshold": [], "children_left": [], "children_right": [], "value": []}]}"""
        )

    @Test
    fun `parse GBT with missing tree fields`() =
        fuzzParse(
            """{"version": 3, "model_type": "gbt", "trees": [{"feature": [0]}]}"""
        )

    @Test
    fun `parse GBT with negative feature indices`() =
        fuzzParse(
            """{"version": 3, "model_type": "gbt", "learning_rate": 0.1, "trees": [{"feature": [-99, -2, -2], "threshold": [0.5, 0.0, 0.0], "children_left": [1, -1, -1], "children_right": [2, -1, -1], "value": [0.0, 0.5, -0.3]}]}"""
        )

    @Test
    fun `parse GBT with very large feature indices`() =
        fuzzParse(
            """{"version": 3, "model_type": "gbt", "learning_rate": 0.1, "trees": [{"feature": [9999, -2, -2], "threshold": [0.5, 0.0, 0.0], "children_left": [1, -1, -1], "children_right": [2, -1, -1], "value": [0.0, 0.5, -0.3]}]}"""
        )

    @Test
    fun `parse GBT with circular children references`() =
        fuzzParse(
            """{"version": 3, "model_type": "gbt", "learning_rate": 0.1, "trees": [{"feature": [0, 0, 0], "threshold": [0.5, 0.5, 0.5], "children_left": [1, 2, 0], "children_right": [2, 0, 1], "value": [0.0, 0.5, -0.3]}]}"""
        )

    @Test
    fun `parse GBT with string values in int array`() =
        fuzzParse(
            """{"version": 3, "model_type": "gbt", "trees": [{"feature": ["a", "b"], "threshold": [0.5, 0.5], "children_left": [1, -1], "children_right": [2, -1], "value": [0.0, 0.5]}]}"""
        )

    @Test
    fun `parse GBT valid single tree then scoring works`() {
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
        fuzzParse(json)
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
    fun `batch fuzz parse with varied adversarial inputs`() {
        val inputs = listOf(
            "{}{}",
            "[]{}\n[]",
            """{"version":2,"version":3}""",
            """{"weights":[1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20],"bias":-2.5,"version":2}""",
            "{\"key\": \"value\u0000hidden\"}",
            """{"version": 2, "weights": [1.0e0, 2.0E1, .5, -.5, +1], "bias": -0}""",
            "\u0001\u0002\u0003\u0004\u0005",
            "\uFEFF{\"version\": 2}",
            """/* comment */ {"version": 2}""",
            """// line comment\n{"version": 2}""",
            """callback({"version": 2})""",
            "\u0000\u0001\u001F",
            """{"${"a".repeat(5000)}": 1}""",
            "{ \t\n\r \"version\" \t\n\r : \t\n\r 2 \t\n\r }",
        )
        for (input in inputs) {
            try {
                fuzzParse(input)
            } catch (e: Exception) {
                fail("Exception for input '${input.take(60)}': ${e::class.simpleName}: ${e.message}")
            }
        }
    }
}
