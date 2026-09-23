package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup
import com.sysadmindoc.callshield.util.countryCallingCodeOf
import com.sysadmindoc.callshield.util.filterAsciiDigits
import com.sysadmindoc.callshield.util.isAsciiDigit
import java.util.Locale

/** Pure, bounded matching helpers for the regional and carrier-name rules. */
object RegionRules {
    const val MAX_ALLOWED_REGIONS = 64
    const val MAX_NAME_PATTERNS = 30
    const val MAX_NAME_PATTERN_LENGTH = 60
    private const val NANP_NATIONAL_LENGTH = 10
    private const val NANP_CALLING_CODE = "1"

    /** `1` plus a three-digit area code, as in `+1809`. */
    private const val NANP_AREA_CODE_ENTRY_LENGTH = 4

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

    // "+1 809", "+1 (809)" and "+1-809" name one area code; join them before splitting.
    fun parseRegionCodes(raw: String): Set<String> = normalizeRegionCodes(raw.replace(SPACED_NANP_AREA_CODE, "+1$1").split(',', ';', '\n', '\t', ' '))

    fun normalizeRegionCodes(regions: Iterable<String>): Set<String> =
        regions
            .asSequence()
            .map { it.trim().uppercase(Locale.ROOT) }
            .mapNotNull { code -> code.takeIf { it in supportedRegionCodes } ?: normalizeDialingCode(code) }
            .distinct()
            .take(MAX_ALLOWED_REGIONS)
            .toCollection(linkedSetOf())

    /**
     * A country is allowed by its calling code (`+57`), never by a two-letter
     * ISO code: CO, IN, PA, SK and many more ISO codes are also US state or
     * Canadian province codes, and a two-letter entry keeps meaning the state
     * or province. A Caribbean country that shares +1 is listed by its area
     * codes (`+1809`, `+1829`, `+1849`). A bare `+1` is refused: it would
     * allow all of North America, and it is what's left when "+1 (809)" or a
     * pasted number splits apart. Returns [code] when it is one of the
     * accepted forms.
     */
    fun normalizeDialingCode(code: String): String? {
        if (!code.startsWith("+")) return null
        val digits = code.substring(1)
        if (digits.isEmpty() || digits == "1" || digits[0] == '0' || !digits.all { it.isAsciiDigit() }) return null
        val isNanpAreaCode = digits.length == NANP_AREA_CODE_ENTRY_LENGTH && digits[0] == '1' && digits[1] in '2'..'9'
        return code.takeIf { isNanpAreaCode || countryCallingCodeOf(digits) == digits }
    }

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

    /**
     * The NANP state, province or territory code of [number], or null. A `+`
     * number outside +1 is never NANP, even when it has ten digits: Penang's
     * +60 4 would otherwise read as Vancouver's 604. A bare number is NANP
     * only on a phone whose home region uses +1 or is unknown.
     */
    fun regionCode(
        number: String,
        homeRegionIso: String? = null,
    ): String? =
        when {
            number.startsWith("+") && !number.startsWith("+1") -> null
            !number.startsWith("+") && !homeUsesNanp(homeRegionIso) -> null
            else -> AreaCodeLookup.getRegionCode(number)
        }

    fun isOutsideAllowedRegions(
        number: String,
        allowedRegions: Set<String>,
        homeRegionIso: String? = null,
    ): Boolean {
        val normalized = normalizeRegionCodes(allowedRegions)
        if (normalized.isEmpty()) return false
        val nanpRegion = regionCode(number, homeRegionIso)
        if (nanpRegion != null && nanpRegion in normalized) return false
        val international = internationalForm(number, homeRegionIso) ?: return true
        return normalized.none { it.startsWith("+") && international.startsWith(it) }
    }

    /**
     * [number] as `+<digits>`. Android leaves a number it can't validate in
     * national form, so a bare number is read in the phone's home region.
     * One that starts with that region's international prefix (`00`, `011`,
     * `810`...) is already international. Otherwise it's ten or eleven digits
     * as NANP where the region uses +1 (or is unknown), and gets the home
     * region's calling code anywhere else.
     */
    private fun internationalForm(
        number: String,
        homeRegionIso: String?,
    ): String? {
        if (number.startsWith("+")) return number
        val digits = filterAsciiDigits(number)
        RegionCallingCodes.afterInternationalPrefix(digits, homeRegionIso)?.let { return "+$it" }
        val homeCode = RegionCallingCodes.forRegion(homeRegionIso)
        if (homeCode != null && homeCode != NANP_CALLING_CODE) return digits.takeIf { it.isNotEmpty() }?.let { "+$homeCode$it" }
        return when {
            digits.length == NANP_NATIONAL_LENGTH -> "+1$digits"
            digits.length == NANP_NATIONAL_LENGTH + 1 && digits.startsWith("1") -> "+$digits"
            else -> null
        }
    }

    private fun homeUsesNanp(homeRegionIso: String?): Boolean = RegionCallingCodes.forRegion(homeRegionIso)?.let { it == NANP_CALLING_CODE } ?: true

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
    private val SPACED_NANP_AREA_CODE = Regex("""\+1[ \t().-]*([2-9][0-9]{2})\)?""")
}
