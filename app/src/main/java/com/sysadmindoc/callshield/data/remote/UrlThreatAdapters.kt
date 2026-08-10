package com.sysadmindoc.callshield.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

private const val URL_THREAT_USER_AGENT = "CallShield/1.0 (URL threat lookup)"
private const val URLHAUS_ENDPOINT = "https://urlhaus-api.abuse.ch/v1/url/"
private const val PHISHTANK_ENDPOINT = "https://checkurl.phishtank.com/checkurl/"
private const val OPENPHISH_FEED_URL =
    "https://raw.githubusercontent.com/openphish/public_feed/refs/heads/main/feed.txt"
private const val SAFE_BROWSING_ENDPOINT =
    "https://safebrowsing.googleapis.com/v4/threatMatches:find"
private const val WEB_RISK_ENDPOINT = "https://webrisk.googleapis.com/v1/uris:search"
private const val MAX_URL_LOOKUP_RESPONSE_BYTES = 256L * 1024L
private const val MAX_OPENPHISH_FEED_BYTES = 16L * 1024L * 1024L
private const val MAX_OPENPHISH_HOSTS = 250_000
private const val OPENPHISH_FEED_TTL_MILLIS = 30L * 60L * 1_000L
private const val MAX_REMOTE_TTL_MILLIS = 24L * 60L * 60L * 1_000L

private val urlThreatClient: OkHttpClient =
    HttpClient.shared
        .newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

internal class UrlhausThreatAdapter(
    private val authKey: String? = null,
    private val client: OkHttpClient = urlThreatClient,
) : UrlThreatAdapter {
    override val source: UrlThreatSource = UrlThreatSource.URLHAUS
    override val sourceVersion: String = source.defaultVersion
    override val isConfigured: Boolean
        get() = !authKey.isNullOrBlank()

    override suspend fun lookup(
        canonicalUrl: String,
        nowMillis: Long,
    ): UrlThreatResult =
        withContext(Dispatchers.IO) {
            if (!isConfigured) {
                return@withContext UrlThreatResult.unknown(
                    source,
                    sourceVersion,
                    canonicalUrl,
                    nowMillis,
                    detail = "auth_key_unconfigured",
                )
            }
            if (!canonicalUrl.isSafeLookupUrl()) {
                return@withContext UrlThreatResult.unknown(source, sourceVersion, canonicalUrl, nowMillis, "invalid_url")
            }
            try {
                val requestBody = FormBody.Builder().add("url", canonicalUrl).build()
                val request =
                    Request
                        .Builder()
                        .url(URLHAUS_ENDPOINT)
                        .post(requestBody)
                        .header("Auth-Key", authKey.orEmpty())
                        .header("User-Agent", URL_THREAT_USER_AGENT)
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext UrlThreatResult.unknown(
                            source,
                            sourceVersion,
                            canonicalUrl,
                            nowMillis,
                            detail = "http_${response.code}",
                        )
                    }
                    val body = response.body.readUtf8Bounded(MAX_URL_LOOKUP_RESPONSE_BYTES)
                    if (body !is BoundedResponseBody.Text) {
                        return@withContext UrlThreatResult.unknown(source, sourceVersion, canonicalUrl, nowMillis, "empty_body")
                    }
                    parseUrlhausResponse(body.value, canonicalUrl, nowMillis)
                }
            } catch (_: IOException) {
                UrlThreatResult.unknown(source, sourceVersion, canonicalUrl, nowMillis, "unavailable")
            } catch (_: RuntimeException) {
                UrlThreatResult.unknown(source, sourceVersion, canonicalUrl, nowMillis, "unavailable")
            }
        }

    companion object {
        internal fun parseUrlhausResponse(
            body: String,
            canonicalUrl: String,
            nowMillis: Long,
        ): UrlThreatResult {
            val queryStatus = jsonString(body, "query_status").orEmpty()
            val threat = jsonString(body, "threat").orEmpty()
            val tags = jsonStringArray(body, "tags")
            val category =
                if (threat.contains("phish", ignoreCase = true) ||
                    queryStatus.contains("phish", ignoreCase = true)
                ) {
                    UrlThreatCategory.PHISHING
                } else {
                    UrlThreatCategory.MALWARE
                }
            val positive =
                queryStatus in setOf("is_malware", "is_phishing", "is_botnet_cc") ||
                    (
                        queryStatus == "ok" &&
                            (jsonString(body, "url_status") != null || jsonArrayHasEntries(body, "urls"))
                    )
            return if (positive) {
                UrlThreatResult.malicious(
                    source = UrlThreatSource.URLHAUS,
                    sourceVersion = UrlThreatSource.URLHAUS.defaultVersion,
                    canonicalUrl = canonicalUrl,
                    category = category,
                    nowMillis = nowMillis,
                    tags = tags,
                    detail = threat.ifBlank { queryStatus },
                )
            } else if (queryStatus == "no_results") {
                UrlThreatResult.clean(
                    UrlThreatSource.URLHAUS,
                    UrlThreatSource.URLHAUS.defaultVersion,
                    canonicalUrl,
                    nowMillis,
                )
            } else {
                UrlThreatResult.unknown(
                    UrlThreatSource.URLHAUS,
                    UrlThreatSource.URLHAUS.defaultVersion,
                    canonicalUrl,
                    nowMillis,
                    detail = queryStatus.ifBlank { "unrecognized_response" },
                )
            }
        }
    }
}

internal class PhishTankThreatAdapter(
    private val appKey: String? = null,
    private val client: OkHttpClient = urlThreatClient,
) : UrlThreatAdapter {
    override val source: UrlThreatSource = UrlThreatSource.PHISHTANK
    override val sourceVersion: String = source.defaultVersion
    override val isConfigured: Boolean = true

    override suspend fun lookup(
        canonicalUrl: String,
        nowMillis: Long,
    ): UrlThreatResult =
        withContext(Dispatchers.IO) {
            if (!canonicalUrl.isSafeLookupUrl()) {
                return@withContext UrlThreatResult.unknown(source, sourceVersion, canonicalUrl, nowMillis, "invalid_url")
            }
            try {
                val form =
                    FormBody
                        .Builder()
                        .add("url", canonicalUrl)
                        .add("format", "json")
                        .apply { appKey?.takeIf(String::isNotBlank)?.let { add("app_key", it) } }
                        .build()
                val request =
                    Request
                        .Builder()
                        .url(PHISHTANK_ENDPOINT)
                        .post(form)
                        .header("User-Agent", URL_THREAT_USER_AGENT)
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext UrlThreatResult.unknown(
                            source,
                            sourceVersion,
                            canonicalUrl,
                            nowMillis,
                            detail = "http_${response.code}",
                        )
                    }
                    val body = response.body.readUtf8Bounded(MAX_URL_LOOKUP_RESPONSE_BYTES)
                    if (body !is BoundedResponseBody.Text) {
                        return@withContext UrlThreatResult.unknown(source, sourceVersion, canonicalUrl, nowMillis, "empty_body")
                    }
                    parsePhishTankResponse(body.value, canonicalUrl, nowMillis)
                }
            } catch (_: IOException) {
                UrlThreatResult.unknown(source, sourceVersion, canonicalUrl, nowMillis, "unavailable")
            } catch (_: RuntimeException) {
                UrlThreatResult.unknown(source, sourceVersion, canonicalUrl, nowMillis, "unavailable")
            }
        }

    companion object {
        internal fun parsePhishTankResponse(
            body: String,
            canonicalUrl: String,
            nowMillis: Long,
        ): UrlThreatResult {
            val inDatabase = jsonBoolean(body, "in_database")
            if (inDatabase != true) {
                return if (inDatabase == false) {
                    UrlThreatResult.clean(
                        UrlThreatSource.PHISHTANK,
                        UrlThreatSource.PHISHTANK.defaultVersion,
                        canonicalUrl,
                        nowMillis,
                    )
                } else {
                    UrlThreatResult.unknown(
                        UrlThreatSource.PHISHTANK,
                        UrlThreatSource.PHISHTANK.defaultVersion,
                        canonicalUrl,
                        nowMillis,
                        detail = "unrecognized_response",
                    )
                }
            }
            val verified = jsonTruthy(jsonString(body, "verified"))
            val valid = jsonTruthy(jsonString(body, "valid"))
            return if (verified && valid) {
                UrlThreatResult.malicious(
                    source = UrlThreatSource.PHISHTANK,
                    sourceVersion = UrlThreatSource.PHISHTANK.defaultVersion,
                    canonicalUrl = canonicalUrl,
                    category = UrlThreatCategory.PHISHING,
                    nowMillis = nowMillis,
                    detail = "verified_phish",
                )
            } else {
                UrlThreatResult.unknown(
                    UrlThreatSource.PHISHTANK,
                    UrlThreatSource.PHISHTANK.defaultVersion,
                    canonicalUrl,
                    nowMillis,
                    detail = "unverified_phish",
                )
            }
        }
    }
}

internal class OpenPhishThreatAdapter(
    private val client: OkHttpClient = urlThreatClient,
) : UrlThreatAdapter {
    override val source: UrlThreatSource = UrlThreatSource.OPENPHISH
    override val sourceVersion: String = source.defaultVersion
    override val isConfigured: Boolean = true

    @Volatile
    private var feedSnapshot: FeedSnapshot? = null

    override suspend fun lookup(
        canonicalUrl: String,
        nowMillis: Long,
    ): UrlThreatResult {
        if (!canonicalUrl.isSafeLookupUrl()) {
            return UrlThreatResult.unknown(source, sourceVersion, canonicalUrl, nowMillis, "invalid_url")
        }
        val snapshot =
            loadSnapshot(nowMillis)
                ?: return UrlThreatResult.unknown(source, sourceVersion, canonicalUrl, nowMillis, "feed_unavailable")
        val host = canonicalUrl.toHttpUrlOrNull()?.host?.removePrefix("www.")
        return if (host != null && host in snapshot.hosts) {
            UrlThreatResult.malicious(
                source = source,
                sourceVersion = sourceVersion,
                canonicalUrl = canonicalUrl,
                category = UrlThreatCategory.PHISHING,
                nowMillis = nowMillis,
                detail = "feed_domain_match",
            )
        } else {
            UrlThreatResult.clean(source, sourceVersion, canonicalUrl, nowMillis)
        }
    }

    private suspend fun loadSnapshot(nowMillis: Long): FeedSnapshot? {
        feedSnapshot?.takeIf { nowMillis < it.expiresAtMillis }?.let { return it }
        return withContext(Dispatchers.IO) {
            feedSnapshot?.takeIf { nowMillis < it.expiresAtMillis }?.let { return@withContext it }
            try {
                val request =
                    Request
                        .Builder()
                        .url(OPENPHISH_FEED_URL)
                        .header("User-Agent", URL_THREAT_USER_AGENT)
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body.readUtf8Bounded(MAX_OPENPHISH_FEED_BYTES)
                    if (body !is BoundedResponseBody.Text) return@withContext null
                    if (body.value.isBlank()) return@withContext null
                    val hosts =
                        body.value
                            .lineSequence()
                            .mapNotNull {
                                it
                                    .trim()
                                    .toHttpUrlOrNull()
                                    ?.host
                                    ?.removePrefix("www.")
                            }.distinct()
                            .take(MAX_OPENPHISH_HOSTS)
                            .toSet()
                    FeedSnapshot(hosts = hosts, expiresAtMillis = nowMillis + OPENPHISH_FEED_TTL_MILLIS).also {
                        feedSnapshot = it
                    }
                }
            } catch (_: IOException) {
                null
            } catch (_: RuntimeException) {
                null
            }
        }
    }

    private data class FeedSnapshot(
        val hosts: Set<String>,
        val expiresAtMillis: Long,
    )
}

internal class SafeBrowsingThreatAdapter(
    private val apiKey: String? = null,
    private val client: OkHttpClient = urlThreatClient,
) : UrlThreatAdapter {
    override val source: UrlThreatSource = UrlThreatSource.SAFE_BROWSING
    override val sourceVersion: String = source.defaultVersion
    override val isConfigured: Boolean
        get() = !apiKey.isNullOrBlank()

    override suspend fun lookup(
        canonicalUrl: String,
        nowMillis: Long,
    ): UrlThreatResult =
        withContext(Dispatchers.IO) {
            if (!isConfigured) {
                return@withContext UrlThreatResult.unknown(
                    source,
                    sourceVersion,
                    canonicalUrl,
                    nowMillis,
                    detail = "api_key_unconfigured",
                )
            }
            if (!canonicalUrl.isSafeLookupUrl()) {
                return@withContext UrlThreatResult.unknown(source, sourceVersion, canonicalUrl, nowMillis, "invalid_url")
            }
            try {
                val escapedUrl = canonicalUrl.escapeJsonString()
                val requestBody =
                    """{"client":{"clientId":"callshield","clientVersion":"url-threat-v1"},"threatInfo":{"threatTypes":["MALWARE","SOCIAL_ENGINEERING","UNWANTED_SOFTWARE"],"platformTypes":["ANY_PLATFORM"],"threatEntryTypes":["URL"],"threatEntries":[{"url":"$escapedUrl"}]}}"""
                        .toRequestBody(JSON_MEDIA_TYPE)
                val url =
                    SAFE_BROWSING_ENDPOINT
                        .toHttpUrlOrNull()
                        ?.newBuilder()
                        ?.addQueryParameter("key", apiKey.orEmpty())
                        ?.build()
                        ?: return@withContext UrlThreatResult.unknown(source, sourceVersion, canonicalUrl, nowMillis, "invalid_endpoint")
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .post(requestBody)
                        .header("User-Agent", URL_THREAT_USER_AGENT)
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext UrlThreatResult.unknown(
                            source,
                            sourceVersion,
                            canonicalUrl,
                            nowMillis,
                            detail = "http_${response.code}",
                        )
                    }
                    val body = response.body.readUtf8Bounded(MAX_URL_LOOKUP_RESPONSE_BYTES)
                    if (body !is BoundedResponseBody.Text) {
                        return@withContext UrlThreatResult.unknown(source, sourceVersion, canonicalUrl, nowMillis, "empty_body")
                    }
                    parseSafeBrowsingResponse(body.value, canonicalUrl, nowMillis)
                }
            } catch (_: IOException) {
                UrlThreatResult.unknown(source, sourceVersion, canonicalUrl, nowMillis, "unavailable")
            } catch (_: RuntimeException) {
                UrlThreatResult.unknown(source, sourceVersion, canonicalUrl, nowMillis, "unavailable")
            }
        }

    companion object {
        internal fun parseSafeBrowsingResponse(
            body: String,
            canonicalUrl: String,
            nowMillis: Long,
        ): UrlThreatResult {
            val matches =
                Regex("\\\"matches\\\"\\s*:\\s*\\[([^]]*)]", RegexOption.IGNORE_CASE)
                    .find(body)
                    ?.groupValues
                    ?.getOrNull(1)
            if (matches.isNullOrBlank()) {
                return UrlThreatResult.clean(UrlThreatSource.SAFE_BROWSING, UrlThreatSource.SAFE_BROWSING.defaultVersion, canonicalUrl, nowMillis)
            }
            val category =
                if (body.contains("SOCIAL_ENGINEERING", ignoreCase = true)) {
                    UrlThreatCategory.PHISHING
                } else {
                    UrlThreatCategory.MALWARE
                }
            val ttlMillis =
                Regex("\\\"cacheDuration\\\"\\s*:\\s*\\\"([0-9.]+)s\\\"")
                    .find(body)
                    ?.groupValues
                    ?.get(1)
                    ?.toDoubleOrNull()
                    ?.times(1_000.0)
                    ?.toLong()
                    ?.coerceIn(1L, MAX_REMOTE_TTL_MILLIS)
                    ?: UrlThreatCache.DEFAULT_TTL_MILLIS
            return UrlThreatResult.malicious(
                source = UrlThreatSource.SAFE_BROWSING,
                sourceVersion = UrlThreatSource.SAFE_BROWSING.defaultVersion,
                canonicalUrl = canonicalUrl,
                category = category,
                nowMillis = nowMillis,
                detail = "safe_browsing_match",
                ttlMillis = ttlMillis,
            )
        }
    }
}

internal class WebRiskThreatAdapter(
    private val apiKey: String? = null,
    private val client: OkHttpClient = urlThreatClient,
) : UrlThreatAdapter {
    override val source: UrlThreatSource = UrlThreatSource.WEB_RISK
    override val sourceVersion: String = source.defaultVersion
    override val isConfigured: Boolean
        get() = !apiKey.isNullOrBlank()

    override suspend fun lookup(
        canonicalUrl: String,
        nowMillis: Long,
    ): UrlThreatResult =
        withContext(Dispatchers.IO) {
            if (!isConfigured) {
                return@withContext UrlThreatResult.unknown(
                    source,
                    sourceVersion,
                    canonicalUrl,
                    nowMillis,
                    detail = "api_key_unconfigured",
                )
            }
            if (!canonicalUrl.isSafeLookupUrl()) {
                return@withContext UrlThreatResult.unknown(source, sourceVersion, canonicalUrl, nowMillis, "invalid_url")
            }
            try {
                val endpoint =
                    WEB_RISK_ENDPOINT
                        .toHttpUrlOrNull()
                        ?.newBuilder()
                        ?.addQueryParameter("threatTypes", "MALWARE")
                        ?.addQueryParameter("threatTypes", "SOCIAL_ENGINEERING")
                        ?.addQueryParameter("threatTypes", "UNWANTED_SOFTWARE")
                        ?.addQueryParameter("uri", canonicalUrl)
                        ?.addQueryParameter("key", apiKey.orEmpty())
                        ?.build()
                        ?: return@withContext UrlThreatResult.unknown(source, sourceVersion, canonicalUrl, nowMillis, "invalid_endpoint")
                val request =
                    Request
                        .Builder()
                        .url(endpoint)
                        .header("User-Agent", URL_THREAT_USER_AGENT)
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext UrlThreatResult.unknown(
                            source,
                            sourceVersion,
                            canonicalUrl,
                            nowMillis,
                            detail = "http_${response.code}",
                        )
                    }
                    val body = response.body.readUtf8Bounded(MAX_URL_LOOKUP_RESPONSE_BYTES)
                    if (body !is BoundedResponseBody.Text || body.value.isBlank()) {
                        return@withContext UrlThreatResult.unknown(source, sourceVersion, canonicalUrl, nowMillis, "empty_body")
                    }
                    if (body.value.trim() == "{}") {
                        return@withContext UrlThreatResult.clean(source, sourceVersion, canonicalUrl, nowMillis)
                    }
                    parseWebRiskResponse(body.value, canonicalUrl, nowMillis)
                }
            } catch (_: IOException) {
                UrlThreatResult.unknown(source, sourceVersion, canonicalUrl, nowMillis, "unavailable")
            } catch (_: RuntimeException) {
                UrlThreatResult.unknown(source, sourceVersion, canonicalUrl, nowMillis, "unavailable")
            }
        }

    companion object {
        internal fun parseWebRiskResponse(
            body: String,
            canonicalUrl: String,
            nowMillis: Long,
        ): UrlThreatResult {
            val threatTypes = jsonStringArray(body, "threatTypes")
            if (threatTypes.isEmpty()) {
                return UrlThreatResult.clean(UrlThreatSource.WEB_RISK, UrlThreatSource.WEB_RISK.defaultVersion, canonicalUrl, nowMillis)
            }
            val category =
                if (threatTypes.any { it == "SOCIAL_ENGINEERING" }) {
                    UrlThreatCategory.PHISHING
                } else {
                    UrlThreatCategory.MALWARE
                }
            val expiresAt =
                Regex("\\\"expireTime\\\"\\s*:\\s*\\\"([^\"]+)\\\"")
                    .find(body)
                    ?.groupValues
                    ?.get(1)
                    ?.let { raw -> runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull() }
                    ?.takeIf { it > nowMillis }
                    ?.coerceAtMost(nowMillis + MAX_REMOTE_TTL_MILLIS)
            val result =
                UrlThreatResult.malicious(
                    source = UrlThreatSource.WEB_RISK,
                    sourceVersion = UrlThreatSource.WEB_RISK.defaultVersion,
                    canonicalUrl = canonicalUrl,
                    category = category,
                    nowMillis = nowMillis,
                    tags = threatTypes,
                    detail = "web_risk_match",
                )
            return expiresAt?.let { result.copy(expiresAtMillis = it) } ?: result
        }
    }
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

private fun String.isSafeLookupUrl(): Boolean {
    val parsed = toHttpUrlOrNull() ?: return false
    return parsed.scheme == "https" || parsed.scheme == "http"
}

private fun String.escapeJsonString(): String =
    replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

private fun jsonString(
    body: String,
    key: String,
): String? =
    Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
        .find(body)
        ?.groupValues
        ?.getOrNull(1)

private fun jsonBoolean(
    body: String,
    key: String,
): Boolean? =
    Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*(true|false|\\\"(?:true|false|y|n)\\\")", RegexOption.IGNORE_CASE)
        .find(body)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim('"')
        ?.let { value ->
            when (value.lowercase()) {
                "true", "y" -> true
                "false", "n" -> false
                else -> null
            }
        }

private fun jsonTruthy(value: String?): Boolean =
    value.equals("true", ignoreCase = true) ||
        value.equals("y", ignoreCase = true) ||
        value.equals("yes", ignoreCase = true)

private fun jsonStringArray(
    body: String,
    key: String,
): List<String> {
    val array =
        Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\[([^]]*)]", RegexOption.IGNORE_CASE)
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()
    return Regex("\\\"([^\\\"]+)\\\"")
        .findAll(array)
        .map { it.groupValues[1] }
        .take(32)
        .toList()
}

private fun jsonArrayHasEntries(
    body: String,
    key: String,
): Boolean =
    Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\[([^]]*)]", RegexOption.IGNORE_CASE)
        .find(body)
        ?.groupValues
        ?.getOrNull(1)
        ?.isNotBlank() == true
