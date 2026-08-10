package com.sysadmindoc.callshield.data.remote

import com.sysadmindoc.callshield.util.filterAsciiDigits
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest

/**
 * Privacy-preserving input contract for PhoneBlock's hash lookup endpoint.
 *
 * PhoneBlock hashes the international representation of a number. We only
 * accept an explicit international number or a NANP number that can be
 * unambiguously converted to E.164; guessing a country for any other local
 * format would produce a misleading hash and disclose more than intended.
 */
internal fun phoneBlockInternationalNumber(raw: String): String? {
    val trimmed = raw.trim()
    val digits = filterAsciiDigits(trimmed)
    if (digits.length !in MIN_PHONE_BLOCK_DIGITS..MAX_PHONE_BLOCK_DIGITS) return null
    return when {
        trimmed.startsWith("+") -> "+$digits"
        digits.length == NANP_NATIONAL_DIGITS -> "+1$digits"
        digits.length == NANP_COUNTRY_CODE_DIGITS && digits.startsWith("1") -> "+$digits"
        else -> null
    }
}

internal fun phoneBlockSha1Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
    val hex = "0123456789ABCDEF"
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            val unsigned = byte.toInt() and 0xFF
            append(hex[unsigned ushr 4])
            append(hex[unsigned and 0x0F])
        }
    }
}

/** Build the hash-only PhoneBlock request; [international] never appears in the URL. */
internal fun phoneBlockLookupUrl(international: String): HttpUrl {
    val base = "https://phoneblock.net/phoneblock/api/check".toHttpUrl()
    return base
        .newBuilder()
        .addQueryParameter("sha1", phoneBlockSha1Hex(international))
        .addQueryParameter("prefix10", phoneBlockSha1Hex(international.dropLast(1)))
        .addQueryParameter("prefix100", phoneBlockSha1Hex(international.dropLast(2)))
        .addQueryParameter("format", "json")
        .build()
}

private const val MIN_PHONE_BLOCK_DIGITS = 7
private const val MAX_PHONE_BLOCK_DIGITS = 15
private const val NANP_NATIONAL_DIGITS = 10
private const val NANP_COUNTRY_CODE_DIGITS = 11
