package com.sysadmindoc.callshield.data

import android.os.Bundle
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.sysadmindoc.callshield.domain.model.DnoStatus
import com.sysadmindoc.callshield.domain.model.LineType
import com.sysadmindoc.callshield.domain.model.ParsedPassport
import com.sysadmindoc.callshield.domain.model.RichCallData
import java.net.URI
import java.util.Base64
import java.util.UUID

internal enum class StirShakenRejectionReason {
    EMPTY,
    TOO_LARGE,
    MALFORMED_JWT,
    UNSUPPORTED_HEADER,
    MISSING_CLAIM,
    STALE_IAT,
    INVALID_CLAIM,
}

internal sealed interface StirShakenParseResult {
    data class Accepted(
        val passport: ParsedPassport,
    ) : StirShakenParseResult

    data class Rejected(
        val reason: StirShakenRejectionReason,
    ) : StirShakenParseResult
}

internal data class CarrierIdentityExtras(
    val passport: ParsedPassport? = null,
    val dnoStatus: DnoStatus = DnoStatus.UNKNOWN,
    val lineType: LineType = LineType.UNKNOWN,
)

/**
 * Bounded structural parser for optional OEM PASSporT/RCD extras.
 *
 * This parser intentionally does not verify ES256 signatures or fetch x5u. A token that parses
 * successfully is metadata only; Android's carrier verification status remains the trust gate.
 */
@Suppress("CyclomaticComplexMethod", "MagicNumber", "ReturnCount", "TooManyFunctions")
internal object StirShakenParser {
    const val MAX_TOKEN_LENGTH = 8_192
    const val MAX_CLOCK_SKEW_SECONDS = 60L

    const val EXTRA_PASSPORT = "android.telecom.extra.PASSPORT"
    const val EXTRA_IDENTITY = "android.telecom.extra.IDENTITY"
    const val EXTRA_STIR_SHAKEN_IDENTITY = "android.telecom.extra.STIR_SHAKEN_IDENTITY"
    const val EXTRA_DNO_STATUS = "android.telecom.extra.DNO_STATUS"
    const val EXTRA_STIR_SHAKEN_DNO_STATUS = "android.telecom.extra.STIR_SHAKEN_DNO_STATUS"
    const val EXTRA_LINE_TYPE = "android.telecom.extra.LINE_TYPE"
    const val EXTRA_STIR_SHAKEN_LINE_TYPE = "android.telecom.extra.STIR_SHAKEN_LINE_TYPE"

    private const val MAX_CERTIFICATE_URL_LENGTH = 2_048
    private const val MAX_CLAIM_STRING_LENGTH = 512
    private const val MAX_TELEPHONE_LENGTH = 32
    private const val MAX_DESTINATIONS = 32
    private const val MAX_MEDIA_KEYS = 32
    private const val MAX_ORIGID_LENGTH = 36

    private val passportExtraKeys =
        listOf(EXTRA_PASSPORT, EXTRA_IDENTITY, EXTRA_STIR_SHAKEN_IDENTITY)
    private val dnoExtraKeys = listOf(EXTRA_DNO_STATUS, EXTRA_STIR_SHAKEN_DNO_STATUS)
    private val lineTypeExtraKeys = listOf(EXTRA_LINE_TYPE, EXTRA_STIR_SHAKEN_LINE_TYPE)
    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val objectAdapter: JsonAdapter<Map<String, Any?>> =
        Moshi.Builder().build().adapter(mapType)

    fun parse(
        token: String?,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L,
    ): StirShakenParseResult {
        val compact = token?.trim().orEmpty()
        if (compact.isEmpty()) return StirShakenParseResult.Rejected(StirShakenRejectionReason.EMPTY)
        if (compact.length > MAX_TOKEN_LENGTH) return StirShakenParseResult.Rejected(StirShakenRejectionReason.TOO_LARGE)

        val parts = compact.split('.')
        if (parts.size != 3 || parts[2].isEmpty()) {
            return StirShakenParseResult.Rejected(StirShakenRejectionReason.MALFORMED_JWT)
        }

        val header = decodeObject(parts[0]) ?: return StirShakenParseResult.Rejected(StirShakenRejectionReason.MALFORMED_JWT)
        val payload = decodeObject(parts[1]) ?: return StirShakenParseResult.Rejected(StirShakenRejectionReason.MALFORMED_JWT)
        if (decodeSegment(parts[2])?.isNotEmpty() != true) {
            return StirShakenParseResult.Rejected(StirShakenRejectionReason.MALFORMED_JWT)
        }

        val typ = header.string("typ")?.lowercase()
        val algorithm = header.string("alg")?.uppercase()
        val certificateUrl = header.string("x5u")?.let(::httpsUrl)
        if (typ != "passport" || algorithm != "ES256" || certificateUrl == null) {
            return StirShakenParseResult.Rejected(StirShakenRejectionReason.UNSUPPORTED_HEADER)
        }

        val issuedAt =
            payload.long("iat")
                ?: return StirShakenParseResult.Rejected(StirShakenRejectionReason.MISSING_CLAIM)
        if (issuedAt < nowEpochSeconds - MAX_CLOCK_SKEW_SECONDS ||
            issuedAt > nowEpochSeconds + MAX_CLOCK_SKEW_SECONDS
        ) {
            return StirShakenParseResult.Rejected(StirShakenRejectionReason.STALE_IAT)
        }

        val originObject = payload.objectValue("orig")
        val destinationObject = payload.objectValue("dest")
        val origin = originObject?.string("tn")?.takeIf(::telephoneNumber)
        val destinationNumbers = destinationObject?.stringList("tn", MAX_DESTINATIONS)
        val destinationUris = destinationObject?.stringList("uri", MAX_DESTINATIONS)
        if (origin == null || destinationObject == null || destinationNumbers == null || destinationUris == null ||
            (destinationNumbers.isEmpty() && destinationUris.isEmpty())
        ) {
            return StirShakenParseResult.Rejected(StirShakenRejectionReason.MISSING_CLAIM)
        }
        if (destinationNumbers.any { !telephoneNumber(it) } || destinationUris.any { !identityUri(it) }) {
            return StirShakenParseResult.Rejected(StirShakenRejectionReason.INVALID_CLAIM)
        }

        val attestation = payload.string("attest")?.uppercase()
        if (payload.containsKey("attest") && attestation !in setOf("A", "B", "C")) {
            return StirShakenParseResult.Rejected(StirShakenRejectionReason.INVALID_CLAIM)
        }

        val origid =
            payload.string("origid")?.let { value ->
                if (value.length > MAX_ORIGID_LENGTH) null else canonicalUuid(value)
            }
        if (payload.containsKey("origid") && origid == null) {
            return StirShakenParseResult.Rejected(StirShakenRejectionReason.INVALID_CLAIM)
        }

        val mediaKeyCount =
            if (payload.containsKey("mky")) {
                val keys =
                    payload["mky"] as? List<*>
                        ?: return StirShakenParseResult.Rejected(StirShakenRejectionReason.INVALID_CLAIM)
                if (keys.size > MAX_MEDIA_KEYS) {
                    return StirShakenParseResult.Rejected(StirShakenRejectionReason.INVALID_CLAIM)
                }
                keys.size
            } else {
                0
            }

        val richCallData =
            when {
                !payload.containsKey("rcd") -> {
                    null
                }

                else -> {
                    parseRichCallData(payload.objectValue("rcd"))
                        ?: return StirShakenParseResult.Rejected(StirShakenRejectionReason.INVALID_CLAIM)
                }
            }

        return StirShakenParseResult.Accepted(
            ParsedPassport(
                typ = "passport",
                algorithm = "ES256",
                certificateUrl = certificateUrl,
                issuedAtEpochSeconds = issuedAt,
                originTelephoneNumber = origin,
                destinationTelephoneNumbers = destinationNumbers,
                destinationUris = destinationUris,
                mediaKeyCount = mediaKeyCount,
                attestation = attestation,
                origid = origid,
                richCallData = richCallData,
            ),
        )
    }

    fun readExtras(
        extras: Bundle?,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L,
    ): CarrierIdentityExtras {
        if (extras == null) return CarrierIdentityExtras()
        val passport =
            passportExtraKeys
                .asSequence()
                .mapNotNull { key -> stringExtra(extras, key) }
                .map { parse(it, nowEpochSeconds) }
                .filterIsInstance<StirShakenParseResult.Accepted>()
                .map { it.passport }
                .firstOrNull()
        val dnoStatus =
            dnoExtraKeys
                .asSequence()
                .mapNotNull { key -> stringExtra(extras, key) }
                .map(DnoStatus::fromWire)
                .firstOrNull { it != DnoStatus.UNKNOWN }
                ?: DnoStatus.UNKNOWN
        val lineType =
            lineTypeExtraKeys
                .asSequence()
                .mapNotNull { key -> stringExtra(extras, key) }
                .map(LineType::fromWire)
                .firstOrNull { it != LineType.UNKNOWN }
                ?: LineType.UNKNOWN
        return CarrierIdentityExtras(passport, dnoStatus, lineType)
    }

    private fun decodeObject(segment: String): Map<String, Any?>? =
        decodeSegment(segment)?.toString(Charsets.UTF_8)?.let { json ->
            runCatching { objectAdapter.fromJson(json) }.getOrNull()
        }

    private fun decodeSegment(segment: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(segment) }.getOrNull()

    private fun parseRichCallData(value: Map<String, Any?>?): RichCallData? {
        if (value == null) return null
        val name = value.boundedString("nam")
        val alternateNumber = value.boundedString("apn")
        val iconUrl = value.string("icn")?.let(::httpsUrl)
        if (value.containsKey("icn") && iconUrl == null) return null
        val jCardUrl = value.string("jcl")?.let(::httpsUrl)
        if (value.containsKey("jcl") && jCardUrl == null) return null
        if (value.containsKey("jcd") && value["jcd"] !is Map<*, *>) return null
        if (value.containsKey("rcdi") && value["rcdi"] !is String) return null
        if (value.keys.none { it in setOf("nam", "apn", "icn", "jcd", "jcl", "rcdi") }) return null
        return RichCallData(
            name = name,
            alternatePresentationNumber = alternateNumber,
            iconUrl = iconUrl,
            inlineJCardPresent = value.containsKey("jcd"),
            jCardUrl = jCardUrl,
        )
    }

    private fun httpsUrl(value: String): String? {
        if (value.length > MAX_CERTIFICATE_URL_LENGTH) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        return value.takeIf {
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null
        }
    }

    private fun identityUri(value: String): Boolean =
        value.length <= MAX_CLAIM_STRING_LENGTH &&
            runCatching { URI(value).scheme?.lowercase() in setOf("tel", "sip", "sips") }.getOrDefault(false)

    private fun telephoneNumber(value: String): Boolean = value.length in 3..MAX_TELEPHONE_LENGTH && value.matches(Regex("\\+?[0-9]+"))

    private fun canonicalUuid(value: String): String? = runCatching { UUID.fromString(value) }.getOrNull()?.toString()?.takeIf { it.equals(value, ignoreCase = true) }

    private fun stringExtra(
        extras: Bundle,
        key: String,
    ): String? = extras.getString(key)?.trim()?.takeIf { it.isNotEmpty() }

    private fun Map<String, Any?>.string(key: String): String? = this[key] as? String

    private fun Map<String, Any?>.boundedString(key: String): String? {
        val value = string(key) ?: return null
        return value.takeIf { it.isNotBlank() && it.length <= MAX_CLAIM_STRING_LENGTH }
    }

    private fun Map<String, Any?>.long(key: String): Long? {
        val number = this[key] as? Number ?: return null
        val value = number.toDouble()
        val integral = number.toLong()
        return integral.takeIf { value.isFinite() && value == integral.toDouble() }
    }

    private fun Map<String, Any?>.objectValue(key: String): Map<String, Any?>? {
        val value = this[key] as? Map<*, *> ?: return null
        return value.entries.associate { it.key.toString() to it.value }
    }

    private fun Map<String, Any?>.stringList(
        key: String,
        maxSize: Int,
    ): List<String>? {
        val value = this[key] ?: return emptyList()
        val values =
            when (value) {
                is String -> listOf(value)
                is List<*> -> value.map { it as? String ?: return null }
                else -> return null
            }
        return values.takeIf { it.size <= maxSize && it.all { item -> item.isNotBlank() && item.length <= MAX_CLAIM_STRING_LENGTH } }
    }
}
