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
                Regex("^$regexPattern$").matches(number)
            } catch (_: Exception) {
                false
            }
        }
    }
}
