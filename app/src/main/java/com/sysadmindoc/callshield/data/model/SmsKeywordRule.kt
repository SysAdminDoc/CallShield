package com.sysadmindoc.callshield.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Custom SMS keyword blocking rules.
 * Messages containing any enabled keyword will be blocked.
 */
@Entity(
    tableName = "sms_keyword_rules",
    indices = [Index(value = ["keyword"], unique = true)]
)
data class SmsKeywordRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyword: String,
    val caseSensitive: Boolean = false,
    val description: String = "",
    val enabled: Boolean = true
) {
    fun matches(text: String): Boolean {
        if (!enabled) return false
        return if (caseSensitive) text.contains(keyword)
        else text.lowercase().contains(keyword.lowercase())
    }
}
