package com.sysadmindoc.callshield.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Optional carrier/number-type lookup via AbstractAPI Phone Validation.
 *
 * Identifies whether a number is:
 *  - VoIP (highest spam risk)
 *  - Prepaid/burner (high risk)
 *  - Mobile (medium risk)
 *  - Landline (lower risk)
 *
 * Free tier: 250 requests/month with an API key (no credit card needed).
 * When no API key is set, returns UNKNOWN without making any network call.
 *
 * To enable: users set their AbstractAPI key in Settings → Advanced.
 * Key URL: https://app.abstractapi.com/api/phone-validation/pricing
 *
 * Only called when the number has a heuristic score > 30 to conserve quota.
 */
object NumberTypeChecker {

    enum class NumberLineType {
        VOIP,       // VoIP — highest spam risk (+30 score)
        PREPAID,    // Prepaid/burner — high risk (+20 score)
        MOBILE,     // Mobile — moderate risk (+5 score)
        LANDLINE,   // Landline — lower risk (+0 score)
        UNKNOWN     // Could not determine
    }

    data class NumberTypeResult(
        val lineType: NumberLineType,
        val carrier: String = "",
        val country: String = ""
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * Look up a number's line type. Returns UNKNOWN immediately if no API key provided.
     * Only call this for numbers with a pre-existing heuristic score > 30.
     */
    suspend fun check(number: String, apiKey: String): NumberTypeResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext NumberTypeResult(NumberLineType.UNKNOWN)

        try {
            val e164 = normalizeE164(number) ?: return@withContext NumberTypeResult(NumberLineType.UNKNOWN)
            val url = "https://phonevalidation.abstractapi.com/v1/?api_key=$apiKey&phone=${e164.removePrefix("+")}"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "CallShield/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext NumberTypeResult(NumberLineType.UNKNOWN)

            val body = response.body?.string() ?: return@withContext NumberTypeResult(NumberLineType.UNKNOWN)

            // Parse type field: {"type":{"type":"VoIP","is_prepaid":false,...},...}
            val typeMatch = Regex(""""type"\s*:\s*"([^"]+)"""").find(body)
            val rawType = typeMatch?.groupValues?.get(1)?.lowercase() ?: ""

            val isPrepaid = body.contains(""""is_prepaid":true""")

            val lineType = when {
                "voip" in rawType || "virtual" in rawType -> NumberLineType.VOIP
                isPrepaid -> NumberLineType.PREPAID
                "mobile" in rawType || "wireless" in rawType -> NumberLineType.MOBILE
                "landline" in rawType || "fixed" in rawType -> NumberLineType.LANDLINE
                else -> NumberLineType.UNKNOWN
            }

            val carrier = Regex(""""name"\s*:\s*"([^"]+)"""").find(body)
                ?.groupValues?.get(1) ?: ""
            val country = Regex(""""country_code"\s*:\s*"([^"]+)"""").find(body)
                ?.groupValues?.get(1) ?: ""

            NumberTypeResult(lineType = lineType, carrier = carrier, country = country)
        } catch (_: Exception) {
            NumberTypeResult(NumberLineType.UNKNOWN)
        }
    }

    /** Heuristic spam score contribution from number line type. */
    fun scoreFromType(result: NumberTypeResult): Int = when (result.lineType) {
        NumberLineType.VOIP     -> 30
        NumberLineType.PREPAID  -> 20
        NumberLineType.MOBILE   -> 5
        NumberLineType.LANDLINE -> 0
        NumberLineType.UNKNOWN  -> 0
    }

    private fun normalizeE164(number: String): String? {
        val digits = number.filter { it.isDigit() }
        return when {
            digits.length == 10 -> "+1$digits"
            digits.length == 11 && digits.startsWith("1") -> "+$digits"
            digits.length in 7..15 -> "+$digits"
            else -> null
        }
    }
}
