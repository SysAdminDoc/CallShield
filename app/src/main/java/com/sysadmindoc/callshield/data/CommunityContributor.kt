package com.sysadmindoc.callshield.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * One-tap anonymous community contribution.
 * Sends reports to the CallShield Cloudflare Worker,
 * which stores them in data/reports/ via GitHub API.
 * No user account or API key needed from the app side.
 *
 * Supports both spam reports AND false positive reports ("not_spam").
 */
object CommunityContributor {

    private const val WORKER_URL = "https://callshield-reports.snafumatthew.workers.dev"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    data class ContributeResult(val success: Boolean, val message: String)

    /**
     * Report a number as spam.
     */
    suspend fun contribute(number: String, type: String = "spam"): ContributeResult {
        return post(number, type)
    }

    /**
     * Report a false positive — this number is NOT spam.
     * The merge script will subtract votes from this number.
     */
    suspend fun reportNotSpam(number: String): ContributeResult {
        return post(number, "not_spam")
    }

    private suspend fun post(number: String, type: String): ContributeResult = withContext(Dispatchers.IO) {
        try {
            val normalized = normalizeForReport(number) ?: return@withContext ContributeResult(false, "Invalid number")
            val json = """{"number":"$normalized","type":"$type"}"""
            val body = json.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(WORKER_URL)
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val msg = if (type == "not_spam") "Reported as not spam" else "Contributed anonymously"
                ContributeResult(true, msg)
            } else {
                ContributeResult(false, "Server error (${response.code})")
            }
        } catch (e: Exception) {
            ContributeResult(false, "Network error: ${e.message}")
        }
    }

    private fun normalizeForReport(number: String): String? {
        val digits = number.filter { it.isDigit() }
        return when {
            digits.length == 10 -> "+1$digits"
            digits.length == 11 && digits.startsWith("1") -> "+$digits"
            digits.length in 7..15 -> "+$digits"
            else -> null
        }
    }
}
