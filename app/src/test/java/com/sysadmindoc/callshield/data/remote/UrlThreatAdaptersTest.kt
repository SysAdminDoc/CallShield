package com.sysadmindoc.callshield.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class UrlThreatAdaptersTest {
    private val canonicalUrl = "https://example.test/"

    @Test
    fun `cache expires entries and keeps source and version isolated`() {
        var nowMillis = 1_000L
        val cache = UrlThreatCache(maxEntries = 4, clock = { nowMillis })
        val urlhausResult =
            UrlThreatResult.malicious(
                source = UrlThreatSource.URLHAUS,
                sourceVersion = "v1",
                canonicalUrl = canonicalUrl,
                category = UrlThreatCategory.MALWARE,
                nowMillis = nowMillis,
            )
        val otherSourceResult =
            urlhausResult.copy(
                source = UrlThreatSource.PHISHTANK,
                sourceVersion = "v1",
                expiresAtMillis = urlhausResult.expiresAtMillis + 1L,
            )
        cache.put(urlhausResult)
        cache.put(otherSourceResult)

        assertEquals(urlhausResult, cache.get(UrlThreatSource.URLHAUS, "v1", canonicalUrl))
        assertEquals(otherSourceResult, cache.get(UrlThreatSource.PHISHTANK, "v1", canonicalUrl))
        assertNull(cache.get(UrlThreatSource.URLHAUS, "v2", canonicalUrl))

        nowMillis = urlhausResult.expiresAtMillis
        assertNull(cache.get(UrlThreatSource.URLHAUS, "v1", canonicalUrl))
        assertNotNull(cache.get(UrlThreatSource.PHISHTANK, "v1", canonicalUrl))
    }

    @Test
    fun `cache remains bounded with least recently used eviction`() {
        var nowMillis = 1_000L
        val cache = UrlThreatCache(maxEntries = 2, clock = { nowMillis })

        fun clean(
            source: UrlThreatSource,
            url: String,
        ) = UrlThreatResult.clean(
            source = source,
            sourceVersion = source.defaultVersion,
            canonicalUrl = url,
            nowMillis = nowMillis,
        )

        val first = clean(UrlThreatSource.URLHAUS, "https://one.test/")
        val second = clean(UrlThreatSource.PHISHTANK, "https://two.test/")
        val third = clean(UrlThreatSource.OPENPHISH, "https://three.test/")
        cache.put(first)
        cache.put(second)
        assertNotNull(cache.get(first.source, first.sourceVersion, first.canonicalUrl))
        cache.put(third)

        assertNotNull(cache.get(first.source, first.sourceVersion, first.canonicalUrl))
        assertNull(cache.get(second.source, second.sourceVersion, second.canonicalUrl))
        assertNotNull(cache.get(third.source, third.sourceVersion, third.canonicalUrl))
        assertEquals(2, cache.sizeForTests())
    }

    @Test
    fun `urlhaus parser distinguishes malware phishing clean and unknown`() {
        val malware =
            UrlhausThreatAdapter.parseUrlhausResponse(
                """{"query_status":"is_malware","threat":"malware","tags":["elf","exe"]}""",
                canonicalUrl,
                1_000L,
            )
        val phishing =
            UrlhausThreatAdapter.parseUrlhausResponse(
                """{"query_status":"ok","threat":"phishing","url_status":"online"}""",
                canonicalUrl,
                1_000L,
            )
        val clean =
            UrlhausThreatAdapter.parseUrlhausResponse(
                """{"query_status":"no_results"}""",
                canonicalUrl,
                1_000L,
            )
        val unknown =
            UrlhausThreatAdapter.parseUrlhausResponse(
                """{"query_status":"temporarily_unavailable"}""",
                canonicalUrl,
                1_000L,
            )

        assertEquals(UrlThreatCategory.MALWARE, malware.category)
        assertEquals(listOf("elf", "exe"), malware.tags)
        assertEquals(UrlThreatVerdict.MALICIOUS, malware.verdict)
        assertEquals(UrlThreatCategory.PHISHING, phishing.category)
        assertEquals(UrlThreatVerdict.CLEAN, clean.verdict)
        assertEquals(UrlThreatVerdict.UNKNOWN, unknown.verdict)
    }

    @Test
    fun `phishtank requires both verification and validity`() {
        val verified =
            PhishTankThreatAdapter.parsePhishTankResponse(
                """{"in_database":true,"verified":"yes","valid":"yes"}""",
                canonicalUrl,
                1_000L,
            )
        val unverified =
            PhishTankThreatAdapter.parsePhishTankResponse(
                """{"in_database":true,"verified":"no","valid":"yes"}""",
                canonicalUrl,
                1_000L,
            )
        val absent =
            PhishTankThreatAdapter.parsePhishTankResponse(
                """{"in_database":false}""",
                canonicalUrl,
                1_000L,
            )

        assertEquals(UrlThreatVerdict.MALICIOUS, verified.verdict)
        assertEquals(UrlThreatCategory.PHISHING, verified.category)
        assertEquals(UrlThreatVerdict.UNKNOWN, unverified.verdict)
        assertEquals(UrlThreatVerdict.CLEAN, absent.verdict)
    }

    @Test
    fun `safe browsing treats empty matches as clean and social engineering as phishing`() {
        val nowMillis = 1_000L
        val match =
            SafeBrowsingThreatAdapter.parseSafeBrowsingResponse(
                """{"matches":[{"threatType":"SOCIAL_ENGINEERING"}],"cacheDuration":"120s"}""",
                canonicalUrl,
                nowMillis,
            )
        val empty =
            SafeBrowsingThreatAdapter.parseSafeBrowsingResponse(
                """{"matches":[]}""",
                canonicalUrl,
                nowMillis,
            )

        assertEquals(UrlThreatVerdict.MALICIOUS, match.verdict)
        assertEquals(UrlThreatCategory.PHISHING, match.category)
        assertEquals(nowMillis + 120_000L, match.expiresAtMillis)
        assertEquals(UrlThreatVerdict.CLEAN, empty.verdict)
    }

    @Test
    fun `web risk parses threat types and expiry while empty response is clean`() {
        val nowMillis = Instant.parse("2026-08-10T12:00:00Z").toEpochMilli()
        val expiry = Instant.ofEpochMilli(nowMillis + 10 * 60 * 1_000L)
        val match =
            WebRiskThreatAdapter.parseWebRiskResponse(
                """{"threat":{"threatTypes":["SOCIAL_ENGINEERING"],"expireTime":"$expiry"}}""",
                canonicalUrl,
                nowMillis,
            )
        val empty = WebRiskThreatAdapter.parseWebRiskResponse("{}", canonicalUrl, nowMillis)

        assertEquals(UrlThreatVerdict.MALICIOUS, match.verdict)
        assertEquals(UrlThreatCategory.PHISHING, match.category)
        assertEquals(listOf("SOCIAL_ENGINEERING"), match.tags)
        assertEquals(UrlThreatVerdict.CLEAN, empty.verdict)
    }

    @Test
    fun `catalog keeps keyed sources disabled and routes commercial use to web risk`() {
        val defaultAdapters = UrlThreatAdapterCatalog.enabled()
        val commercialAdapters = UrlThreatAdapterCatalog.enabled(UrlThreatAdapterConfig(commercialUse = true))
        val defaultSources = defaultAdapters.map { it.source }
        val commercialSources = commercialAdapters.map { it.source }

        assertTrue(UrlThreatSource.PHISHTANK in defaultSources)
        assertTrue(UrlThreatSource.OPENPHISH in defaultSources)
        assertFalse(UrlThreatSource.URLHAUS in defaultSources)
        assertTrue(UrlThreatSource.SAFE_BROWSING !in commercialSources)
        assertTrue(UrlThreatSource.WEB_RISK !in commercialSources)

        val configuredCommercial =
            UrlThreatAdapterCatalog.enabled(
                UrlThreatAdapterConfig(
                    webRiskApiKey = "test-key",
                    commercialUse = true,
                ),
            )
        assertTrue(configuredCommercial.any { it.source == UrlThreatSource.WEB_RISK })
        assertFalse(configuredCommercial.any { it.source == UrlThreatSource.SAFE_BROWSING })
    }

    @Test
    fun `source manifest records privacy and access posture for every remote source`() {
        val entries = UrlThreatSourceManifest.entries.associateBy { it.source }

        assertEquals(5, entries.size)
        assertEquals("canonical_origin_only", entries.getValue(UrlThreatSource.URLHAUS).privacyMode)
        assertEquals("local_feed_match", entries.getValue(UrlThreatSource.OPENPHISH).privacyMode)
        assertTrue(entries.getValue(UrlThreatSource.WEB_RISK).accessMode.contains("google_cloud"))
    }
}
