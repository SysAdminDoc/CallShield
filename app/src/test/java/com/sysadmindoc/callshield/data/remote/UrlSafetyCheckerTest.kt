package com.sysadmindoc.callshield.data.remote

import com.sysadmindoc.callshield.data.SmsContentAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class UrlSafetyCheckerTest {
    @After
    fun tearDown() {
        SmsContentAnalyzer.updateSpamDomains(emptyList())
    }

    @Test
    fun `extractCandidateUrls normalizes duplicates and strips trailing punctuation`() {
        val urls =
            UrlSafetyChecker.extractCandidateUrls(
                "Visit www.evil.test, then https://evil.test/path!.\n" +
                    "Duplicate: www.evil.test",
            )

        assertEquals(
            listOf(
                "https://www.evil.test",
                "https://evil.test/path",
            ),
            urls,
        )
    }

    @Test
    fun `extractCandidateUrls limits results after dedupe`() {
        val urls =
            UrlSafetyChecker.extractCandidateUrls(
                "https://one.test\n" +
                    "https://two.test\n" +
                    "https://three.test\n" +
                    "https://four.test\n" +
                    "https://five.test\n" +
                    "https://six.test\n" +
                    "https://one.test",
                limit = 5,
            )

        assertEquals(
            listOf(
                "https://one.test",
                "https://two.test",
                "https://three.test",
                "https://four.test",
                "https://five.test",
            ),
            urls,
        )
    }

    @Test
    fun `normalizeCandidateUrl preserves http scheme and trims punctuation`() {
        val normalized = UrlSafetyChecker.normalizeCandidateUrl("http://safe.test/path?),")

        assertEquals("http://safe.test/path", normalized)
    }

    @Test
    fun `normalizeRemoteLookupUrl strips query strings and fragments by default`() {
        val normalized =
            UrlSafetyChecker.normalizeRemoteLookupUrl("https://bad.test/pay?recipient=5551234&token=secret#frag")

        assertEquals("https://bad.test/pay", normalized)
    }

    @Test
    fun `normalizeRemoteLookupUrl keeps query when privacy mode is disabled but strips fragment`() {
        val normalized =
            UrlSafetyChecker.normalizeRemoteLookupUrl(
                "https://bad.test/pay?recipient=5551234&token=secret#frag",
                stripQuery = false,
            )

        assertEquals("https://bad.test/pay?recipient=5551234&token=secret", normalized)
    }

    @Test
    fun `local spam-domain URLs are flagged before remote lookup with sanitized URL`() =
        runBlocking {
            SmsContentAnalyzer.updateSpamDomains(listOf("evil.test"))

            val malicious =
                UrlSafetyChecker.checkSmsBody(
                    "Claim at https://login.evil.test/pay?recipient=5551234&token=secret#frag",
                )

            assertEquals(1, malicious.size)
            assertEquals("https://login.evil.test/pay", malicious.single().url)
            assertEquals("known_spam_domain", malicious.single().threat)
            assertEquals(listOf("local_spam_domain"), malicious.single().tags)
        }

    @Test
    fun `local spam-domain result honors query-preserving setting but never keeps fragments`() {
        SmsContentAnalyzer.updateSpamDomains(listOf("evil.test"))

        val result =
            UrlSafetyChecker.localSpamDomainResult(
                "https://login.evil.test/pay?recipient=5551234&token=secret#frag",
                stripQuery = false,
            )

        assertNotNull(result)
        assertEquals("https://login.evil.test/pay?recipient=5551234&token=secret", result?.url)
    }
}
