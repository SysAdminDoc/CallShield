package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.remote.UrlSafetyChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PastedMessageVerdictTest {
    private val phishingLink = UrlSafetyChecker.UrlCheckResult(url = "https://evil.example", isMalicious = true)

    @Test
    fun `a text scored at the bar looks like spam, and one below it doesn't`() {
        assertTrue(PastedMessageVerdict(50, listOf("excessive_caps"), emptyList(), threshold = 50).looksLikeSpam)
        assertFalse(PastedMessageVerdict(49, listOf("excessive_caps"), emptyList(), threshold = 50).looksLikeSpam)
    }

    @Test
    fun `a known dangerous link is enough on its own`() {
        assertTrue(PastedMessageVerdict(0, emptyList(), listOf(phishingLink), threshold = 50).looksLikeSpam)
    }

    @Test
    fun `the content rules score it the way they score an incoming text`() {
        val body = "URGENT! Your account is LOCKED. Verify now at http://bit.ly/x1 or call 1-800-555-0199"
        val analysis = SmsContentAnalyzer.analyze(body)

        val verdict = PastedMessageVerdict.of(body, aggressive = false, dangerousLinks = emptyList())

        assertEquals(analysis.score, verdict.score)
        assertEquals(analysis.reasons, verdict.signals)
        assertEquals(PastedMessageVerdict.DEFAULT_THRESHOLD, verdict.threshold)
    }

    @Test
    fun `aggressive mode lowers the bar as it does for incoming texts`() {
        assertEquals(
            PastedMessageVerdict.AGGRESSIVE_THRESHOLD,
            PastedMessageVerdict.of("See you at six", aggressive = true, dangerousLinks = emptyList()).threshold,
        )
    }

    @Test
    fun `an ordinary text gets no signals`() {
        val verdict = PastedMessageVerdict.of("See you at six, I'll bring the salad", aggressive = false, dangerousLinks = emptyList())

        assertFalse(verdict.looksLikeSpam)
        assertEquals(emptyList<String>(), verdict.signals)
    }
}
