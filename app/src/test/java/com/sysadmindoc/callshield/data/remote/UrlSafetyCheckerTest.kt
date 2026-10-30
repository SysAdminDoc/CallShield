package com.sysadmindoc.callshield.data.remote

import com.sysadmindoc.callshield.data.SmsContentAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class UrlSafetyCheckerTest {
    @After
    fun tearDown() {
        SmsContentAnalyzer.updateSpamDomains(emptyList())
    }

    @Test
    fun `an on-device-only check keeps only the feeds matched on the phone`() {
        val all = UrlThreatAdapterCatalog.all()

        assertEquals(listOf(UrlThreatSource.OPENPHISH), UrlSafetyChecker.adaptersFor(all, onDeviceOnly = true).map { it.source })
        assertEquals(all, UrlSafetyChecker.adaptersFor(all, onDeviceOnly = false))
    }

    @Test
    fun `a message check sends a link to a remote feed only when it isn't on-device-only`() =
        runBlocking {
            val remote = RecordingAdapter(UrlThreatSource.PHISHTANK, matchesOnDevice = false)
            val onDevice = RecordingAdapter(UrlThreatSource.OPENPHISH, matchesOnDevice = true)
            val realAdapters = UrlSafetyChecker.enabledAdapters
            UrlSafetyChecker.enabledAdapters = listOf(remote, onDevice)
            try {
                // IP links: a domain's remote form comes from the public suffix list, which unit tests don't have.
                UrlSafetyChecker.checkSmsBody("Notes: https://198.51.100.7/today", allowRemoteLookup = true, onDeviceOnly = true)
                assertEquals(emptyList<String>(), remote.lookups)
                assertEquals(1, onDevice.lookups.size)

                // Another address, so neither feed answers from the cache.
                UrlSafetyChecker.checkSmsBody("Notes: https://203.0.113.9/today", allowRemoteLookup = true, onDeviceOnly = false)
                assertEquals(1, remote.lookups.size)
                assertEquals(2, onDevice.lookups.size)
            } finally {
                UrlSafetyChecker.enabledAdapters = realAdapters
            }
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
    fun `an on-device list gets the full host and path and nothing else`() {
        // OpenPhish is matched on the phone, so it can see the subdomain its feed
        // entries name and the bucket on a shared host. The query, fragment and
        // credentials still go.
        assertEquals(
            "https://login.example.test/pay",
            UrlSafetyChecker.normalizeOnDeviceLookupUrl("https://alice:secret@login.example.test/pay?token=secret#frag"),
        )
        assertEquals("", UrlSafetyChecker.normalizeOnDeviceLookupUrl("not a link"))
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
    fun `a spam domain split by an invisible character is still flagged`() =
        runBlocking {
            SmsContentAnalyzer.updateSpamDomains(listOf("scam.xyz"))

            val malicious = UrlSafetyChecker.checkSmsBody("Pay now https://sc​am.xyz/x")

            assertEquals(listOf("https://scam.xyz/x"), malicious.map { it.url })
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

    /** A feed that finds nothing and keeps every link it was asked about. */
    private class RecordingAdapter(
        override val source: UrlThreatSource,
        override val matchesOnDevice: Boolean,
    ) : UrlThreatAdapter {
        override val sourceVersion = "recording"
        override val isConfigured = true
        val lookups = CopyOnWriteArrayList<String>()

        override suspend fun lookup(
            canonicalUrl: String,
            nowMillis: Long,
        ): UrlThreatResult {
            lookups += canonicalUrl
            return UrlThreatResult.unknown(source, sourceVersion, canonicalUrl, nowMillis)
        }
    }
}
