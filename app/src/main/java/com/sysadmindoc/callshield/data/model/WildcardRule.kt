package com.sysadmindoc.callshield.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sysadmindoc.callshield.data.TimeSchedule
import com.sysadmindoc.callshield.util.filterAsciiDigits
import java.util.Calendar
import kotlin.math.pow

/**
 * Feature 8: Wildcard/regex blocking rules.
 * Supports patterns like "832555*" or regex like "^\\+1832555\\d{4}$".
 *
 * A7 (v8→v9): carries an optional schedule. `scheduleDays == 0` means
 * "always active". See [com.sysadmindoc.callshield.data.TimeSchedule].
 */
@Entity(
    tableName = "wildcard_rules",
    indices = [Index(value = ["pattern"], unique = true)],
)
data class WildcardRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String, // e.g., "+1832555*" or regex
    val isRegex: Boolean = false,
    val description: String = "",
    val enabled: Boolean = true,
    val scheduleDays: Int = 0,
    val scheduleStartHour: Int = 0,
    val scheduleEndHour: Int = 0,
) {
    val schedule: TimeSchedule
        get() = TimeSchedule(scheduleDays, scheduleStartHour, scheduleEndHour)

    /**
     * Schedule-aware match. Short-circuits on the schedule before running
     * the (potentially expensive) regex compile + match.
     */
    fun matchesNow(
        number: String,
        calendar: Calendar = Calendar.getInstance(),
    ): Boolean {
        if (!schedule.isActiveAt(calendar)) return false
        return matches(number)
    }

    fun matches(number: String): Boolean {
        val normalizedPattern = pattern.trim()
        if (normalizedPattern.isBlank()) return false
        return if (isRegex) {
            val variants = numberVariants(number)
            // Measured against this input's length, since a pattern that is
            // quick on a phone number can still stall on a long SMS sender.
            if (!isSafeRegexPattern(normalizedPattern, variants.maxOf { it.length })) return false
            // Compile once per distinct pattern and reuse across screening
            // calls (see [compiledRegexFor]). Prior to this cache the hot path
            // re-ran Pattern.compile per rule per incoming call.
            val regex =
                compiledRegexFor("R", normalizedPattern) { Regex(normalizedPattern) }
                    ?: return false
            // Try the input as-is first, then normalized forms so that
            // patterns like `^\+1832555\d{4}$` also match SMS senders that
            // arrive without the `+1` prefix. This matches the glob path
            // below; prior to v1.6.3 the two paths diverged silently —
            // users hit "why does my glob match but my regex doesn't?"
            variants.any { regex.containsMatchIn(it) }
        } else {
            // Glob-style: * matches any digits, ? matches one digit.
            if (normalizedPattern.length > MAX_REGEX_LENGTH) return false
            val regex =
                compiledRegexFor("G", normalizedPattern) { Regex("^${globToRegex(normalizedPattern)}$") }
                    ?: return false
            // Try the input as-is first, then normalized forms so that
            // patterns like "+1212*" also match SMS senders that arrive
            // without the +1 prefix (e.g. "2125551234").
            numberVariants(number).any { regex.matches(it) }
        }
    }

    companion object {
        /** Hard limit on regex pattern length. Phone numbers are short; any
         *  pattern longer than this is almost certainly a copy-paste mistake
         *  or a deliberate ReDoS attempt. */
        internal const val MAX_REGEX_LENGTH = 200

        /** A real phone-number regex never needs many open-ended repeats;
         *  more than this is a ReDoS shape, not an expressive pattern. */
        internal const val MAX_UNBOUNDED_QUANTIFIERS = 8

        /**
         * The add-rule dialog measures a pattern at this length: E.164's 15
         * digits and a plus sign, with room for the spaces, dashes and
         * brackets of a formatted caller ID like `+1 (212) 555-1234`.
         */
        internal const val CALLER_ID_LENGTH = 24

        /**
         * Most ways the regex engine may try to match before giving up (see
         * [BacktrackingPaths]). Android's Matcher hands ICU a copy of the input
         * and a running ICU match can't be stopped, so a pattern is measured
         * before it runs rather than timed while it runs.
         */
        internal const val MAX_BACKTRACKING_PATHS = 1_000_000.0

        /** Runs of consecutive glob `*`, collapsed to one before compilation. */
        private val consecutiveStars = Regex("""\*+""")

        /**
         * Compiled-pattern cache shared across all rule instances. Keyed by
         * `"$kind:$pattern"` so glob and regex forms of the same literal never
         * collide. Rule entities are recreated on every rule-cache reload, so a
         * per-entity `lazy` would recompile on each invalidation anyway; a
         * process-lifetime map keyed by the pattern text is both simpler and
         * strictly better — a given pattern compiles exactly once. Bounded by
         * the number of distinct patterns (dozens), so no eviction is needed.
         */
        private val compiledRegexCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

        /** Memoized compile. Returns null (and does not cache) if [build] throws. */
        private inline fun compiledRegexFor(
            kind: String,
            pattern: String,
            build: () -> Regex,
        ): Regex? {
            val key = "$kind:$pattern"
            compiledRegexCache[key]?.let { return it }
            return try {
                build().also { compiledRegexCache[key] = it }
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Convert a glob pattern (`*` = any digits, `?` = one digit) to a regex
         * body, escaping all other regex metacharacters. Runs of consecutive
         * `*` are collapsed first: `\d*\d*` is semantically identical to `\d*`
         * but compiles to a catastrophic-backtracking shape (a `**********5`
         * pattern measured 60+ s). Escaping metacharacters first stops
         * `212.555*` from treating `.` as regex any-char.
         */
        internal fun globToRegex(pattern: String): String {
            val collapsed = pattern.replace(consecutiveStars, "*")
            return buildString {
                for (ch in collapsed) {
                    when (ch) {
                        '*' -> {
                            append("\\d*")
                        }

                        '?' -> {
                            append("\\d")
                        }

                        '+', '.', '(', ')', '[', ']', '{', '}',
                        '|', '^', '$', '\\',
                        -> {
                            append('\\')
                            append(ch)
                        }

                        else -> {
                            append(ch)
                        }
                    }
                }
            }
        }

        /** Open-ended repeats: `*`, `+`, and `{n,}` (but not bounded `{n,m}`). */
        private val unboundedQuantifier = Regex("""[*+]|\{\d*,\}""")

        /**
         * Reject regex patterns that are known catastrophic-backtracking
         * shapes. This is a coarse-grained heuristic — not a full ReDoS
         * analyzer — but it catches the common offenders we don't want
         * running on the call-screening hot path.
         *
         * Rejected:
         *  - patterns longer than [MAX_REGEX_LENGTH]
         *  - nested quantifiers like `(a+)+`, `(a*)+`, `(a+)*` — the classic
         *    catastrophic-backtracking trigger
         *  - alternation inside a repeated group like `(a|aa)+` — same family
         *  - anything with more than [MAX_BACKTRACKING_PATHS] ways to try an
         *    input of [inputLength] characters, like forty `\d?` in a row or
         *    `(?:[A-Z]+\s?)+`, which the shape checks miss
         *
         * Phone-number regex doesn't need any of these to be expressive
         * (`^\+?1?\d{10}$`-style patterns pass through fine).
         */
        internal fun isSafeRegexPattern(
            pattern: String,
            inputLength: Int = CALLER_ID_LENGTH,
        ): Boolean {
            if (pattern.length > MAX_REGEX_LENGTH) return false
            // The path count below would read quoted text (`\Q...\E`) as
            // syntax, so a quoted `[` could hide every quantifier after it.
            if (pattern.contains("\\Q")) return false
            // Catastrophic nested quantifiers: a group whose body ends in
            // `+` / `*` / `}` (counted) followed immediately by another
            // outer quantifier.
            val nestedQuantifier = Regex("""\([^)]*[+*}]\s*\)\s*[+*?{]""")
            if (nestedQuantifier.containsMatchIn(pattern)) return false
            // Alternation inside a repeated group: `(...|...)+`
            val ambiguousAlternation = Regex("""\([^()]*\|[^()]*\)\s*[+*]""")
            if (ambiguousAlternation.containsMatchIn(pattern)) return false
            // Too many unbounded quantifiers. A chain of `\d*\d*…\d*a` has no
            // groups (so the two heuristics above miss it) but still backtracks
            // combinatorially. A real phone regex needs at most one or two.
            if (unboundedQuantifier.findAll(pattern).count() > MAX_UNBOUNDED_QUANTIFIERS) return false
            return BacktrackingPaths(pattern, inputLength).count() <= MAX_BACKTRACKING_PATHS
        }

        /** Generate common US number normalizations so wildcard globs match
         *  regardless of whether the input has a +1 prefix or not. */
        internal fun numberVariants(number: String): List<String> {
            val digits = filterAsciiDigits(number)
            return buildList {
                add(number)
                if (digits != number) add(digits) // raw digits without punctuation
                if (digits.length == 10) {
                    add("+1$digits")
                    add("1$digits")
                }
                if (digits.length == 11 && digits.startsWith("1")) {
                    add("+$digits")
                    add(digits.drop(1))
                }
            }.distinct()
        }
    }
}

/**
 * An upper bound on the ways a backtracking engine can walk [pattern] over
 * an input of [inputLength] characters. A sequence multiplies the ways of
 * its parts and an alternation adds its branches. An atom with p ways
 * repeated k times has p^k, summed over every k its quantifier allows, and
 * an unanchored pattern is tried from every start. It ignores which
 * characters can match, so it overstates the real work and only refuses
 * shapes that could stall.
 */
private class BacktrackingPaths(
    private val pattern: String,
    private val inputLength: Int,
) {
    private var at = 0

    fun count(): Double {
        var paths = 0.0
        var branches = 0
        while (true) {
            paths += sequence()
            branches++
            if (at >= pattern.length) break
            at++ // a top-level `|`, or a `)` with no group to close
        }
        val anchored = branches == 1 && (pattern.startsWith("^") || pattern.startsWith("\\A"))
        return if (anchored) paths else paths * (inputLength + 1)
    }

    private fun alternation(): Double {
        var paths = sequence()
        while (pattern.getOrNull(at) == '|') {
            at++
            paths += sequence()
        }
        return paths
    }

    private fun sequence(): Double {
        var paths = 1.0
        while (at < pattern.length && pattern[at] != '|' && pattern[at] != ')') {
            paths *= repeated(atom())
        }
        return paths
    }

    private fun atom(): Double {
        when (pattern[at]) {
            '(' -> {
                at++
                skipGroupPrefix()
                val inner = alternation()
                if (at < pattern.length) at++
                return inner
            }

            '[' -> {
                skipClass()
            }

            '\\' -> {
                at = minOf(at + 2, pattern.length)
            }

            else -> {
                at++
            }
        }
        return 1.0
    }

    /** Steps over `?:`, `?=`, `?!`, `?>`, `?<=`, `?<!`, `?<name>` and inline flags like `?i`. */
    private fun skipGroupPrefix() {
        if (pattern.getOrNull(at) != '?') return
        at++
        when (pattern.getOrNull(at)) {
            ':', '=', '!', '>' -> {
                at++
            }

            '<' -> {
                at =
                    when (pattern.getOrNull(at + 1)) {
                        '=', '!' -> at + 2
                        else -> pattern.indexOf('>', at).let { if (it < 0) pattern.length else it + 1 }
                    }
            }

            else -> {
                while (pattern.getOrNull(at)?.let { it.isLetter() || it == '-' } == true) at++
                if (pattern.getOrNull(at) == ':') at++
            }
        }
    }

    /** Steps over a `[...]` class, nested classes and escapes included. */
    private fun skipClass() {
        var depth = 0
        while (at < pattern.length) {
            when (pattern[at]) {
                '\\' -> {
                    at++
                }

                '[' -> {
                    depth++
                }

                ']' -> {
                    if (--depth == 0) {
                        at++
                        return
                    }
                }
            }
            at++
        }
    }

    private fun repeated(atom: Double): Double {
        val (least, most) = quantifier() ?: return atom
        // A lazy `?` or possessive `+` after it changes the order, not the count.
        if (pattern.getOrNull(at) == '?' || pattern.getOrNull(at) == '+') at++
        // An open-ended repeat makes one pass per character at most, since the
        // engine stops looping once a pass matches nothing. A counted one can
        // make every pass on nothing, as `(?:\d?){30}` does.
        val highest = if (most == Int.MAX_VALUE) maxOf(least, inputLength) else most
        var power = atom.pow(least)
        var paths = 0.0
        repeat(highest - least + 1) {
            paths += power
            if (paths > WildcardRule.MAX_BACKTRACKING_PATHS) return paths
            power *= atom
        }
        return paths
    }

    /** Reads `?`, `*`, `+`, `{n}`, `{n,}` or `{n,m}` as the repeat counts it allows. */
    private fun quantifier(): Pair<Int, Int>? {
        val range =
            when (pattern.getOrNull(at)) {
                '?' -> 0 to 1
                '*' -> 0 to Int.MAX_VALUE
                '+' -> 1 to Int.MAX_VALUE
                '{' -> return braces()
                else -> return null
            }
        at++
        return range
    }

    private fun braces(): Pair<Int, Int>? {
        val close = pattern.indexOf('}', at)
        if (close < 0) return null
        val bounds = pattern.substring(at + 1, close).split(',')
        val least = bounds[0].toIntOrNull() ?: return null
        val most =
            when {
                bounds.size == 1 -> least
                bounds.size == 2 && bounds[1].isEmpty() -> Int.MAX_VALUE
                bounds.size == 2 -> bounds[1].toIntOrNull() ?: return null
                else -> return null
            }
        at = close + 1
        return least to most
    }
}
