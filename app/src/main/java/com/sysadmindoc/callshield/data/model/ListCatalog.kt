package com.sysadmindoc.callshield.data.model

import com.squareup.moshi.Json

/**
 * How a list kept in one country writes that country's numbers. People write
 * local numbers the local way ("3131918305", "03395051735"), and a phone in
 * another country reads those as its own numbers, so a catalog list declares
 * its plan and every row is made international before it's stored.
 */
data class ListNumberPlan(
    /** ISO 3166 country the list is kept in, such as "CO". */
    val country: String,
    /** The country's calling code without "+", such as "57". */
    val callingCode: String,
    /** The digits dialed before a national number inside the country, such as "0", or "" for none. */
    val trunkPrefix: String = "",
    /** How many digits a national number has there. */
    val nationalLengths: List<Int> = emptyList(),
) {
    /**
     * [raw] as "+" and the calling code, or null when it's already international
     * or isn't written one of the usual ways: the national number alone, with
     * the trunk prefix, with the calling code, or with "00" and the calling code.
     * One stray digit in front of the calling code is dropped, as in
     * OpenCallShield's "1573..." and "0357..." rows.
     */
    fun toInternational(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("+")) return null
        val digits = trimmed.filter { it in '0'..'9' }
        val afterTrunk = digits.takeIf { trunkPrefix.isNotEmpty() && it.startsWith(trunkPrefix) }?.removePrefix(trunkPrefix)
        val national =
            when {
                digits.startsWith("00$callingCode") -> digits.removePrefix("00$callingCode")
                isInternational(digits) -> digits.removePrefix(callingCode)
                hasStrayDigit(digits) -> digits.drop(1 + callingCode.length)
                afterTrunk != null && hasStrayDigit(afterTrunk) -> afterTrunk.drop(1 + callingCode.length)
                afterTrunk != null -> afterTrunk
                else -> digits
            }
        return "+$callingCode$national".takeIf { national.length in nationalLengths }
    }

    private fun isInternational(digits: String): Boolean = digits.startsWith(callingCode) && digits.length - callingCode.length in nationalLengths

    /** A number that already has a national length is never read as a stray digit and a calling code. */
    private fun hasStrayDigit(digits: String): Boolean = digits.length !in nationalLengths && isInternational(digits.drop(1))
}

/** A list from the signed catalog (`data/list_catalog.json`) that someone can subscribe to. */
data class ListCatalogEntry(
    val id: String,
    val name: String,
    val url: String,
    val homepage: String,
    val license: String,
    val licenseUrl: String,
    val format: String,
    val numberPlan: ListNumberPlan,
)

/** `data/list_catalog.json` as published; every field is checked before use. */
data class ListCatalogJson(
    val version: Int? = null,
    val revision: Int? = null,
    val lists: List<ListCatalogEntryJson>? = null,
)

data class ListCatalogEntryJson(
    val id: String? = null,
    val name: String? = null,
    val country: String? = null,
    @param:Json(name = "calling_code") val callingCode: String? = null,
    @param:Json(name = "trunk_prefix") val trunkPrefix: String? = null,
    @param:Json(name = "national_lengths") val nationalLengths: List<Int>? = null,
    val url: String? = null,
    val homepage: String? = null,
    val license: String? = null,
    @param:Json(name = "license_url") val licenseUrl: String? = null,
    val format: String? = null,
    @param:Json(name = "enabled_by_default") val enabledByDefault: Boolean? = null,
)
