package com.sysadmindoc.callshield.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlSafetyCheckerTest {

    @Test
    fun `extractCandidateUrls normalizes duplicates and strips trailing punctuation`() {
        val urls = UrlSafetyChecker.extractCandidateUrls(
            """
            Visit www.evil.test, then https://evil.test/path!.
            Duplicate: www.evil.test
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "https://www.evil.test",
                "https://evil.test/path"
            ),
            urls
        )
    }

    @Test
    fun `extractCandidateUrls limits results after dedupe`() {
        val urls = UrlSafetyChecker.extractCandidateUrls(
            """
            https://one.test
            https://two.test
            https://three.test
            https://four.test
            https://five.test
            https://six.test
            https://one.test
            """.trimIndent(),
            limit = 5
        )

        assertEquals(
            listOf(
                "https://one.test",
                "https://two.test",
                "https://three.test",
                "https://four.test",
                "https://five.test"
            ),
            urls
        )
    }

    @Test
    fun `normalizeCandidateUrl preserves http scheme and trims punctuation`() {
        val normalized = UrlSafetyChecker.normalizeCandidateUrl("http://safe.test/path?),")

        assertEquals("http://safe.test/path", normalized)
    }
}
