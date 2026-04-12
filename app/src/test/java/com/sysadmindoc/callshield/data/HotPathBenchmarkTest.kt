package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.model.WildcardRule
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM microbenchmarks for the spam detection hot paths.
 *
 * These are NOT the Jetpack Benchmark library — they're lightweight
 * nanosecond timers that run as normal unit tests. That trades some
 * accuracy for zero new build deps and ability to execute in the
 * existing CI. Each test establishes a **regression ceiling** rather
 * than asserting an exact timing: failures indicate performance
 * regressed dramatically (not that a fix slowed things down by 5%).
 *
 * Ceilings were set based on warm JIT timings on a modern laptop JVM,
 * with 20x headroom so CI noise doesn't cause spurious failures. If
 * a CI machine is pathologically slow the ceilings can be relaxed via
 * the `callshield.benchHeadroom` system property.
 */
class HotPathBenchmarkTest {

    private val headroom: Double =
        System.getProperty("callshield.benchHeadroom")?.toDoubleOrNull() ?: 1.0

    @Test
    fun wildcardRule_matches_10k_iterations_under_ceiling() {
        val rule = WildcardRule(pattern = "+1212*")
        val numbers = listOf(
            "+12125551234", "2125551234", "12125551234", "+13105551234",
            "+19175550001", "5165551234", "+18005551234", "+12025551234",
        )
        // Warm up JIT
        repeat(1_000) { rule.matches(numbers[it % numbers.size]) }

        val start = System.nanoTime()
        repeat(10_000) { rule.matches(numbers[it % numbers.size]) }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0

        // 10k wildcard matches (with 4-variant normalization) should complete
        // well under 500 ms with generous headroom. A regression past 500 ms
        // means either a pathological regex or a scanning loop.
        val ceilingMs = 500.0 * headroom
        assertTrue(
            "wildcard match 10k iters took ${elapsedMs}ms, ceiling ${ceilingMs}ms",
            elapsedMs < ceilingMs
        )
    }

    @Test
    fun campaignDetector_record_plus_check_1k_iterations_under_ceiling() {
        // Reset state so other tests don't skew the numbers
        clearCampaignDetectorState()
        // Warm up
        repeat(100) { CampaignDetector.recordCall("+12125550001") }

        val start = System.nanoTime()
        repeat(1_000) { i ->
            val digit = (i % 10).toString()
            CampaignDetector.recordCall("+1212555$digit${i % 100}")
            CampaignDetector.isActiveCampaign("+12125551234")
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0

        // 1k record+check cycles should be effectively instant. Anything
        // over 200 ms means the synchronized map or the expiration loop
        // has regressed to quadratic behavior.
        val ceilingMs = 200.0 * headroom
        assertTrue(
            "CampaignDetector 1k cycles took ${elapsedMs}ms, ceiling ${ceilingMs}ms",
            elapsedMs < ceilingMs
        )
        clearCampaignDetectorState()
    }

    @Test
    fun spamMLScorer_extractFeatures_plus_score_1k_iterations_under_ceiling() {
        val numbers = listOf(
            "+12125551234", "+18005551234", "+19005551234", "+15555555555",
            "+13105550000", "+16465551111", "+17185559999", "+14155551234",
        )
        // Warm up
        repeat(100) { SpamMLScorer.score(numbers[it % numbers.size]) }

        val start = System.nanoTime()
        repeat(1_000) { SpamMLScorer.score(numbers[it % numbers.size]) }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0

        // 1k scores (20 features + logistic regression / GBT inference)
        // should finish under 300 ms. GBT with 50 trees of depth 4 is the
        // worst case. Regression past 300 ms likely means the feature
        // extractor allocated per-call or the tree parser is being
        // re-invoked inside score().
        val ceilingMs = 300.0 * headroom
        assertTrue(
            "SpamMLScorer 1k score iters took ${elapsedMs}ms, ceiling ${ceilingMs}ms",
            elapsedMs < ceilingMs
        )
    }

    @Test
    fun spamHeuristics_pure_checks_1k_iterations_under_ceiling() {
        // Only hit the context-free heuristics so the test stays in pure JVM.
        val n = "+12125551234"
        repeat(100) {
            SpamHeuristics.isTollFree(n)
            SpamHeuristics.isHighSpamVoipRange(n)
            SpamHeuristics.isInvalidFormat(n)
        }
        val start = System.nanoTime()
        repeat(1_000) {
            SpamHeuristics.isTollFree(n)
            SpamHeuristics.isHighSpamVoipRange(n)
            SpamHeuristics.isInvalidFormat(n)
            SpamHeuristics.isHotCampaignRange(n)
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0

        val ceilingMs = 100.0 * headroom
        assertTrue(
            "SpamHeuristics 1k pure-check cycles took ${elapsedMs}ms, ceiling ${ceilingMs}ms",
            elapsedMs < ceilingMs
        )
    }

    /**
     * CampaignDetector is a singleton object; there's no public reset.
     * Use reflection to zero out the internal map so benchmark runs are
     * independent.
     */
    private fun clearCampaignDetectorState() {
        val field = CampaignDetector::class.java.getDeclaredField("recentPrefixes").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val map = field.get(CampaignDetector) as MutableMap<String, MutableList<Long>>
        map.clear()
    }
}
