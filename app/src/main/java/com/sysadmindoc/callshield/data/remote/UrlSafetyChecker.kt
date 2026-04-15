package com.sysadmindoc.callshield.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    suspend fun checkSmsBody(body: String): List<UrlCheckResult> {
        val urls = extractCandidateUrls(body)

        if (urls.isEmpty()) return emptyList()

        return urls.mapNotNull { url ->
            checkUrl(url).takeIf { it.isMalicious }
        }
    }

    /**
     * Check a single URL against URLhaus.
     */
    suspend fun checkUrl(url: String): UrlCheckResult = withContext(Dispatchers.IO) {
        try {
            val normalizedUrl = normalizeCandidateUrl(url)

            val escapedUrl = normalizedUrl.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
            val jsonBody = """{"url":"$escapedUrl"}""".toRequestBody(JSON_TYPE)
            val request = Request.Builder()
                .url("https://urlhaus-api.abuse.ch/v1/url/")
                .post(jsonBody)
                .header("User-Agent", "CallShield/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext UrlCheckResult(url, false)

                val responseBody = response.body?.string() ?: return@withContext UrlCheckResult(url, false)

                // Parse response
                // {"query_status":"is_phishing","url_status":"online","threat":"phishing",...}
                // {"query_status":"no_results"} — clean or unknown
                val status = Regex(""""query_status"\s*:\s*"([^"]+)"""").find(responseBody)
                    ?.groupValues?.get(1) ?: "no_results"

                val isMalicious = status in listOf("is_malware", "is_phishing", "is_botnet_cc")

                if (!isMalicious) return@withContext UrlCheckResult(url, false)

                val threat = Regex(""""threat"\s*:\s*"([^"]+)"""").find(responseBody)
                    ?.groupValues?.get(1) ?: status

                val tagsMatch = Regex(""""tags"\s*:\s*\[([^\]]*)]""").find(responseBody)
                val tags = tagsMatch?.groupValues?.get(1)
                    ?.split(",")
                    ?.map { it.trim().trim('"') }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList()

                UrlCheckResult(url = url, isMalicious = true, threat = threat, tags = tags)
            }
        } catch (_: Exception) {
            UrlCheckResult(url, false) // Network error = don't flag
        }
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
}
