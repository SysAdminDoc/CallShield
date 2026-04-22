package com.sysadmindoc.callshield.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sysadmindoc.callshield.data.TimeSchedule
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
                // Guard against ReDoS: reject overly complex patterns.
                // Phone numbers are short (<20 chars) which limits exposure,
                // but pathological patterns can still hang the call screening path.
                if (normalizedPattern.length > 200) return false
                Regex(normalizedPattern).containsMatchIn(number)
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
        /** Generate common US number normalizations so wildcard globs match
         *  regardless of whether the input has a +1 prefix or not. */
        internal fun numberVariants(number: String): List<String> {
            val digits = number.filter { it.isDigit() }
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
