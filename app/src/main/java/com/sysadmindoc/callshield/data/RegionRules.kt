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
            "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "DC", "FL", "GA", "HI",
            "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD", "MA", "MI", "MN",
            "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH",
            "OK", "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA",
            "WV", "WI", "WY", "VI", "AB", "BC", "MB", "NB", "NL", "NS", "NT", "ON",
            "QC", "SK", "TF",
        )

    fun parseRegionCodes(raw: String): Set<String> =
        normalizeRegionCodes(raw.split(',', ';', '\n', '\t', ' '))

    fun normalizeRegionCodes(regions: Iterable<String>): Set<String> =
        regions
            .asSequence()
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter { it in supportedRegionCodes }
            .distinct()
            .take(MAX_ALLOWED_REGIONS)
            .toCollection(linkedSetOf())

    fun parseNamePatterns(raw: String): Set<String> =
        normalizeNamePatterns(raw.split('\n', ',', ';'))

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
        return regionCode(number) !in normalized
    }

    fun matchesPresentedName(
        presentedName: String?,
        patterns: Set<String>,
    ): Boolean {
        val name = presentedName?.trim()?.replace(WHITESPACE, " ").orEmpty()
        if (name.isEmpty()) return false
        return normalizeNamePatterns(patterns).any { pattern -> globMatches(name, pattern) }
    }

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
                else -> return false
            }
        }
        while (globIndex < glob.length && glob[globIndex] == '*') globIndex++
        return globIndex == glob.length
    }

    private val WHITESPACE = Regex("\\s+")
}
