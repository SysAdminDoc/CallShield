package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.checker.BlockResult
import com.sysadmindoc.callshield.domain.model.SpamCheckResult
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
        val result =
            SpamCheckResult(
                isSpam = true,
                matchSource = "prefix",
                type = "wangiri_scam",
                description = "Wangiri callback scam",
            )
        // Type wins over matchSource description per the resolver contract.
        assertEquals(CallCategory.Wangiri, CallCategoryResolver.resolve(result))
    }

    @Test
    fun `sms_content always resolves to Phishing`() {
        val result =
            SpamCheckResult(
                isSpam = true,
                matchSource = "sms_content",
                description = "shortened url, urgent_language, callback_number",
                confidence = 75,
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
        val result =
            SpamCheckResult(
                isSpam = true,
                matchSource = "heuristic",
                description = "neighbor_spoof, voip_spam_range",
                confidence = 70,
            )
        assertEquals(CallCategory.Scam, CallCategoryResolver.resolve(result))
    }

    @Test
    fun `a live heuristic block resolves from its signals, not its description`() {
        // What HeuristicChecker writes now: labels in the app language plus
        // the raw tokens. The labels never carried the underscored tokens.
        fun live(
            type: String,
            description: String,
            vararg signals: String,
        ) = BlockResult
            .block("heuristic", type = type, description = description, confidence = 70, signals = signals.toList())
            .toSpamCheckResult()

        assertEquals(CallCategory.Scam, CallCategoryResolver.resolve(live("spoofed", "可能是邻近伪装", "neighbor_spoof")))
        assertEquals(CallCategory.Scam, CallCategoryResolver.resolve(live("spoofed", "Possible neighbor spoofing", "neighbor_spoof")))
        assertEquals(CallCategory.Robocall, CallCategoryResolver.resolve(live("suspicious", "正在进行的骚扰活动", "hot_campaign_range")))
        assertEquals(CallCategory.Telemarketer, CallCategoryResolver.resolve(live("suspicious", "Toll-free caller", "toll_free")))
        assertEquals(CallCategory.Unknown, CallCategoryResolver.resolve(live("suspicious", "号码格式异常", "invalid_format")))
    }

    @Test
    fun `a weak heuristic block names no category, so no category rule can wave it through`() {
        // Aggressive mode blocks a lone neighbor-spoof match at 50. With Scam
        // set to Allow, naming it Scam let the call ring.
        val weak =
            BlockResult
                .block("heuristic", type = "spoofed", description = "Possible neighbor spoofing", confidence = 50, signals = listOf("neighbor_spoof"))
                .toSpamCheckResult()

        assertEquals(CallCategory.Unknown, CallCategoryResolver.resolve(weak))
        assertEquals(CallCategory.Scam, CallCategoryResolver.resolve(weak.copy(confidence = 60)))
    }

    @Test
    fun `a weak heuristic block's type names no category either`() {
        // HeuristicChecker types its blocks from the same signals: a lone
        // VoIP-range match (30) or rapid-fire match (40) is typed "robocall",
        // and aggressive mode blocks both.
        fun heuristic(
            type: String,
            confidence: Int,
            vararg signals: String,
        ) = BlockResult
            .block("heuristic", type = type, description = "", confidence = confidence, signals = signals.toList())
            .toSpamCheckResult()

        assertEquals(CallCategory.Unknown, CallCategoryResolver.resolve(heuristic("robocall", 30, "voip_spam_range")))
        assertEquals(CallCategory.Unknown, CallCategoryResolver.resolve(heuristic("robocall", 40, "rapid_fire")))
        assertEquals(CallCategory.Robocall, CallCategoryResolver.resolve(heuristic("robocall", 70, "rapid_fire", "voip_spam_range")))
        // The same type from the database is authoritative at any confidence.
        assertEquals(
            CallCategory.Robocall,
            CallCategoryResolver.resolve(SpamCheckResult(isSpam = true, matchSource = "database", type = "robocall", confidence = 30)),
        )
    }

    @Test
    fun `heuristic with rapid_fire resolves to Robocall`() {
        val result =
            SpamCheckResult(
                isSpam = true,
                matchSource = "heuristic",
                description = "rapid_fire",
                confidence = 65,
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
    fun `a weak ml block names no category although its checker types it robocall`() {
        // What MlScorerChecker writes: every block is typed "robocall", and the
        // model blocks from about 65.
        fun ml(confidence: Int) =
            BlockResult
                .block("ml_scorer", type = "robocall", description = "ML model: $confidence% spam likelihood", confidence = confidence)
                .toSpamCheckResult()

        assertEquals(CallCategory.Unknown, CallCategoryResolver.resolve(ml(65)))
        assertEquals(CallCategory.Unknown, CallCategoryResolver.resolve(ml(79)))
        assertEquals(CallCategory.Robocall, CallCategoryResolver.resolve(ml(80)))
        // A logged ML row keeps the type, and the Blocked log labels it the same way.
        assertEquals(CallCategory.Unknown, CallCategoryResolver.resolveFromLog("ml_scorer", "robocall", "", 72))
        assertEquals(CallCategory.Robocall, CallCategoryResolver.resolveFromLog("ml_scorer", "robocall", "", 85))
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
