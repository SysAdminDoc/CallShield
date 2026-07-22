package com.sysadmindoc.callshield.data

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.util.Locale

/** Region-aware canonical identity contract for phone numbers and SMS sender IDs. */
class PhoneIdentityCanonicalizer internal constructor(
    regionIso: String?,
    private val formatToE164: (String, String) -> String?,
) {
    private val regionIso = normalizeRegion(regionIso)

    constructor(regionIso: String?) : this(regionIso, PhoneNumberUtils::formatNumberToE164)

    fun canonicalizePhone(raw: String): String {
        val normalized = normalizePhoneNumber(raw)
        val digitCount = normalized.count { it in '0'..'9' }
        val shouldFormat =
            normalized.isNotBlank() &&
                !normalized.startsWith("+") &&
                digitCount > MAX_SHORT_CODE_DIGITS &&
                regionIso != null
        val formatted =
            if (shouldFormat) {
                runCatching { formatToE164(normalized, requireNotNull(regionIso)) }.getOrNull()
            } else {
                null
            }
        return formatted
            ?.let(::normalizePhoneNumber)
            ?.takeIf { it.startsWith("+") }
            ?: normalized
    }

    fun canonicalizeIdentity(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.any { it in 'A'..'Z' || it in 'a'..'z' }) {
            return trimmed
                .takeIf { it.length <= MAX_OPAQUE_SENDER_LENGTH && OPAQUE_SENDER_PATTERN.matches(it) }
                ?.uppercase(Locale.ROOT)
                .orEmpty()
        }
        return canonicalizePhone(trimmed)
    }

    companion object {
        private const val MAX_SHORT_CODE_DIGITS = 6
        private const val MAX_OPAQUE_SENDER_LENGTH = 64
        private val OPAQUE_SENDER_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9 ._-]*")

        fun fromContext(context: Context): PhoneIdentityCanonicalizer = PhoneIdentityCanonicalizer(resolveRegion(context))

        internal fun resolveRegion(context: Context): String? {
            val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val candidates =
                listOf(
                    runCatching { telephony?.networkCountryIso }.getOrNull(),
                    runCatching { telephony?.simCountryIso }.getOrNull(),
                    Locale.getDefault().country,
                )
            return candidates.firstNotNullOfOrNull(::normalizeRegion)
        }

        private fun normalizeRegion(value: String?): String? =
            value
                ?.trim()
                ?.uppercase(Locale.ROOT)
                ?.takeIf { it.length == 2 && it.all { character -> character in 'A'..'Z' } }
    }
}
