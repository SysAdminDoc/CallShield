package com.sysadmindoc.callshield.data.remote

import com.sysadmindoc.callshield.util.filterAsciiDigits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Multi-source external spam lookup — queries free APIs in parallel.
 * No API keys required for any of these sources.
 *
 * Sources:
 *   1. SkipCalls (1M+ spam numbers, free, no signup)
 *   2. PhoneBlock.net (community DB, no auth)
 *   3. WhoCalledMe (web scrape)
 *   4. OpenCNAM (caller name lookup, 60 req/hr free, no signup)
 */
object ExternalLookup {

    enum class SpamLookupSource {
        SKIP_CALLS,
        PHONE_BLOCK,
        WHO_CALLED_ME
    }

    data class MultiLookupResult(
        val isSpam: Boolean = false,
        val totalReports: Int = 0,
        val callerName: String = "",        // CNAM from OpenCNAM
        val sources: List<SourceResult> = emptyList(),
        val communityNotes: List<String> = emptyList()
    )

    data class SourceResult(
        val source: String,
        val isSpam: Boolean,
        val reports: Int = 0,
        val detail: String = "",
        val status: RemoteLookupStatus = RemoteLookupStatus.CLEAN
    )

    private const val JSON_RESPONSE_LIMIT_BYTES = 64L * 1024L
    private const val HTML_RESPONSE_LIMIT_BYTES = 128L * 1024L
    private const val CNAM_RESPONSE_LIMIT_BYTES = 16L * 1024L

    private val client = HttpClient.shared.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun spamLookupSources(): List<SpamLookupSource> = listOf(
        SpamLookupSource.SKIP_CALLS,
        SpamLookupSource.PHONE_BLOCK,
        SpamLookupSource.WHO_CALLED_ME
    )

    /**
     * Query all sources in parallel and merge results.
     */
    suspend fun lookupAll(number: String): MultiLookupResult = coroutineScope {
        val digits = filterAsciiDigits(number)
        if (digits.length < 7) return@coroutineScope MultiLookupResult()

        val spamDeferred = spamLookupSources().map { source ->
            async { lookupSpamSource(digits, source) }
        }
        val cnamDeferred = async { fetchCallerName(digits) }
        val spamResults = spamDeferred.awaitAll().filterNotNull()
        val callerName = cnamDeferred.await()
        val totalReports = spamResults.sumOf { it.reports }
        val isSpam = spamResults.any { it.isSpam } || totalReports >= 3

        MultiLookupResult(
            isSpam = isSpam,
            totalReports = totalReports,
            callerName = callerName,
            sources = spamResults,
            communityNotes = spamResults.flatMap {
                if (it.detail.isNotEmpty()) listOf("${it.source}: ${it.detail}") else emptyList()
            }
        )
    }

    suspend fun lookupSpamSource(numberOrDigits: String, source: SpamLookupSource): SourceResult? {
        val digits = filterAsciiDigits(numberOrDigits)
        if (digits.length < 7) return null
        return when (source) {
            SpamLookupSource.SKIP_CALLS -> checkSkipCalls(digits)
            SpamLookupSource.PHONE_BLOCK -> checkPhoneBlock(digits)
            SpamLookupSource.WHO_CALLED_ME -> checkWhoCalledMe(digits)
        }
    }

    suspend fun lookupCallerName(numberOrDigits: String): String {
        val digits = filterAsciiDigits(numberOrDigits)
        if (digits.length < 7) return ""
        return fetchCallerName(digits)
    }

    // ── SkipCalls (free, no key, 1M+ numbers) ─────────────────────────
    private suspend fun checkSkipCalls(digits: String): SourceResult? = withContext(Dispatchers.IO) {
        try {
            val url = "https://spam.skipcalls.app/check/$digits"
            val request = Request.Builder().url(url)
                .header("User-Agent", "CallShield/1.0")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext sourceFallback("SkipCalls", RemoteLookupStatus.HTTP_ERROR)
                }
                when (val body = response.body?.readUtf8Bounded(JSON_RESPONSE_LIMIT_BYTES)) {
                    is BoundedResponseBody.Text -> parseSkipCallsBody(body.value)
                    null -> sourceFallback("SkipCalls", RemoteLookupStatus.EMPTY_BODY)
                    else -> sourceFallback("SkipCalls", body.status())
                }
            }
        } catch (_: Exception) {
            sourceFallback("SkipCalls", RemoteLookupStatus.UNAVAILABLE)
        }
    }

    // ── PhoneBlock.net (community DB, per-number lookup, no auth) ────
    private suspend fun checkPhoneBlock(digits: String): SourceResult? = withContext(Dispatchers.IO) {
        try {
            val usNumber = if (digits.length == 10) "1$digits" else digits
            val url = "https://phoneblock.net/phoneblock/api/num/$usNumber"
            val request = Request.Builder().url(url)
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext sourceFallback("PhoneBlock", RemoteLookupStatus.HTTP_ERROR)
                }
                when (val body = response.body?.readUtf8Bounded(JSON_RESPONSE_LIMIT_BYTES)) {
                    is BoundedResponseBody.Text -> parsePhoneBlockBody(body.value)
                    null -> sourceFallback("PhoneBlock", RemoteLookupStatus.EMPTY_BODY)
                    else -> sourceFallback("PhoneBlock", body.status())
                }
            }
        } catch (_: Exception) {
            sourceFallback("PhoneBlock", RemoteLookupStatus.UNAVAILABLE)
        }
    }

    // ── WhoCalledMe (web scrape) ──────────────────────────────────────
    private suspend fun checkWhoCalledMe(digits: String): SourceResult? = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.whocalledme.com/Phone-Number.aspx/$digits"
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext sourceFallback("WhoCalledMe", RemoteLookupStatus.HTTP_ERROR)
                }
                when (val body = response.body?.readUtf8Bounded(HTML_RESPONSE_LIMIT_BYTES)) {
                    is BoundedResponseBody.Text -> parseWhoCalledMeBody(body.value)
                    null -> sourceFallback("WhoCalledMe", RemoteLookupStatus.EMPTY_BODY)
                    else -> sourceFallback("WhoCalledMe", body.status())
                }
            }
        } catch (_: Exception) {
            sourceFallback("WhoCalledMe", RemoteLookupStatus.UNAVAILABLE)
        }
    }

    // ── OpenCNAM — Caller Name (CNAM) lookup ─────────────────────────
    // Free tier: 60 requests/hour with no signup. Returns the caller's
    // registered name (e.g., "IRS SCAM", "CREDIT CARD SERVICES") which
    // is shown in the Caller ID overlay.
    private suspend fun fetchCallerName(digits: String): String = withContext(Dispatchers.IO) {
        try {
            // Normalize to E.164 — OpenCNAM expects +1XXXXXXXXXX
            val e164 = when {
                digits.length == 10 -> "+1$digits"
                digits.length == 11 && digits.startsWith("1") -> "+$digits"
                else -> return@withContext ""
            }
            val url = "https://api.opencnam.com/v3/phone/$e164?format=json"
            val request = Request.Builder().url(url)
                .header("User-Agent", "CallShield/1.0")
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext ""
                when (val body = response.body?.readUtf8Bounded(CNAM_RESPONSE_LIMIT_BYTES)) {
                    is BoundedResponseBody.Text -> parseCallerNameBody(body.value)
                    else -> ""
                }
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun sourceFallback(source: String, status: RemoteLookupStatus): SourceResult =
        SourceResult(source = source, isSpam = false, status = status)
}
