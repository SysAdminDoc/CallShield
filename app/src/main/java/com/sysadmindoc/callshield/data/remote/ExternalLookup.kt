package com.sysadmindoc.callshield.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
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
        val detail: String = ""
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Query all sources in parallel and merge results.
     */
    suspend fun lookupAll(number: String): MultiLookupResult = coroutineScope {
        val digits = number.filter { it.isDigit() }
        if (digits.length < 7) return@coroutineScope MultiLookupResult()

        val skipCallsDeferred  = async { checkSkipCalls(digits) }
        val phoneBlockDeferred = async { checkPhoneBlock(digits) }
        val whoCalledDeferred  = async { checkWhoCalledMe(digits) }
        val cnamDeferred       = async { fetchCallerName(digits) }

        val spamResults = listOf(
            skipCallsDeferred.await(),
            phoneBlockDeferred.await(),
            whoCalledDeferred.await()
        ).filterNotNull()

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

    // ── SkipCalls (free, no key, 1M+ numbers) ─────────────────────────
    private suspend fun checkSkipCalls(digits: String): SourceResult? = withContext(Dispatchers.IO) {
        try {
            val url = "https://spam.skipcalls.app/check/$digits"
            val request = Request.Builder().url(url)
                .header("User-Agent", "CallShield/1.0")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body?.string() ?: return@withContext null

            // Parse JSON response
            val isSpam = body.contains("\"spam\":true", ignoreCase = true) ||
                         body.contains("\"isSpam\":true", ignoreCase = true) ||
                         body.contains("\"status\":\"spam\"", ignoreCase = true)
            val reportMatch = Regex(""""(?:reports?|count)":\s*(\d+)""").find(body)
            val reports = reportMatch?.groupValues?.get(1)?.toIntOrNull() ?: if (isSpam) 1 else 0

            SourceResult("SkipCalls", isSpam, reports, if (isSpam) "Flagged as spam" else "")
        } catch (_: Exception) {
            null
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
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body?.string() ?: return@withContext null

            val votesMatch = Regex(""""votes":\s*(\d+)""").find(body)
            val votes = votesMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val blacklisted = body.contains("\"blackListed\":true")
            val rating = Regex(""""rating":\s*"([^"]+)"""").find(body)?.groupValues?.get(1) ?: ""
            val isSpam = blacklisted || votes >= 3 || rating.startsWith("D_") || rating.startsWith("E_")

            SourceResult(
                "PhoneBlock", isSpam, votes,
                when {
                    blacklisted -> "Blacklisted ($votes votes)"
                    votes > 0 -> "$votes community votes"
                    else -> "Rating: $rating"
                }
            )
        } catch (_: Exception) {
            null
        }
    }

    // ── WhoCalledMe (web scrape) ──────────────────────────────────────
    private suspend fun checkWhoCalledMe(digits: String): SourceResult? = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.whocalledme.com/Phone-Number.aspx/$digits"
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body?.string() ?: return@withContext null
            val reportMatch = Regex("""(\d+)\s*(?:report|complaint|comment)""", RegexOption.IGNORE_CASE).find(body)
            val reports = reportMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

            if (reports > 0) {
                SourceResult("WhoCalledMe", reports >= 3, reports, "$reports reports")
            } else null
        } catch (_: Exception) {
            null
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
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext ""

            val body = response.body?.string() ?: return@withContext ""
            // Response: {"number":"+15551234567","name":"JOHN DOE"}
            val nameMatch = Regex(""""name"\s*:\s*"([^"]+)"""").find(body)
            nameMatch?.groupValues?.get(1)?.trim() ?: ""
        } catch (_: Exception) {
            ""
        }
    }
}
