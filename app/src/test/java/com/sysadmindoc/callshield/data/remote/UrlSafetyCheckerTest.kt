package com.sysadmindoc.callshield.data.remote

import com.sysadmindoc.callshield.data.SmsContentAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
    fun `remote lookup strips path query and fragment`() {
        val normalized =
            UrlSafetyChecker.normalizeRemoteLookupUrl(
                "https://bad.test/pay?recipient=5551234&token=secret#frag",
                registrableDomain = { "bad.test" },
            )

        assertEquals("https://bad.test/", normalized)
    }

    @Test
    fun `remote lookup sends only the registrable domain`() {
        val normalized =
            UrlSafetyChecker.normalizeRemoteLookupUrl(
                "https://login.accounts.example.co.uk/pay?recipient=5551234&token=secret#frag",
                registrableDomain = { "example.co.uk" },
            )

        assertEquals("https://example.co.uk/", normalized)
    }

    @Test
    fun `remote lookup strips embedded credentials as well as message URL data`() {
        val normalized =
            UrlSafetyChecker.normalizeRemoteLookupUrl(
                "https://alice:secret@login.example.test/pay?token=secret#frag",
                registrableDomain = { "example.test" },
            )

        assertEquals("https://example.test/", normalized)
    }

    @Test
    fun `remote lookup fails closed when registrable domain cannot be resolved`() {
        val normalized =
            UrlSafetyChecker.normalizeRemoteLookupUrl(
                "https://one-time-token.unknown/path",
                registrableDomain = { null },
            )

        assertEquals("", normalized)
    }

    @Test
    fun `remote lookup is disabled by default for unknown domains`() =
        runBlocking {
            val results = UrlSafetyChecker.checkSmsBody("Sign in at https://accounts.example.com/magic?token=secret")

            assertTrue(results.isEmpty())
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
            assertEquals(UrlThreatSource.LOCAL_SPAM_DOMAINS, malicious.single().source)
            assertEquals(UrlThreatVerdict.MALICIOUS, malicious.single().verdict)
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
