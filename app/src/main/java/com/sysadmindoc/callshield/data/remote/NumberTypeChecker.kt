package com.sysadmindoc.callshield.data.remote

import com.sysadmindoc.callshield.util.filterAsciiDigits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request.Builder
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Optional carrier/number-type lookup via AbstractAPI Phone Validation.
 *
 * When no API key is set, returns UNKNOWN without making a network call.
 */
object NumberTypeChecker {
    enum class NumberLineType {
        VOIP,
        PREPAID,
        MOBILE,
        LANDLINE,
        UNKNOWN,
    }

    data class NumberTypeResult(
        val lineType: NumberLineType,
        val carrier: String = "",
        val country: String = "",
        val status: RemoteLookupStatus = RemoteLookupStatus.CLEAN,
    )

    private const val NUMBER_TYPE_RESPONSE_LIMIT_BYTES = 64L * 1024L

    private val client =
        HttpClient.shared
            .newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

    suspend fun check(
        number: String,
        apiKey: String,
    ): NumberTypeResult =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext NumberTypeResult(NumberLineType.UNKNOWN, status = RemoteLookupStatus.DISABLED)
            }

            try {
                val e164 =
                    normalizeE164(number)
                        ?: return@withContext NumberTypeResult(
                            NumberLineType.UNKNOWN,
                            status = RemoteLookupStatus.INVALID_INPUT,
                        )
                val url = "https://phonevalidation.abstractapi.com/v1/?api_key=$apiKey&phone=${e164.removePrefix("+")}"

                val request =
                    Builder()
                        .url(url)
                        .header("User-Agent", "CallShield/1.0")
                        .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext unknown(RemoteLookupStatus.fromHttpCode(response.code))
                    }

                    when (val body = response.body?.readUtf8Bounded(NUMBER_TYPE_RESPONSE_LIMIT_BYTES)) {
                        is BoundedResponseBody.Text -> parseNumberTypeBody(body.value)
                        null -> unknown(RemoteLookupStatus.EMPTY_BODY)
                        else -> unknown(body.status())
                    }
                }
            } catch (exception: IOException) {
                unknown(exception.toRemoteLookupStatus())
            }
        }

    fun scoreFromType(result: NumberTypeResult): Int =
        when (result.lineType) {
            NumberLineType.VOIP -> 30
            NumberLineType.PREPAID -> 20
            NumberLineType.MOBILE -> 5
            NumberLineType.LANDLINE -> 0
            NumberLineType.UNKNOWN -> 0
        }

    private fun normalizeE164(number: String): String? {
        val digits = filterAsciiDigits(number)
        return when {
            digits.length == 10 -> "+1$digits"
            digits.length == 11 && digits.startsWith("1") -> "+$digits"
            digits.length in 7..15 -> "+$digits"
            else -> null
        }
    }

    internal fun parseNumberTypeBody(body: String): NumberTypeResult {
        if (body.isMalformedJsonObject()) {
            return unknown(RemoteLookupStatus.PARSE_ERROR)
        }
        val typeMatch = Regex(""""type"\s*:\s*"([^"]+)"""").find(body)
        val rawType = typeMatch?.groupValues?.get(1)?.lowercase() ?: ""
        val lineType = classifyLineType(rawType, body.contains(""""is_prepaid":true"""))
        val carrier =
            Regex(""""name"\s*:\s*"([^"]+)"""")
                .find(body)
                ?.groupValues
                ?.get(1)
                .orEmpty()
        val country =
            Regex(""""country_code"\s*:\s*"([^"]+)"""")
                .find(body)
                ?.groupValues
                ?.get(1)
                .orEmpty()

        return NumberTypeResult(
            lineType = lineType,
            carrier = carrier,
            country = country,
            status = statusForParsedType(lineType, carrier, country),
        )
    }

    private fun classifyLineType(
        rawType: String,
        isPrepaid: Boolean,
    ): NumberLineType =
        when {
            "voip" in rawType || "virtual" in rawType -> NumberLineType.VOIP
            isPrepaid -> NumberLineType.PREPAID
            "mobile" in rawType || "wireless" in rawType -> NumberLineType.MOBILE
            "landline" in rawType || "fixed" in rawType -> NumberLineType.LANDLINE
            else -> NumberLineType.UNKNOWN
        }

    private fun statusForParsedType(
        lineType: NumberLineType,
        carrier: String,
        country: String,
    ): RemoteLookupStatus =
        if (lineType == NumberLineType.UNKNOWN && carrier.isBlank() && country.isBlank()) {
            RemoteLookupStatus.CLEAN
        } else {
            RemoteLookupStatus.FOUND
        }

    private fun unknown(status: RemoteLookupStatus): NumberTypeResult =
        NumberTypeResult(
            NumberLineType.UNKNOWN,
            status = status,
        )
}
