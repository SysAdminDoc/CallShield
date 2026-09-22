package com.sysadmindoc.callshield.data.remote

import com.sysadmindoc.callshield.util.filterAsciiDigits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request.Builder
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * External spam lookup through free services that need no key.
 *
 * Only SkipCalls is left. PhoneBlock's hash endpoint and OpenCNAM now require
 * accounts, which CallShield never takes, and WhoCalledMe's domain is parked;
 * all three were removed on 2026-09-21 after a probe showed them dead.
 * `scripts/probe_live_sources.py` checks what remains.
 */
object ExternalLookup {
    enum class SpamLookupSource {
        SKIP_CALLS,
    }

    data class MultiLookupResult(
        val isSpam: Boolean = false,
        val totalReports: Int = 0,
        val sources: List<SourceResult> = emptyList(),
        val communityNotes: List<String> = emptyList(),
    )

    data class SourceResult(
        val source: String,
        val isSpam: Boolean,
        val reports: Int = 0,
        val detail: String = "",
        val status: RemoteLookupStatus = RemoteLookupStatus.CLEAN,
    )

    private const val JSON_RESPONSE_LIMIT_BYTES = 64L * 1024L

    private val client =
        HttpClient.shared
            .newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

    fun spamLookupSources(): List<SpamLookupSource> = SpamLookupSource.entries

    suspend fun lookupAll(number: String): MultiLookupResult =
        coroutineScope {
            val digits = filterAsciiDigits(number)
            if (digits.length < 7) return@coroutineScope MultiLookupResult()

            val spamResults =
                spamLookupSources()
                    .map { source -> async { lookupSpamSource(number, source) } }
                    .awaitAll()
                    .filterNotNull()
            val totalReports = spamResults.sumOf { result -> result.reports }
            val isSpam = spamResults.any { result -> result.isSpam } || totalReports >= 3

            MultiLookupResult(
                isSpam = isSpam,
                totalReports = totalReports,
                sources = spamResults,
                communityNotes =
                    spamResults.flatMap { result ->
                        if (result.detail.isNotEmpty()) {
                            listOf("${result.source}: ${result.detail}")
                        } else {
                            emptyList()
                        }
                    },
            )
        }

    suspend fun lookupSpamSource(
        numberOrDigits: String,
        source: SpamLookupSource,
    ): SourceResult? {
        val digits = filterAsciiDigits(numberOrDigits)
        if (digits.length < 7) return null
        return when (source) {
            SpamLookupSource.SKIP_CALLS -> checkSkipCalls(digits)
        }
    }

    private suspend fun checkSkipCalls(digits: String): SourceResult? =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://spam.skipcalls.app/check/$digits"
                val request =
                    Builder()
                        .url(url)
                        .header("User-Agent", "CallShield/1.0")
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext sourceFallback(
                            "SkipCalls",
                            RemoteLookupStatus.fromHttpCode(response.code),
                        )
                    }
                    when (val body = response.body.readUtf8Bounded(JSON_RESPONSE_LIMIT_BYTES)) {
                        is BoundedResponseBody.Text -> parseSkipCallsBody(body.value)
                        else -> sourceFallback("SkipCalls", body.status())
                    }
                }
            } catch (exception: IOException) {
                sourceFallback("SkipCalls", exception.toRemoteLookupStatus())
            }
        }

    private fun sourceFallback(
        source: String,
        status: RemoteLookupStatus,
    ): SourceResult = SourceResult(source = source, isSpam = false, status = status)
}
