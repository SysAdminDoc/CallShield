package com.sysadmindoc.callshield.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [DigestWorker.matchReasonBucket] — the pure mapping from a
 * checker `matchReason` to the coarse source bucket used in the daily digest.
 */
class DigestWorkerBucketTest {
    @Test
    fun `database and user blocklist reasons map to database`() {
        assertEquals("database", DigestWorker.matchReasonBucket("database_exact"))
        assertEquals("database", DigestWorker.matchReasonBucket("user_blocklist"))
    }

    @Test
    fun `heuristic reasons map to heuristic`() {
        assertEquals("heuristic", DigestWorker.matchReasonBucket("heuristic_neighbor_spoof"))
    }

    @Test
    fun `ml scorer reasons map to ML`() {
        assertEquals("ML", DigestWorker.matchReasonBucket("ml_scorer_gbt"))
    }

    @Test
    fun `sms content and keyword reasons map to content`() {
        assertEquals("content", DigestWorker.matchReasonBucket("sms_content_phishing"))
        assertEquals("content", DigestWorker.matchReasonBucket("keyword_loan"))
    }

    @Test
    fun `rcs reasons map to RCS filter`() {
        assertEquals("RCS filter", DigestWorker.matchReasonBucket("rcs_heuristic"))
    }

    @Test
    fun `category policy wrapped reasons bucket by the underlying source`() {
        assertEquals("database", DigestWorker.matchReasonBucket("category_policy:scam:silence:database_exact"))
        assertEquals("heuristic", DigestWorker.matchReasonBucket("category_policy:telemarketer:block:heuristic_x"))
        assertEquals("ML", DigestWorker.matchReasonBucket("category_policy:scam:block:ml_scorer_gbt"))
    }

    @Test
    fun `unknown reasons map to other`() {
        assertEquals("other", DigestWorker.matchReasonBucket("campaign_burst"))
        assertEquals("other", DigestWorker.matchReasonBucket(""))
    }

    @Test
    fun `grouping counts match the digest breakdown shape`() {
        val reasons =
            listOf(
                "database_exact",
                "user_blocklist",
                "heuristic_x",
                "ml_scorer_gbt",
                "ml_scorer_lr",
                "ml_scorer_lr",
            )
        val counts =
            reasons
                .groupingBy { DigestWorker.matchReasonBucket(it) }
                .eachCount()
        assertEquals(2, counts["database"])
        assertEquals(1, counts["heuristic"])
        assertEquals(3, counts["ML"])
    }
}
