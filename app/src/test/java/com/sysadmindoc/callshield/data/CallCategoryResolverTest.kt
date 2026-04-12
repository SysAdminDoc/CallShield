package com.sysadmindoc.callshield.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CallCategoryResolverTest {

    @Test
    fun `not spam resolves to unknown`() {
        val result = SpamCheckResult(isSpam = false, matchSource = "manual_whitelist")
        assertEquals(CallCategory.Unknown, CallCategoryResolver.resolve(result))
    }

    @Test
    fun `database type debt_collector wins over matchSource`() {
        val result = SpamCheckResult(isSpam = true, matchSource = "database", type = "debt_collector")
        assertEquals(CallCategory.DebtCollector, CallCategoryResolver.resolve(result))
    }

    @Test
    fun `database type political`() {
        val result = SpamCheckResult(isSpam = true, matchSource = "database", type = "political")
        assertEquals(CallCategory.Political, CallCategoryResolver.resolve(result))
    }

    @Test
    fun `database type scam variants all resolve to Scam`() {
        listOf("scam", "premium_scam", "financial_scam", "tech_support_scam").forEach { type ->
            val result = SpamCheckResult(isSpam = true, matchSource = "database", type = type)
            assertEquals("type=$type", CallCategory.Scam, CallCategoryResolver.resolve(result))
        }
    }

    @Test
    fun `sms_spam database type reclassifies as Phishing`() {
        val result = SpamCheckResult(isSpam = true, matchSource = "database", type = "sms_spam")
        assertEquals(CallCategory.Phishing, CallCategoryResolver.resolve(result))
    }

    @Test
    fun `prefix rule with wangiri description resolves to Wangiri`() {
        val result = SpamCheckResult(
            isSpam = true, matchSource = "prefix",
            type = "wangiri_scam", description = "Wangiri callback scam",
        )
        // Type wins over matchSource description per the resolver contract.
        assertEquals(CallCategory.Wangiri, CallCategoryResolver.resolve(result))
    }

    @Test
    fun `sms_content always resolves to Phishing`() {
        val result = SpamCheckResult(
            isSpam = true, matchSource = "sms_content",
            description = "shortened url, urgent_language, callback_number", confidence = 75,
        )
        assertEquals(CallCategory.Phishing, CallCategoryResolver.resolve(result))
    }

    @Test
    fun `campaign_burst always resolves to Robocall`() {
        val result = SpamCheckResult(isSpam = true, matchSource = "campaign_burst", confidence = 75)
        assertEquals(CallCategory.Robocall, CallCategoryResolver.resolve(result))
    }

    @Test
    fun `heuristic with neighbor_spoof resolves to Scam`() {
        val result = SpamCheckResult(
            isSpam = true, matchSource = "heuristic",
            description = "neighbor_spoof, voip_spam_range", confidence = 70,
        )
        assertEquals(CallCategory.Scam, CallCategoryResolver.resolve(result))
    }

    @Test
    fun `heuristic with rapid_fire resolves to Robocall`() {
        val result = SpamCheckResult(
            isSpam = true, matchSource = "heuristic",
            description = "rapid_fire", confidence = 65,
        )
        assertEquals(CallCategory.Robocall, CallCategoryResolver.resolve(result))
    }

    @Test
    fun `ml scorer below 80pct confidence stays Unknown to avoid mislabeling`() {
        val result = SpamCheckResult(isSpam = true, matchSource = "ml_scorer", confidence = 72)
        // 72 is still a block (threshold 0.7 == 70%) but we don't commit
        // to a specific category without more evidence.
        assertEquals(CallCategory.Unknown, CallCategoryResolver.resolve(result))
    }

    @Test
    fun `ml scorer at or above 80pct resolves to Robocall`() {
        val result = SpamCheckResult(isSpam = true, matchSource = "ml_scorer", confidence = 85)
        assertEquals(CallCategory.Robocall, CallCategoryResolver.resolve(result))
    }

    @Test
    fun `rcs prefix matchSource resolves to Phishing`() {
        val result = SpamCheckResult(isSpam = true, matchSource = "rcs_database", type = "unknown")
        assertEquals(CallCategory.Phishing, CallCategoryResolver.resolve(result))
    }

    @Test
    fun `unknown evidence falls back to Unknown category`() {
        val result = SpamCheckResult(isSpam = true, matchSource = "frequency", confidence = 100)
        assertEquals(CallCategory.Unknown, CallCategoryResolver.resolve(result))
    }
}
