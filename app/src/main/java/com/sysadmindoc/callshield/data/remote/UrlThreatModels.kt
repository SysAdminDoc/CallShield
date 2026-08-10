package com.sysadmindoc.callshield.data.remote

import java.util.LinkedHashMap

/** Stable source IDs used in URL evidence and diagnostics. */
enum class UrlThreatSource(
    val stableId: String,
    val defaultVersion: String,
) {
    LOCAL_SPAM_DOMAINS("local_spam_domains", "hot-domains-v1"),
    URLHAUS("urlhaus", "community-api-v1"),
    PHISHTANK("phishtank", "lookup-api-v1"),
    OPENPHISH("openphish", "public-feed-v1"),
    SAFE_BROWSING("safe_browsing", "lookup-api-v4"),
    WEB_RISK("web_risk", "lookup-api-v1"),
}

enum class UrlThreatVerdict {
    MALICIOUS,
    CLEAN,
    UNKNOWN,
}

enum class UrlThreatCategory {
    MALWARE,
    PHISHING,
    UNKNOWN,
}

/**
 * Evidence for one canonical URL. The canonical URL is already reduced to a
 * registrable-domain origin before any remote adapter sees it.
 */
internal data class UrlThreatResult(
    val source: UrlThreatSource,
    val sourceVersion: String,
    val canonicalUrl: String,
    val verdict: UrlThreatVerdict,
    val category: UrlThreatCategory = UrlThreatCategory.UNKNOWN,
    val tags: List<String> = emptyList(),
    val expiresAtMillis: Long,
    val detail: String = "",
) {
    val isMalicious: Boolean
        get() = verdict == UrlThreatVerdict.MALICIOUS

    companion object {
        fun unknown(
            source: UrlThreatSource,
            sourceVersion: String,
            canonicalUrl: String,
            nowMillis: Long,
            detail: String = "",
        ): UrlThreatResult =
            UrlThreatResult(
                source = source,
                sourceVersion = sourceVersion,
                canonicalUrl = canonicalUrl,
                verdict = UrlThreatVerdict.UNKNOWN,
                expiresAtMillis = nowMillis + UrlThreatCache.UNKNOWN_TTL_MILLIS,
                detail = detail,
            )

        fun clean(
            source: UrlThreatSource,
            sourceVersion: String,
            canonicalUrl: String,
            nowMillis: Long,
            ttlMillis: Long = UrlThreatCache.DEFAULT_TTL_MILLIS,
        ): UrlThreatResult =
            UrlThreatResult(
                source = source,
                sourceVersion = sourceVersion,
                canonicalUrl = canonicalUrl,
                verdict = UrlThreatVerdict.CLEAN,
                expiresAtMillis = nowMillis + ttlMillis,
            )

        fun malicious(
            source: UrlThreatSource,
            sourceVersion: String,
            canonicalUrl: String,
            category: UrlThreatCategory,
            nowMillis: Long,
            tags: List<String> = emptyList(),
            detail: String = "",
            ttlMillis: Long = UrlThreatCache.DEFAULT_TTL_MILLIS,
        ): UrlThreatResult =
            UrlThreatResult(
                source = source,
                sourceVersion = sourceVersion,
                canonicalUrl = canonicalUrl,
                verdict = UrlThreatVerdict.MALICIOUS,
                category = category,
                tags = tags,
                expiresAtMillis = nowMillis + ttlMillis,
                detail = detail,
            )
    }
}

internal interface UrlThreatAdapter {
    val source: UrlThreatSource
    val sourceVersion: String
    val isConfigured: Boolean

    suspend fun lookup(
        canonicalUrl: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): UrlThreatResult
}

/** Bounded in-memory cache; raw SMS and URL paths never enter persistent storage. */
internal class UrlThreatCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class CacheKey(
        val source: UrlThreatSource,
        val sourceVersion: String,
        val canonicalUrl: String,
    )

    private val entries =
        object : LinkedHashMap<CacheKey, UrlThreatResult>(maxEntries.coerceAtLeast(1), 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<CacheKey, UrlThreatResult>?,
            ): Boolean = size > maxEntries
        }
    private val lock = Any()

    fun get(
        source: UrlThreatSource,
        sourceVersion: String,
        canonicalUrl: String,
    ): UrlThreatResult? =
        synchronized(lock) {
            val key = CacheKey(source, sourceVersion, canonicalUrl)
            val result = entries[key] ?: return@synchronized null
            if (clock() >= result.expiresAtMillis) {
                entries.remove(key)
                null
            } else {
                result
            }
        }

    fun put(result: UrlThreatResult) {
        synchronized(lock) {
            if (clock() < result.expiresAtMillis) {
                entries[CacheKey(result.source, result.sourceVersion, result.canonicalUrl)] = result
            }
        }
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    internal fun sizeForTests(): Int = synchronized(lock) { entries.size }

    companion object {
        const val DEFAULT_TTL_MILLIS = 15L * 60L * 1_000L
        const val UNKNOWN_TTL_MILLIS = 5L * 60L * 1_000L
        private const val DEFAULT_MAX_ENTRIES = 256
    }
}

/** Source and license posture is kept in code so adapters cannot silently drift. */
internal object UrlThreatSourceManifest {
    data class Entry(
        val source: UrlThreatSource,
        val accessMode: String,
        val privacyMode: String,
        val licenseNote: String,
    )

    val entries: List<Entry> =
        listOf(
            Entry(
                UrlThreatSource.URLHAUS,
                accessMode = "optional_auth_key",
                privacyMode = "canonical_origin_only",
                licenseNote = "abuse.ch community API; fair-use and Auth-Key terms apply",
            ),
            Entry(
                UrlThreatSource.PHISHTANK,
                accessMode = "public_low_rate_or_optional_app_key",
                privacyMode = "canonical_origin_only",
                licenseNote = "PhishTank API terms and rate limits apply",
            ),
            Entry(
                UrlThreatSource.OPENPHISH,
                accessMode = "public_feed",
                privacyMode = "local_feed_match",
                licenseNote = "OpenPhish public-feed terms apply; do not redistribute the feed",
            ),
            Entry(
                UrlThreatSource.SAFE_BROWSING,
                accessMode = "optional_api_key_non_commercial",
                privacyMode = "canonical_origin_only",
                licenseNote = "Google Safe Browsing is non-commercial and requires attribution",
            ),
            Entry(
                UrlThreatSource.WEB_RISK,
                accessMode = "optional_google_cloud_api_key",
                privacyMode = "canonical_origin_only",
                licenseNote = "Google Web Risk terms and billing apply",
            ),
        )
}

internal data class UrlThreatAdapterConfig(
    val urlhausAuthKey: String? = null,
    val phishTankAppKey: String? = null,
    val safeBrowsingApiKey: String? = null,
    val webRiskApiKey: String? = null,
    val commercialUse: Boolean = false,
)

internal object UrlThreatAdapterCatalog {
    fun all(config: UrlThreatAdapterConfig = UrlThreatAdapterConfig()): List<UrlThreatAdapter> =
        listOf(
            UrlhausThreatAdapter(authKey = config.urlhausAuthKey),
            PhishTankThreatAdapter(appKey = config.phishTankAppKey),
            OpenPhishThreatAdapter(),
            if (config.commercialUse) {
                WebRiskThreatAdapter(apiKey = config.webRiskApiKey)
            } else {
                SafeBrowsingThreatAdapter(apiKey = config.safeBrowsingApiKey)
            },
        )

    fun enabled(config: UrlThreatAdapterConfig = UrlThreatAdapterConfig()): List<UrlThreatAdapter> = all(config).filter(UrlThreatAdapter::isConfigured)
}
