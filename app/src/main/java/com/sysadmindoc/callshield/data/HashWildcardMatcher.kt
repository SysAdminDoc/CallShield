package com.sysadmindoc.callshield.data

/**
 * Length-locked `#` wildcard matcher for phone numbers.
 *
 * Patterns look like `+33162######` — every `#` matches exactly one
 * digit, every other character matches itself. Pattern length must equal
 * number length for a match; there is no `*` or "match the rest".
 *
 * Borrowed from Saracroche (codeberg.org/cbouvat/saracroche-android,
 * `util/PhoneNumberMatcher.kt`). Benefits vs regex:
 *
 * - **No regex JIT cost** — pure character-index loop, ~20 ns per match.
 * - **No ReDoS surface** — impossible to construct a pathological pattern.
 * - **Trivially auditable** — users can read their own patterns.
 *
 * ## Input normalization
 *
 * Most real-world users store numbers as `0612345678` while incoming
 * calls arrive as `+33612345678` (or vice versa). [matchesWithVariants]
 * generates plausible country-prefix variants for the number and tries
 * each against the pattern, so a pattern like `+33612######` also
 * matches `0612345678` without the user needing to write two patterns.
 *
 * ## Pattern coverage
 *
 * [coveredNumberCount] reports `10^(# count)` — the number of distinct
 * numbers the pattern covers. Used in the rule-edit UI to warn users
 * that `+########` covers a billion numbers.
 *
 * [coversOrCoveredBy] detects rule overlap — when the user is about to
 * add a pattern that's already covered by (or covers) an existing rule.
 */
object HashWildcardMatcher {

    /**
     * `true` iff [pattern] matches [number] exactly.
     *
     * - Same length required.
     * - `#` matches any single digit.
     * - Any other character must equal the corresponding character in
     *   [number] byte-for-byte (so `+` matches only `+`).
     *
     * Returns `false` on an invalid pattern (empty) or number (empty)
     * rather than throwing — callers are on the hot path.
     */
    fun matches(pattern: String, number: String): Boolean {
        if (pattern.isEmpty() || number.isEmpty()) return false
        if (pattern.length != number.length) return false
        for (i in pattern.indices) {
            val p = pattern[i]
            val n = number[i]
            if (p == '#') {
                if (!n.isDigit()) return false
            } else {
                if (p != n) return false
            }
        }
        return true
    }

    /**
     * Try [pattern] against [number] and several common variants. Returns
     * `true` if any variant matches.
     *
     * Generated variants:
     *   - The number as-is.
     *   - The digits only (strip non-digits, keep or drop leading `+`).
     *   - `+1` prepended if number has 10 digits (NANP).
     *   - `+` prepended if number has 11 digits and starts with `1`.
     *   - Leading `+CC` swapped for `0` and vice versa (crude but works
     *     for most European "national format" cases).
     *
     * @param countryPrefixes optional list of known country prefixes
     *        (e.g. `["+33", "+44"]`) — when present, used to generate
     *        `0...` ↔ `+CC...` swaps.
     */
    fun matchesWithVariants(
        pattern: String,
        number: String,
        countryPrefixes: List<String> = DEFAULT_COUNTRY_PREFIXES,
    ): Boolean {
        return numberVariants(number, countryPrefixes).any { matches(pattern, it) }
    }

    /**
     * Common North American / European prefixes. Callers can override with
     * a narrower list when they know the device's locale.
     */
    val DEFAULT_COUNTRY_PREFIXES: List<String> = listOf(
        "+1",   // NANP (US, Canada)
        "+33",  // France
        "+44",  // UK
        "+49",  // Germany
        "+34",  // Spain
        "+39",  // Italy
        "+31",  // Netherlands
        "+32",  // Belgium
        "+41",  // Switzerland
        "+43",  // Austria
        "+46",  // Sweden
        "+47",  // Norway
        "+45",  // Denmark
        "+358", // Finland
        "+351", // Portugal
        "+353", // Ireland
        "+52",  // Mexico
        "+55",  // Brazil
        "+61",  // Australia
        "+64",  // NZ
    )

    /**
     * Generate plausible normalization variants for [number].
     *
     * Intended for matching against user-entered patterns — if the user
     * wrote `+33612######` and the incoming number is `0612345678`,
     * we want that to match.
     */
    internal fun numberVariants(
        number: String,
        countryPrefixes: List<String>,
    ): List<String> {
        val trimmed = number.trim()
        if (trimmed.isEmpty()) return emptyList()
        val digits = trimmed.filter { it.isDigit() }

        val variants = LinkedHashSet<String>()
        variants.add(trimmed)
        if (digits != trimmed) variants.add(digits)

        // NANP shortcut
        if (digits.length == 10) {
            variants.add("+1$digits")
            variants.add("1$digits")
        }
        if (digits.length == 11 && digits.startsWith("1")) {
            variants.add("+$digits")
            variants.add(digits.drop(1))
        }

        // Country-prefix swap: if the number starts with +CC, also try
        // "0<national>"; if it starts with 0, also try each known +CC.
        // Sort prefixes by length descending so "+358" wins over "+35".
        val sortedPrefixes = countryPrefixes.sortedByDescending { it.length }
        for (cc in sortedPrefixes) {
            if (trimmed.startsWith(cc)) {
                val national = "0" + trimmed.drop(cc.length)
                variants.add(national)
            }
        }
        if (trimmed.startsWith("0") && trimmed.length > 1) {
            val national = trimmed.drop(1)
            for (cc in sortedPrefixes) {
                variants.add(cc + national)
            }
        }
        return variants.toList()
    }

    /**
     * How many distinct numbers this pattern covers. A pattern with N
     * `#` characters covers 10^N numbers.
     *
     * Used in the rule-edit UI to warn the user about over-broad rules.
     * Returns [Long.MAX_VALUE] if the count would overflow a Long
     * (patterns with 20+ `#`s — practically impossible for real phone
     * numbers but worth handling defensively).
     */
    fun coveredNumberCount(pattern: String): Long {
        val hashes = pattern.count { it == '#' }
        if (hashes <= 0) return 1L
        if (hashes >= 19) return Long.MAX_VALUE  // 10^19 overflows Long
        var count = 1L
        repeat(hashes) { count *= 10L }
        return count
    }

    /**
     * Detect overlap between two patterns.
     *
     * Returns one of:
     *   - [Overlap.NONE] — no relationship (different prefix or length)
     *   - [Overlap.EQUAL] — patterns match exactly the same set
     *   - [Overlap.A_COVERS_B] — every number matching B also matches A
     *   - [Overlap.B_COVERS_A] — every number matching A also matches B
     *
     * Only patterns of the same length can have any relationship — `#`
     * matches exactly one digit so length is the coverage key.
     *
     * Runs in O(pattern length). Used in the rule-edit UI to flag
     * redundant or conflicting user rules.
     */
    fun coversOrCoveredBy(a: String, b: String): Overlap {
        if (a.length != b.length || a.isEmpty()) return Overlap.NONE

        var aCoversB = true
        var bCoversA = true
        for (i in a.indices) {
            val ca = a[i]
            val cb = b[i]
            when {
                ca == cb -> Unit                                    // position agrees
                ca == '#' && cb.isDigit() -> bCoversA = false       // A wider at this pos
                cb == '#' && ca.isDigit() -> aCoversB = false       // B wider at this pos
                else -> return Overlap.NONE                         // concrete mismatch
            }
            if (!aCoversB && !bCoversA) return Overlap.NONE
        }
        return when {
            aCoversB && bCoversA -> Overlap.EQUAL
            aCoversB -> Overlap.A_COVERS_B
            bCoversA -> Overlap.B_COVERS_A
            else -> Overlap.NONE
        }
    }

    enum class Overlap { NONE, EQUAL, A_COVERS_B, B_COVERS_A }
}
