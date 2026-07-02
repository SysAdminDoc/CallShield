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
 * Multi-source external spam lookup through free APIs with no required keys.
 */
object ExternalLookup {
    enum class SpamLookupSource {
        SKIP_CALLS,
        PHONE_BLOCK,
        WHO_CALLED_ME,
    }

    data class MultiLookupResult(
        val isSpam: Boolean = false,
        val totalReports: Int = 0,
        val callerName: String = "",
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

    data class CallerNameResult(
        val callerName: String = "",
        val source: String = "OpenCNAM",
        val status: RemoteLookupStatus = RemoteLookupStatus.CLEAN,
    ) {
        fun asSourceResult(): SourceResult =
            SourceResult(
                source = source,
                isSpam = false,
                detail = callerName,
                status = status,
            )
    }

    private const val JSON_RESPONSE_LIMIT_BYTES = 64L * 1024L
    private const val HTML_RESPONSE_LIMIT_BYTES = 128L * 1024L
    private const val CNAM_RESPONSE_LIMIT_BYTES = 16L * 1024L

    private val client =
        HttpClient.shared
            .newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

    fun spamLookupSources(): List<SpamLookupSource> =
        listOf(
            SpamLookupSource.SKIP_CALLS,
            SpamLookupSource.PHONE_BLOCK,
            SpamLookupSource.WHO_CALLED_ME,
        )

    suspend fun lookupAll(number: String): MultiLookupResult =
        coroutineScope {
            val digits = filterAsciiDigits(number)
            if (digits.length < 7) return@coroutineScope MultiLookupResult()

            val spamDeferred =
                spamLookupSources().map { source ->
                    async { lookupSpamSource(digits, source) }
                }
            val cnamDeferred = async { fetchCallerName(digits) }
            val spamResults = spamDeferred.awaitAll().filterNotNull()
            val cnamResult = cnamDeferred.await()
            val totalReports = spamResults.sumOf { result -> result.reports }
            val isSpam = spamResults.any { result -> result.isSpam } || totalReports >= 3

            MultiLookupResult(
                isSpam = isSpam,
                totalReports = totalReports,
                callerName = cnamResult.callerName,
                sources = spamResults + cnamResult.asSourceResult(),
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
            SpamLookupSource.PHONE_BLOCK -> checkPhoneBlock(digits)
            SpamLookupSource.WHO_CALLED_ME -> checkWhoCalledMe(digits)
        }
    }

    suspend fun lookupCallerName(numberOrDigits: String): String {
        val digits = filterAsciiDigits(numberOrDigits)
        if (digits.length < 7) return ""
        return lookupCallerNameResult(digits).callerName
    }

    suspend fun lookupCallerNameResult(numberOrDigits: String): CallerNameResult {
        val digits = filterAsciiDigits(numberOrDigits)
        if (digits.length < 7) return CallerNameResult(status = RemoteLookupStatus.INVALID_INPUT)
        return fetchCallerName(digits)
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
                    when (val body = response.body?.readUtf8Bounded(JSON_RESPONSE_LIMIT_BYTES)) {
                        is BoundedResponseBody.Text -> parseSkipCallsBody(body.value)
                        null -> sourceFallback("SkipCalls", RemoteLookupStatus.EMPTY_BODY)
                        else -> sourceFallback("SkipCalls", body.status())
                    }
                }
            } catch (exception: IOException) {
                sourceFallback("SkipCalls", exception.toRemoteLookupStatus())
            }
        }

    private suspend fun checkPhoneBlock(digits: String): SourceResult? =
        withContext(Dispatchers.IO) {
            try {
                val usNumber = if (digits.length == 10) "1$digits" else digits
                val url = "https://phoneblock.net/phoneblock/api/num/$usNumber"
                val request =
                    Builder()
                        .url(url)
                        .header("Accept", "application/json")
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext sourceFallback(
                            "PhoneBlock",
                            RemoteLookupStatus.fromHttpCode(response.code),
                        )
                    }
                    when (val body = response.body?.readUtf8Bounded(JSON_RESPONSE_LIMIT_BYTES)) {
                        is BoundedResponseBody.Text -> parsePhoneBlockBody(body.value)
                        null -> sourceFallback("PhoneBlock", RemoteLookupStatus.EMPTY_BODY)
                        else -> sourceFallback("PhoneBlock", body.status())
                    }
                }
            } catch (exception: IOException) {
                sourceFallback("PhoneBlock", exception.toRemoteLookupStatus())
            }
        }

    private suspend fun checkWhoCalledMe(digits: String): SourceResult? =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://www.whocalledme.com/Phone-Number.aspx/$digits"
                val request =
                    Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext sourceFallback(
                            "WhoCalledMe",
                            RemoteLookupStatus.fromHttpCode(response.code),
                        )
                    }
                    when (val body = response.body?.readUtf8Bounded(HTML_RESPONSE_LIMIT_BYTES)) {
                        is BoundedResponseBody.Text -> parseWhoCalledMeBody(body.value)
                        null -> sourceFallback("WhoCalledMe", RemoteLookupStatus.EMPTY_BODY)
                        else -> sourceFallback("WhoCalledMe", body.status())
                    }
                }
            } catch (exception: IOException) {
                sourceFallback("WhoCalledMe", exception.toRemoteLookupStatus())
            }
        }

    private suspend fun fetchCallerName(digits: String): CallerNameResult =
        withContext(Dispatchers.IO) {
            try {
                val e164 =
                    when {
                        digits.length == 10 -> "+1$digits"
                        digits.length == 11 && digits.startsWith("1") -> "+$digits"
                        else -> return@withContext CallerNameResult(status = RemoteLookupStatus.INVALID_INPUT)
                    }
                val url = "https://api.opencnam.com/v3/phone/$e164?format=json"
                val request =
                    Builder()
                        .url(url)
                        .header("User-Agent", "CallShield/1.0")
                        .header("Accept", "application/json")
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext CallerNameResult(
                            status = RemoteLookupStatus.fromHttpCode(response.code),
                        )
                    }
                    when (val body = response.body?.readUtf8Bounded(CNAM_RESPONSE_LIMIT_BYTES)) {
                        is BoundedResponseBody.Text -> parseCallerNameBody(body.value)
                        null -> CallerNameResult(status = RemoteLookupStatus.EMPTY_BODY)
                        else -> CallerNameResult(status = body.status())
                    }
                }
            } catch (exception: IOException) {
                CallerNameResult(status = exception.toRemoteLookupStatus())
            }
        }

    private fun sourceFallback(
        source: String,
        status: RemoteLookupStatus,
    ): SourceResult = SourceResult(source = source, isSpam = false, status = status)
}
