package com.sysadmindoc.callshield.data.remote

import com.sysadmindoc.callshield.data.SmsContentAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Background URL safety checker using URLhaus (abuse.ch).
 * Free, no API key, community-maintained malware/phishing URL database.
 *
 * This runs AFTER the real-time blocking decision to avoid adding
 * latency to SMS interception. Results are used to update block log
 * entries and flag phishing URLs in notifications.
 *
 * API: https://urlhaus-api.abuse.ch/v1/url/
 * Rate limit: generous, no key required.
 */
object UrlSafetyChecker {
    data class UrlCheckResult(
        val url: String,
        val isMalicious: Boolean,
        val threat: String = "", // "malware", "phishing", "botnet_cc", etc.
        val tags: List<String> = emptyList(),
    )

    // URL pattern — extract all URLs from message body
    private val URL_PATTERN =
        Regex(
            """https?://[^\s<>"]+|www\.[^\s<>"]+""",
            RegexOption.IGNORE_CASE,
        )

    private val client =
        HttpClient
            .shared
            .newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

    // URLhaus URL-status responses are small JSON; cap generously to bound memory.
    private const val MAX_URLHAUS_BYTES = 256L * 1024L

    private val JSON_TYPE = "application/json".toMediaType()

    /**
     * Extract all URLs from an SMS body and check each against URLhaus.
     * Returns a list of malicious URLs found, or empty list if clean/unreachable.
     * Safe to call from a background coroutine — never blocks the call/SMS decision.
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
                ?: if (allowRemoteLookup) checkUrl(url).takeIf { it.isMalicious } else null
        }
    }

    /**
     * Check a single URL against URLhaus.
     */
    suspend fun checkUrl(
        url: String,
    ): UrlCheckResult =
        withContext(Dispatchers.IO) {
            val lookupUrl = normalizeRemoteLookupUrl(url)
            if (lookupUrl.isBlank()) return@withContext UrlCheckResult("", false)
            try {
                val escapedUrl =
                    lookupUrl
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("\t", "\\t")
                val jsonBody = """{"url":"$escapedUrl"}""".toRequestBody(JSON_TYPE)
                val request =
                    Request
                        .Builder()
                        .url("https://urlhaus-api.abuse.ch/v1/url/")
                        .post(jsonBody)
                        .header("User-Agent", "CallShield/1.0")
                        .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext UrlCheckResult(lookupUrl, false)

                    // Bound the response read — every other remote lookup caps its
                    // body; a large/abusive endpoint response should not inflate
                    // memory on the post-decision SMS/RCS URL-scan path.
                    val responseBody =
                        when (val bounded = response.body?.readUtf8Bounded(MAX_URLHAUS_BYTES)) {
                            is BoundedResponseBody.Text -> bounded.value
                            else -> return@withContext UrlCheckResult(lookupUrl, false)
                        }

                    // Parse response
                    // {"query_status":"is_phishing","url_status":"online","threat":"phishing",...}
                    // {"query_status":"no_results"} — clean or unknown
                    val status =
                        Regex(""""query_status"\s*:\s*"([^"]+)"""")
                            .find(responseBody)
                            ?.groupValues
                            ?.get(1)
                            ?: "no_results"

                    val isMalicious = status in listOf("is_malware", "is_phishing", "is_botnet_cc")

                    if (!isMalicious) return@withContext UrlCheckResult(lookupUrl, false)

                    val threat =
                        Regex(""""threat"\s*:\s*"([^"]+)"""")
                            .find(responseBody)
                            ?.groupValues
                            ?.get(1)
                            ?: status

                    val tagsMatch = Regex(""""tags"\s*:\s*\[([^\]]*)]""").find(responseBody)
                    val tags =
                        tagsMatch
                            ?.groupValues
                            ?.get(1)
                            ?.split(",")
                            ?.map { it.trim().trim('"') }
                            ?.filter { it.isNotEmpty() }
                            ?: emptyList()

                    UrlCheckResult(url = lookupUrl, isMalicious = true, threat = threat, tags = tags)
                }
            } catch (_: Exception) {
                UrlCheckResult(lookupUrl, false) // Network error = don't flag
            }
        }

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
        )
    }

    internal fun extractCandidateUrls(
        body: String,
        limit: Int = 5,
    ): List<String> =
        URL_PATTERN
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
        val parsedUrl =
            normalizedUrl.toHttpUrlOrNull()
                ?: return ""

        // Never disclose an SMS/RCS path, query, or subdomain to URLhaus.
        // A remote lookup is explicit opt-in and receives only the
        // registrable domain (or literal IP host) as an origin URL.
        val resolvedDomain = runCatching { registrableDomain(parsedUrl) }
        if (resolvedDomain.isFailure) return ""
        val lookupHost =
            resolvedDomain.getOrNull()
                ?: parsedUrl.host.takeIf(::isIpLiteral)
                ?: return ""
        return parsedUrl
            .newBuilder()
            .host(lookupHost)
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
            .toString()
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
