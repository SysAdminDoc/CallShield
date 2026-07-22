package com.sysadmindoc.callshield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the v3 `fallback_weights` guard. The old check was
 * `parsedWeights.size >= 15`, but the array is always exactly 20 entries
 * (`featureNames.map { pairs[it] ?: 0.0 }`), so a malformed block whose keys
 * didn't parse silently produced an all-zero logistic-regression fallback
 * (constant output, never spam). The guard now counts *present* named weights.
 */
class SpamMLScorerFallbackTest {
    private val featureNames =
        listOf(
            "toll_free",
            "high_spam_npa",
            "voip_range",
            "repeated_digits_ratio",
            "sequential_asc_ratio",
            "all_same_digit",
            "nxx_555",
            "last4_zero",
            "invalid_nxx",
            "subscriber_all_same",
            "alternating_pattern",
            "sequential_desc_ratio",
            "nxx_below_200",
            "low_digit_entropy",
            "subscriber_sequential",
            "time_of_day_sin",
            "time_of_day_cos",
            "geographic_distance",
            "short_number",
            "plus_one_prefix",
        )

    private fun jsonWithFallback(entries: List<Pair<String, Double>>): String {
        val body = entries.joinToString(", ") { "\"${it.first}\": ${it.second}" }
        return """{"version": 3, "model_type": "gbt", "fallback_weights": {$body}, "fallback_bias": -1.5}"""
    }

    @Test
    fun `well-formed fallback block is accepted`() {
        val json = jsonWithFallback(featureNames.map { it to 0.5 })
        val result = SpamMLScorer().parseFallbackWeights(json, version = 3)
        assertNotNull(result)
        assertEquals(20, result!!.first.size)
        assertEquals(-1.5, result.second, 1e-9)
    }

    @Test
    fun `malformed fallback block with no valid named weights is rejected`() {
        // Keys don't match any feature name and there is no v2 weights array,
        // so parsing must fail (null) rather than yield an all-zero LR.
        val json = """{"version": 3, "model_type": "gbt", "fallback_weights": {"foo": 1.0, "bar": 2.0}, "fallback_bias": -1.5}"""
        assertNull(SpamMLScorer().parseFallbackWeights(json, version = 3))
    }

    @Test
    fun `fallback block below the minimum named-weight count is rejected`() {
        val tooFew = featureNames.take(SpamMLScorer.MIN_FALLBACK_NAMED_WEIGHTS - 1).map { it to 0.5 }
        val json = jsonWithFallback(tooFew)
        assertNull(SpamMLScorer().parseFallbackWeights(json, version = 3))
    }

    @Test
    fun `fallback block at the minimum named-weight count is accepted`() {
        val justEnough = featureNames.take(SpamMLScorer.MIN_FALLBACK_NAMED_WEIGHTS).map { it to 0.5 }
        val json = jsonWithFallback(justEnough)
        assertNotNull(SpamMLScorer().parseFallbackWeights(json, version = 3))
    }
}
