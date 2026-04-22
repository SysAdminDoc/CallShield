package com.sysadmindoc.callshield.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sysadmindoc.callshield.data.HashWildcardMatcher
import com.sysadmindoc.callshield.data.TimeSchedule
import java.util.Calendar

/**
 * Length-locked `#` wildcard rule (Saracroche-style).
 *
 * Patterns like `+33162######` match every 11-digit number starting with
 * `+33162`. Unlike [WildcardRule] (which supports `*` / `?` / regex), these
 * patterns are length-locked and regex-free — see [HashWildcardMatcher]
 * for rationale (perf, safety, auditability).
 *
 * Lives in its own table so users can reason about hash-patterns and
 * legacy glob/regex patterns independently — they have different
 * semantics (length-locked vs variable-length) and different UX in the
 * rule-edit screen.
 *
 * Introduced by DB migration v6→v7.
 * Schedule gating (A7) added in v7→v8 — three plain columns with
 * `scheduleDays == 0` meaning "no gating" (always active). See
 * [com.sysadmindoc.callshield.data.TimeSchedule].
 */
@Entity(
    tableName = "hash_wildcard_rules",
    indices = [Index(value = ["pattern"], unique = true)],
)
data class HashWildcardRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Pattern: non-digit characters (e.g. `+`) match literally, `#` matches any single digit. */
    val pattern: String,
    val description: String = "",
    val enabled: Boolean = true,
    /** Creation timestamp — shown in rule-edit UI sorted newest-first. */
    val addedTimestamp: Long = System.currentTimeMillis(),
    // ── A7 schedule gating ──────────────────────────────────────────
    /** 7-bit day-of-week mask; 0 = "no schedule gating, always active". */
    val scheduleDays: Int = 0,
    /** 0..23; inclusive start hour. Ignored when [scheduleDays] == 0. */
    val scheduleStartHour: Int = 0,
    /** 0..23; exclusive end hour. `start == end` disables the hour filter. */
    val scheduleEndHour: Int = 0,
) {
    /** Convenience accessor — bundles the three schedule columns. */
    val schedule: TimeSchedule
        get() = TimeSchedule(
            daysMask = scheduleDays,
            startHour = scheduleStartHour,
            endHour = scheduleEndHour,
        )

    /**
     * Gated match — returns `true` iff the pattern matches [number] AND
     * the schedule (if any) is active at [calendar]. Checkers on the hot
     * path should prefer this over calling [matches] + [schedule] separately.
     */
    fun matchesNow(number: String, calendar: Calendar = Calendar.getInstance()): Boolean {
        if (!schedule.isActiveAt(calendar)) return false
        return matches(number)
    }

    /**
     * True iff [number] matches this rule's pattern, trying common
     * country-prefix variants so `+33612######` also matches a national-
     * format `0612345678`. Does NOT consult the schedule — use [matchesNow]
     * on the decision path.
     */
    fun matches(number: String): Boolean =
        HashWildcardMatcher.matchesWithVariants(pattern, number)
}
