package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup
import java.util.Locale

/** Pure, bounded matching helpers for the regional and carrier-name rules. */
object RegionRules {
    const val MAX_ALLOWED_REGIONS = 64
    const val MAX_NAME_PATTERNS = 30
    const val MAX_NAME_PATTERN_LENGTH = 60

    val supportedRegionCodes: Set<String> =
        setOf(
            "AL",
            "AK",
            "AZ",
            "AR",
            "CA",
            "CO",
            "CT",
            "DE",
            "DC",
            "FL",
            "GA",
            "HI",
            "ID",
            "IL",
            "IN",
            "IA",
            "KS",
            "KY",
            "LA",
            "ME",
            "MD",
            "MA",
            "MI",
            "MN",
            "MS",
            "MO",
            "MT",
            "NE",
            "NV",
            "NH",
            "NJ",
            "NM",
            "NY",
            "NC",
            "ND",
            "OH",
            "OK",
            "OR",
            "PA",
            "RI",
            "SC",
            "SD",
            "TN",
            "TX",
            "UT",
            "VT",
            "VA",
            "WA",
            "WV",
            "WI",
            "WY",
            "VI",
            "AB",
            "BC",
            "MB",
            "NB",
            "NL",
            "NS",
            "NT",
            "ON",
            "QC",
            "SK",
            "TF",
        )

    private val countryCallingCodes: Map<String, String> =
        mapOf(
            "AF" to "+93", "AL" to "+355", "DZ" to "+213", "AR" to "+54",
            "AU" to "+61", "AT" to "+43", "BD" to "+880", "BE" to "+32",
            "BR" to "+55", "BG" to "+359", "KH" to "+855", "CM" to "+237",
            "CL" to "+56", "CN" to "+86", "CO" to "+57", "HR" to "+385",
            "CZ" to "+420", "DK" to "+45", "EG" to "+20", "FI" to "+358",
            "FR" to "+33", "DE" to "+49", "GH" to "+233", "GR" to "+30",
            "HK" to "+852", "HU" to "+36", "IN" to "+91", "ID" to "+62",
            "IR" to "+98", "IQ" to "+964", "IE" to "+353", "IL" to "+972",
            "IT" to "+39", "JP" to "+81", "JO" to "+962", "KE" to "+254",
            "KR" to "+82", "KW" to "+965", "MY" to "+60", "MX" to "+52",
            "MA" to "+212", "NL" to "+31", "NZ" to "+64", "NG" to "+234",
            "NO" to "+47", "PK" to "+92", "PE" to "+51", "PH" to "+63",
            "PL" to "+48", "PT" to "+351", "RO" to "+40", "RU" to "+7",
            "SA" to "+966", "SG" to "+65", "ZA" to "+27", "ES" to "+34",
            "SE" to "+46", "CH" to "+41", "TW" to "+886", "TH" to "+66",
            "TR" to "+90", "UA" to "+380", "AE" to "+971", "GB" to "+44",
            "VN" to "+84", "EC" to "+593", "VE" to "+58", "UY" to "+598",
            "PY" to "+595", "BO" to "+591", "PA" to "+507", "CR" to "+506",
            "GT" to "+502", "HN" to "+504", "SV" to "+503", "NI" to "+505",
            "DO" to "+1809", "JM" to "+1876", "TT" to "+1868",
        )

    fun countryCallingCodeFor(isoCode: String): String? = countryCallingCodes[isoCode.uppercase(Locale.ROOT)]

    val supportedCountryCodes: Set<String> = countryCallingCodes.keys

    fun parseRegionCodes(raw: String): Set<String> = normalizeRegionCodes(raw.split(',', ';', '\n', '\t', ' '))

    fun normalizeRegionCodes(regions: Iterable<String>): Set<String> =
        regions
            .asSequence()
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter { it in supportedRegionCodes || it in supportedCountryCodes }
            .distinct()
            .take(MAX_ALLOWED_REGIONS)
            .toCollection(linkedSetOf())

    fun parseNamePatterns(raw: String): Set<String> = normalizeNamePatterns(raw.split('\n', ',', ';'))

    fun normalizeNamePatterns(patterns: Iterable<String>): Set<String> =
        patterns
            .asSequence()
            .map { it.trim().replace(WHITESPACE, " ") }
            .filter { it.isNotBlank() }
            .map { it.take(MAX_NAME_PATTERN_LENGTH) }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(MAX_NAME_PATTERNS)
            .toCollection(linkedSetOf())

    fun regionCode(number: String): String? = AreaCodeLookup.getRegionCode(number)

    fun isOutsideAllowedRegions(
        number: String,
        allowedRegions: Set<String>,
    ): Boolean {
        val normalized = normalizeRegionCodes(allowedRegions)
        if (normalized.isEmpty()) return false
        val nanpRegion = regionCode(number)
        if (nanpRegion != null && nanpRegion in normalized) return false
        for (code in normalized) {
            val prefix = countryCallingCodeFor(code) ?: continue
            if (number.startsWith(prefix)) return false
        }
        return true
    }

    fun matchesPresentedName(
        presentedName: String?,
        patterns: Set<String>,
    ): Boolean {
        val name = normalizePresentedName(presentedName)
        if (name.isEmpty()) return false
        return normalizeNamePatterns(patterns).any { pattern -> globMatches(name, pattern) }
    }

    fun normalizePresentedName(presentedName: String?): String =
        presentedName
            ?.trim()
            ?.replace(WHITESPACE, " ")
            ?.take(MAX_NAME_PATTERN_LENGTH)
            .orEmpty()

    /** Case-insensitive `*`/`?` glob matching without regex backtracking. */
    private fun globMatches(
        value: String,
        pattern: String,
    ): Boolean {
        val text = value.lowercase(Locale.ROOT)
        val glob = pattern.lowercase(Locale.ROOT)
        var textIndex = 0
        var globIndex = 0
        var starIndex = -1
        var retryTextIndex = -1
        while (textIndex < text.length) {
            when {
                globIndex < glob.length && (glob[globIndex] == '?' || glob[globIndex] == text[textIndex]) -> {
                    textIndex++
                    globIndex++
                }

                globIndex < glob.length && glob[globIndex] == '*' -> {
                    starIndex = globIndex++
                    retryTextIndex = textIndex
                }

                starIndex >= 0 -> {
                    globIndex = starIndex + 1
                    textIndex = ++retryTextIndex
                }

                else -> {
                    return false
                }
            }
        }
        while (globIndex < glob.length && glob[globIndex] == '*') globIndex++
        return globIndex == glob.length
    }

    private val WHITESPACE = Regex("\\s+")
}
