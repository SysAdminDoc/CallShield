package com.sysadmindoc.callshield.data.remote

import com.sysadmindoc.callshield.data.SmsContentAnalyzer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Background URL safety checker with local domain matching and opt-in threat
 * adapters. URL lookups run after the real-time blocking decision so a remote
 * service can never add latency to call or SMS interception.
 */
object UrlSafetyChecker {
    data class UrlCheckResult(
        val url: String,
        val isMalicious: Boolean,
        val threat: String = "",
        val tags: List<String> = emptyList(),
        val source: UrlThreatSource = UrlThreatSource.URLHAUS,
        val sourceVersion: String = UrlThreatSource.URLHAUS.defaultVersion,
        val category: UrlThreatCategory = UrlThreatCategory.UNKNOWN,
        val verdict: UrlThreatVerdict = if (isMalicious) UrlThreatVerdict.MALICIOUS else UrlThreatVerdict.CLEAN,
        val expiresAtMillis: Long = 0L,
    )

    private val urlPattern =
        Regex(
            """https?://[^\s<>\"]+|www\.[^\s<>\"]+""",
            RegexOption.IGNORE_CASE,
        )

    /** Cache keys include source and version; no URL evidence is persisted. */
    private val threatCache = UrlThreatCache()

    /**
     * Keyed adapters stay disabled until their key is supplied. PhishTank's
     * low-rate endpoint and the OpenPhish public feed are available for the
     * explicit remote-lookup setting without embedding credentials.
     */
    private val enabledAdapters = UrlThreatAdapterCatalog.enabled()

    /**
     * Extract URLs from an SMS body and return only malicious evidence.
     * Local spam-domain evidence is checked first and does not require network
     * access. Remote adapters receive only a canonical registrable-domain
     * origin, never the raw message or URL path/query.
     */
    suspend fun checkSmsBody(
        body: String,
        stripQuery: Boolean = true,
        allowRemoteLookup: Boolean = false,
    ): List<UrlCheckResult> {
        val urls = extractCandidateUrls(body)
        if (urls.isEmpty()) return emptyList()

        return urls.mapNotNull { url ->
            localSpamDomainResult(url, stripQuery)
                ?: if (allowRemoteLookup) {
                    checkUrlMatches(url).firstOrNull()
                } else {
                    null
                }
        }
    }

    /**
     * Check one URL and return its first adapter's evidence. Consumers should
     * use [checkSmsBody] for notification decisions, which only returns
     * malicious matches and never treats a remote unknown as malicious.
     */
    suspend fun checkUrl(url: String): UrlCheckResult =
        withContext(Dispatchers.IO) {
            val lookupUrl = normalizeRemoteLookupUrl(url)
            if (lookupUrl.isBlank()) {
                return@withContext UrlCheckResult(
                    url = "",
                    isMalicious = false,
                    verdict = UrlThreatVerdict.UNKNOWN,
                )
            }
            val evidence = lookupUrlEvidence(lookupUrl)
            evidence.firstOrNull(UrlThreatResult::isMalicious)?.let(::toCheckResult)
                ?: evidence.firstOrNull()?.let(::toCheckResult)
                ?: UrlCheckResult(
                    url = lookupUrl,
                    isMalicious = false,
                    verdict = UrlThreatVerdict.UNKNOWN,
                )
        }

    /** Return all malicious adapter matches for a canonical URL. */
    internal suspend fun checkUrlMatches(url: String): List<UrlCheckResult> {
        val lookupUrl = normalizeRemoteLookupUrl(url)
        if (lookupUrl.isBlank()) return emptyList()
        return lookupUrlEvidence(lookupUrl)
            .filter(UrlThreatResult::isMalicious)
            .map(::toCheckResult)
    }

    private suspend fun lookupUrlEvidence(lookupUrl: String): List<UrlThreatResult> {
        if (enabledAdapters.isEmpty()) return emptyList()
        val nowMillis = System.currentTimeMillis()
        return coroutineScope {
            enabledAdapters
                .map { adapter ->
                    async {
                        val cached = threatCache.get(adapter.source, adapter.sourceVersion, lookupUrl)
                        if (cached != null) {
                            cached
                        } else {
                            val result =
                                try {
                                    adapter.lookup(lookupUrl, nowMillis)
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (_: RuntimeException) {
                                    UrlThreatResult.unknown(
                                        source = adapter.source,
                                        sourceVersion = adapter.sourceVersion,
                                        canonicalUrl = lookupUrl,
                                        nowMillis = nowMillis,
                                        detail = "adapter_error",
                                    )
                                }
                            threatCache.put(result)
                            result
                        }
                    }
                }.awaitAll()
        }
    }

    private fun toCheckResult(result: UrlThreatResult): UrlCheckResult =
        UrlCheckResult(
            url = result.canonicalUrl,
            isMalicious = result.isMalicious,
            threat = result.detail.ifBlank { result.category.name.lowercase() },
            tags = result.tags,
            source = result.source,
            sourceVersion = result.sourceVersion,
            category = result.category,
            verdict = result.verdict,
            expiresAtMillis = result.expiresAtMillis,
        )

    internal fun localSpamDomainResult(
        url: String,
        stripQuery: Boolean = true,
    ): UrlCheckResult? {
        if (!SmsContentAnalyzer.isKnownSpamDomainUrl(url)) return null
        return UrlCheckResult(
            url = normalizeLocalResultUrl(url, stripQuery),
            isMalicious = true,
            threat = "known_spam_domain",
            tags = listOf("local_spam_domain"),
            source = UrlThreatSource.LOCAL_SPAM_DOMAINS,
            sourceVersion = UrlThreatSource.LOCAL_SPAM_DOMAINS.defaultVersion,
            category = UrlThreatCategory.UNKNOWN,
            verdict = UrlThreatVerdict.MALICIOUS,
            expiresAtMillis = System.currentTimeMillis() + UrlThreatCache.DEFAULT_TTL_MILLIS,
        )
    }

    internal fun extractCandidateUrls(
        body: String,
        limit: Int = 5,
    ): List<String> =
        urlPattern
            .findAll(body)
            .map { normalizeCandidateUrl(it.value) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(limit.coerceAtLeast(1))
            .toList()

    internal fun normalizeCandidateUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim().trimEnd('.', ',', '!', '?', ';', ':', ')', ']', '}')
        return if (trimmed.startsWith("www.", ignoreCase = true)) {
            "https://$trimmed"
        } else {
            trimmed
        }
    }

    internal fun normalizeRemoteLookupUrl(
        rawUrl: String,
        registrableDomain: (okhttp3.HttpUrl) -> String? = { it.topPrivateDomain() },
    ): String {
        val normalizedUrl = normalizeCandidateUrl(rawUrl)
        val parsedUrl = normalizedUrl.toHttpUrlOrNull() ?: return ""

        // Never disclose an SMS/RCS path, query, fragment, subdomain, or
        // embedded username/password to a threat service.
        val resolvedDomain = runCatching { registrableDomain(parsedUrl) }
        if (resolvedDomain.isFailure) return ""
        val lookupHost =
            resolvedDomain.getOrNull()
                ?: parsedUrl.host.takeIf(::isIpLiteral)
                ?: return ""
        return parsedUrl
            .newBuilder()
            .username("")
            .password("")
            .host(lookupHost)
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    internal fun clearThreatCacheForTests() {
        threatCache.clear()
    }

    private fun isIpLiteral(host: String): Boolean =
        ':' in host ||
            host.split('.').let { labels ->
                labels.size == 4 &&
                    labels.all { label -> label.toIntOrNull()?.let { it in 0..255 } == true }
            }

    internal fun normalizeLocalResultUrl(
        rawUrl: String,
        stripQuery: Boolean = true,
    ): String {
        val normalizedUrl = normalizeCandidateUrl(rawUrl)
        val parsedUrl = normalizedUrl.toHttpUrlOrNull()
        if (parsedUrl == null) {
            val withoutFragment = normalizedUrl.substringBefore('#')
            return if (stripQuery) withoutFragment.substringBefore('?') else withoutFragment
        }

        val builder = parsedUrl.newBuilder().fragment(null)
        if (stripQuery) builder.query(null)
        return builder.build().toString()
    }
}
