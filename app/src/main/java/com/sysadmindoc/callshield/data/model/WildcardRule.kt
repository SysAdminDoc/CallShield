package com.sysadmindoc.callshield.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sysadmindoc.callshield.data.TimeSchedule
import com.sysadmindoc.callshield.util.filterAsciiDigits
import java.util.Calendar

/**
 * Feature 8: Wildcard/regex blocking rules.
 * Supports patterns like "832555*" or regex like "^\\+1832555\\d{4}$".
 *
 * A7 (v8→v9): carries an optional schedule. `scheduleDays == 0` means
 * "always active". See [com.sysadmindoc.callshield.data.TimeSchedule].
 */
@Entity(
    tableName = "wildcard_rules",
    indices = [Index(value = ["pattern"], unique = true)]
)
data class WildcardRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String,         // e.g., "+1832555*" or regex
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
    fun matchesNow(number: String, calendar: Calendar = Calendar.getInstance()): Boolean {
        if (!schedule.isActiveAt(calendar)) return false
        return matches(number)
    }

    fun matches(number: String): Boolean {
        val normalizedPattern = pattern.trim()
        if (normalizedPattern.isBlank()) return false
        return if (isRegex) {
            try {
                if (!isSafeRegexPattern(normalizedPattern)) return false
                val regex = Regex(normalizedPattern)
                // Try the input as-is first, then normalized forms so that
                // patterns like `^\+1832555\d{4}$` also match SMS senders that
                // arrive without the `+1` prefix. This matches the glob path
                // below; prior to v1.6.3 the two paths diverged silently —
                // users hit "why does my glob match but my regex doesn't?"
                numberVariants(number).any { regex.containsMatchIn(it) }
            } catch (_: Exception) {
                false
            }
        } else {
            // Glob-style: * matches any digits, ? matches one digit.
            // Escape ALL regex metacharacters first, then convert our globs.
            // Without this, a pattern like "212.555*" would treat '.' as
            // regex any-char and match "2120555..." unexpectedly.
            val escaped = buildString {
                for (ch in normalizedPattern) {
                    when (ch) {
                        '*' -> append("\\d*")
                        '?' -> append("\\d")
                        '+', '.', '(', ')', '[', ']', '{', '}',
                        '|', '^', '$', '\\' -> { append('\\'); append(ch) }
                        else -> append(ch)
                    }
                }
            }
            try {
                val regex = Regex("^$escaped$")
                // Try the input as-is first, then normalized forms so that
                // patterns like "+1212*" also match SMS senders that arrive
                // without the +1 prefix (e.g. "2125551234").
                numberVariants(number).any { regex.matches(it) }
            } catch (_: Exception) {
                false
            }
        }
    }

    companion object {
        /** Hard limit on regex pattern length. Phone numbers are short; any
         *  pattern longer than this is almost certainly a copy-paste mistake
         *  or a deliberate ReDoS attempt. */
        internal const val MAX_REGEX_LENGTH = 200

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
         *
         * Phone-number regex doesn't need any of these to be expressive
         * (`^\+?1?\d{10}$`-style patterns pass through fine).
         */
        internal fun isSafeRegexPattern(pattern: String): Boolean {
            if (pattern.length > MAX_REGEX_LENGTH) return false
            // Catastrophic nested quantifiers: a group whose body ends in
            // `+` / `*` / `}` (counted) followed immediately by another
            // outer quantifier.
            val nestedQuantifier = Regex("""\([^)]*[+*}]\s*\)\s*[+*?{]""")
            if (nestedQuantifier.containsMatchIn(pattern)) return false
            // Alternation inside a repeated group: `(...|...)+`
            val ambiguousAlternation = Regex("""\([^()]*\|[^()]*\)\s*[+*]""")
            if (ambiguousAlternation.containsMatchIn(pattern)) return false
            return true
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
