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
 *   3. WhoCalledMe (web scrape, existing)
 */
object ExternalLookup {

    data class MultiLookupResult(
        val isSpam: Boolean = false,
        val totalReports: Int = 0,
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

        val results = listOf(
            async { checkSkipCalls(digits) },
            async { checkPhoneBlock(digits) },
            async { checkWhoCalledMe(digits) }
        ).awaitAll()

        val allSources = results.filterNotNull()
        val totalReports = allSources.sumOf { it.reports }
        val isSpam = allSources.any { it.isSpam } || totalReports >= 3

        MultiLookupResult(
            isSpam = isSpam,
            totalReports = totalReports,
            sources = allSources,
            communityNotes = allSources.flatMap {
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
}
