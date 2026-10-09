package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup
import com.sysadmindoc.callshield.util.filterAsciiDigits
import java.util.Locale

/**
 * Which country a caller dialed from, when it isn't the phone's own. Carriers
 * label these ("Unverified: Overseas Call"), and a one-ring scam from the
 * Caribbean looks like any other +1 number until someone says where it is.
 * Only a label: verdicts never read it.
 */
internal object CallerCountry {
    /**
     * The ISO country [number] came from when that's not [homeRegionIso]'s, or
     * null: a number without a "+", one too short or long to be a phone line,
     * an unknown home region, or a call from home. North America shares +1,
     * so there the area code says which country it is, and the US counts its
     * territories as home. A toll-free or unlisted area code says nothing, so
     * it isn't labeled. Russia and Kazakhstan share +7, and a 7 after it is
     * Kazakhstan. Elsewhere a calling code shared by several regions (+44 for
     * the UK, Jersey, Guernsey and the Isle of Man) is one home.
     */
    fun abroad(
        number: String,
        homeRegionIso: String?,
    ): String? {
        val home = homeRegionIso?.uppercase(Locale.ROOT) ?: return null
        val homeCode = RegionCallingCodes.forRegion(home) ?: return null
        if (!number.trimStart().startsWith("+")) return null
        val digits = filterAsciiDigits(number)
        if (digits.length !in E164_DIGITS) return null
        val code = RegionCallingCodes.callingCodeOf(digits) ?: return null
        if (code == NANP_CODE) {
            if (digits.length != NANP_DIGITS) return null
            val caller = nanpCountry(digits.substring(1, 4), number) ?: return null
            return caller.takeIf { homeCode != NANP_CODE || it != nanpGroup(home) }
        }
        if (code == RU_KZ_CODE) {
            val caller = if (digits.getOrNull(1) == '7') "KZ" else "RU"
            return caller.takeIf { it != home }
        }
        if (code == homeCode) return null
        return RegionCallingCodes.mainRegionFor(code)
    }

    /** [iso]'s name in [locale], such as "Jamaica". */
    fun displayName(
        iso: String,
        locale: Locale = Locale.getDefault(),
    ): String =
        Locale
            .Builder()
            .setRegion(iso)
            .build()
            .getDisplayCountry(locale)
            .ifBlank { iso }

    /** Null for a toll-free code or one the table doesn't list: Canada and the US share both. */
    private fun nanpCountry(
        areaCode: String,
        number: String,
    ): String? {
        CARIBBEAN_AREA_CODES[areaCode]?.let { return it }
        val region = AreaCodeLookup.getRegionCode(number)
        return when (region) {
            null, TOLL_FREE -> null
            in CANADIAN_PROVINCES -> "CA"
            else -> US
        }
    }

    /** The US and its territories dial each other as home. */
    private fun nanpGroup(home: String): String = if (home in US_TERRITORIES) US else home

    private const val NANP_CODE = "1"
    private const val RU_KZ_CODE = "7"
    private const val TOLL_FREE = "TF"
    private const val NANP_DIGITS = 11
    private const val US = "US"
    private val E164_DIGITS = 8..15
    private val US_TERRITORIES = setOf("US", "PR", "VI", "GU", "AS", "MP")
    private val CANADIAN_PROVINCES = setOf("AB", "BC", "MB", "NB", "NL", "NS", "NT", "NU", "ON", "PE", "QC", "SK", "YT")

    /** NANP members outside the US and Canada, by area code (NANPA). */
    private val CARIBBEAN_AREA_CODES =
        mapOf(
            "242" to "BS",
            "246" to "BB",
            "264" to "AI",
            "268" to "AG",
            "284" to "VG",
            "345" to "KY",
            "441" to "BM",
            "473" to "GD",
            "649" to "TC",
            "658" to "JM",
            "664" to "MS",
            "721" to "SX",
            "758" to "LC",
            "767" to "DM",
            "784" to "VC",
            "809" to "DO",
            "829" to "DO",
            "849" to "DO",
            "868" to "TT",
            "869" to "KN",
            "876" to "JM",
        )
}
