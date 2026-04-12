package com.sysadmindoc.callshield.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Feature 8: Wildcard/regex blocking rules.
 * Supports patterns like "832555*" or regex like "^\\+1832555\\d{4}$"
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
    val enabled: Boolean = true
) {
    fun matches(number: String): Boolean {
        val normalizedPattern = pattern.trim()
        if (normalizedPattern.isBlank()) return false
        return if (isRegex) {
            try {
                Regex(normalizedPattern).containsMatchIn(number)
            } catch (_: Exception) {
                false
            }
        } else {
            // Glob-style: * matches any digits
            val regexPattern = normalizedPattern
                .replace("+", "\\+")
                .replace("*", "\\d*")
                .replace("?", "\\d")
            try {
                val regex = Regex("^$regexPattern$")
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
