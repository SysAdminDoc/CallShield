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
        if (trimmed.any(Char::isLetter)) {
            val opaque =
                trimmed
                    .takeIf { it.length <= MAX_OPAQUE_SENDER_LENGTH && OPAQUE_SENDER_PATTERN.matches(it) }
                    ?.uppercase(Locale.ROOT)
            // Never collapse a lettered sender to "": a blank identity makes
            // isSpamSms skip keyword/content screening entirely, so a spammer
            // could opt out of analysis by using a decorated or non-Latin
            // sender ID. Unrepresentable senders get a stable hashed token
            // instead — deterministic per sender, so burst detection still
            // aggregates correctly.
            return opaque ?: hashedOpaqueIdentity(trimmed)
        }
        return canonicalizePhone(trimmed)
    }

    private fun hashedOpaqueIdentity(raw: String): String {
        val digest =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.UTF_8))
        return "OPAQUE:" + digest.take(HASHED_SENDER_BYTES).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_SHORT_CODE_DIGITS = 6
        private const val MAX_OPAQUE_SENDER_LENGTH = 64
        private const val HASHED_SENDER_BYTES = 8

        // Unicode letters/digits, not just ASCII — legitimate sender IDs in
        // non-Latin scripts must keep a readable canonical form.
        private val OPAQUE_SENDER_PATTERN = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N} ._-]*")

        private const val REGION_CACHE_TTL_MS = 60_000L

        @Volatile
        private var cachedInstance: Pair<Long, PhoneIdentityCanonicalizer>? = null

        fun fromContext(context: Context): PhoneIdentityCanonicalizer = PhoneIdentityCanonicalizer(resolveRegion(context))

        /**
         * TTL-cached variant for hot paths: [resolveRegion] costs two
         * TelephonyManager binder round-trips, which the 5-second screening
         * budget should not pay on every contact lookup. The short TTL still
         * picks up SIM swaps and locale changes.
         */
        fun cachedFromContext(context: Context): PhoneIdentityCanonicalizer {
            val now = System.currentTimeMillis()
            cachedInstance?.let { (createdAt, instance) ->
                if (now - createdAt < REGION_CACHE_TTL_MS) return instance
            }
            return fromContext(context.applicationContext).also { cachedInstance = now to it }
        }

        internal fun resolveRegion(context: Context): String? {
            val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            // SIM region first: it is the user's home region for interpreting
            // national-format numbers (libphonenumber convention). The network
            // region is the *visited* network while roaming — preferring it
            // would canonicalize a roaming user's national numbers under the
            // wrong country and, worse, bake that into the one-time v12
            // identity migration.
            val candidates =
                listOf(
                    runCatching { telephony?.simCountryIso }.getOrNull(),
                    runCatching { telephony?.networkCountryIso }.getOrNull(),
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
