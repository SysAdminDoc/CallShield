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
        val threat: String = "",   // "malware", "phishing", "botnet_cc", etc.
        val tags: List<String> = emptyList()
    )

    // URL pattern — extract all URLs from message body
    private val URL_PATTERN = Regex(
        """https?://[^\s<>"]+|www\.[^\s<>"]+""",
        RegexOption.IGNORE_CASE
    )

    private val client = HttpClient.shared.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json".toMediaType()

    /**
     * Extract all URLs from an SMS body and check each against URLhaus.
     * Returns a list of malicious URLs found, or empty list if clean/unreachable.
     * Safe to call from a background coroutine — never blocks the call/SMS decision.
     */
    suspend fun checkSmsBody(
        body: String,
        stripQuery: Boolean = true,
    ): List<UrlCheckResult> {
        val urls = extractCandidateUrls(body)

        if (urls.isEmpty()) return emptyList()

        return urls.mapNotNull { url ->
            localSpamDomainResult(url, stripQuery)
                ?: checkUrl(url, stripQuery).takeIf { it.isMalicious }
        }
    }

    /**
     * Check a single URL against URLhaus.
     */
    suspend fun checkUrl(
        url: String,
        stripQuery: Boolean = true,
    ): UrlCheckResult = withContext(Dispatchers.IO) {
        val lookupUrl = normalizeRemoteLookupUrl(url, stripQuery)
        try {
            val escapedUrl = lookupUrl.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
            val jsonBody = """{"url":"$escapedUrl"}""".toRequestBody(JSON_TYPE)
            val request = Request.Builder()
                .url("https://urlhaus-api.abuse.ch/v1/url/")
                .post(jsonBody)
                .header("User-Agent", "CallShield/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext UrlCheckResult(lookupUrl, false)

                val responseBody = response.body?.string() ?: return@withContext UrlCheckResult(lookupUrl, false)

                // Parse response
                // {"query_status":"is_phishing","url_status":"online","threat":"phishing",...}
                // {"query_status":"no_results"} — clean or unknown
                val status = Regex(""""query_status"\s*:\s*"([^"]+)"""").find(responseBody)
                    ?.groupValues?.get(1) ?: "no_results"

                val isMalicious = status in listOf("is_malware", "is_phishing", "is_botnet_cc")

                if (!isMalicious) return@withContext UrlCheckResult(lookupUrl, false)

                val threat = Regex(""""threat"\s*:\s*"([^"]+)"""").find(responseBody)
                    ?.groupValues?.get(1) ?: status

                val tagsMatch = Regex(""""tags"\s*:\s*\[([^\]]*)]""").find(responseBody)
                val tags = tagsMatch?.groupValues?.get(1)
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
            url = normalizeRemoteLookupUrl(url, stripQuery),
            isMalicious = true,
            threat = "known_spam_domain",
            tags = listOf("local_spam_domain")
        )
    }

    internal fun extractCandidateUrls(body: String, limit: Int = 5): List<String> {
        return URL_PATTERN.findAll(body)
            .map { normalizeCandidateUrl(it.value) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(limit.coerceAtLeast(1))
            .toList()
    }

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
        stripQuery: Boolean = true,
    ): String {
        val normalizedUrl = normalizeCandidateUrl(rawUrl)
        val parsedUrl = normalizedUrl.toHttpUrlOrNull() ?: return normalizedUrl
            .substringBefore('#')
            .let { if (stripQuery) it.substringBefore('?') else it }
        val builder = parsedUrl.newBuilder().fragment(null)
        if (stripQuery) builder.query(null)
        return builder.build().toString()
    }
}
