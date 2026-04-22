package com.sysadmindoc.callshield.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sysadmindoc.callshield.data.TimeSchedule
import java.util.Calendar

/**
 * Custom SMS keyword blocking rules.
 * Messages containing any enabled keyword will be blocked.
 *
 * A7 (v8→v9): carries an optional schedule. `scheduleDays == 0` means
 * "always active". See [com.sysadmindoc.callshield.data.TimeSchedule].
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
    val enabled: Boolean = true,
    val scheduleDays: Int = 0,
    val scheduleStartHour: Int = 0,
    val scheduleEndHour: Int = 0,
) {
    val schedule: TimeSchedule
        get() = TimeSchedule(scheduleDays, scheduleStartHour, scheduleEndHour)

    /**
     * Schedule-aware match. Callers on the hot path should prefer this —
     * skips the case-fold + substring scan when the rule is inactive.
     */
    fun matchesNow(text: String, calendar: Calendar = Calendar.getInstance()): Boolean {
        if (!schedule.isActiveAt(calendar)) return false
        return matches(text)
    }

    fun matches(text: String): Boolean {
        val normalizedKeyword = keyword.trim()
        if (!enabled || normalizedKeyword.isBlank()) return false
        return if (caseSensitive) text.contains(normalizedKeyword)
        else text.lowercase().contains(normalizedKeyword.lowercase())
    }
}
