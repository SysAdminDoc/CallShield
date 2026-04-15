package com.sysadmindoc.callshield.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Reverse phone lookup via free web sources.
 * Scrapes publicly available data — no API keys.
 */
object WebLookup {

    data class LookupResult(
        val carrier: String? = null,
        val lineType: String? = null,
        val spamReports: Int = 0,
        val communityNotes: List<String> = emptyList(),
        val source: String = ""
    )

    private val client = HttpClient.shared.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Check number against free public phone databases.
     * Returns whatever info we can find without API keys.
     */
    suspend fun lookup(number: String): LookupResult = withContext(Dispatchers.IO) {
        val digits = number.filter { it.isDigit() }
        if (digits.length < 10) return@withContext LookupResult()

        // Try to extract info from a public source
        try {
            val url = "https://www.whocalledme.com/Phone-Number.aspx/$digits"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext LookupResult()
                val body = response.body?.string() ?: return@withContext LookupResult()

                // Extract report count from page
                val reportRegex = Regex("""(\d+)\s*(?:report|complaint|comment)""", RegexOption.IGNORE_CASE)
                val reportMatch = reportRegex.find(body)
                val reports = reportMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

                // Extract any community notes (simplified)
                val noteRegex = Regex("""<div[^>]*class="[^"]*comment[^"]*"[^>]*>([^<]{10,200})</div>""", RegexOption.IGNORE_CASE)
                val notes = noteRegex.findAll(body).map { it.groupValues[1].trim() }.take(3).toList()

                LookupResult(
                    spamReports = reports,
                    communityNotes = notes,
                    source = "whocalledme.com"
                )
            }
        } catch (_: Exception) {
            LookupResult()
        }
    }
}
