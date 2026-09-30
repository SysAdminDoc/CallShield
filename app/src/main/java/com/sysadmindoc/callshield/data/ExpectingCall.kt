package com.sysadmindoc.callshield.data

import androidx.datastore.preferences.core.Preferences
import com.sysadmindoc.callshield.data.checker.MeetingModeChecker
import java.time.Instant
import java.time.ZoneId

/**
 * "Expecting a call": for a while, unknown callers ring. The window is an end
 * time in preferences, so it ends by itself. The user's own blocklist, system
 * list and wildcard rules and a failed caller ID check still block, and
 * contacts-only mode stands aside while it runs.
 */
internal object ExpectingCall {
    enum class Length {
        ONE_HOUR,
        THREE_HOURS,
        UNTIL_MIDNIGHT,
    }

    private const val HOUR_MS = 60L * 60L * 1_000L

    fun endsAt(
        length: Length,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long =
        when (length) {
            Length.ONE_HOUR -> {
                now + HOUR_MS
            }

            Length.THREE_HOURS -> {
                now + 3 * HOUR_MS
            }

            Length.UNTIL_MIDNIGHT -> {
                Instant
                    .ofEpochMilli(now)
                    .atZone(zone)
                    .toLocalDate()
                    .plusDays(1)
                    .atStartOfDay(zone)
                    .toInstant()
                    .toEpochMilli()
            }
        }

    fun until(prefs: Preferences): Long = prefs[SpamRepository.KEY_EXPECTING_CALL_UNTIL] ?: 0L

    fun isActive(
        prefs: Preferences,
        now: Long,
    ): Boolean = until(prefs) > now

    /** A window that ran out. Its end time is removed once seen, so a clock stepped back can't reopen it. */
    fun hasEnded(
        prefs: Preferences,
        now: Long,
    ): Boolean = until(prefs) in 1..now

    /**
     * What rejects or silences a call that came with no number, or null to let it
     * ring. A window lets it ring: the expected caller may withhold their number,
     * and the window promises unknown callers get through, so it outranks both
     * "block hidden numbers" (set by the Contacts only, Personal, Sleep and
     * Maximum profiles) and meeting mode.
     */
    fun withheldCallBlockSource(
        prefs: Preferences,
        now: Long,
        meetingApp: String?,
    ): String? =
        when {
            isActive(prefs, now) -> null
            prefs[SpamRepository.KEY_BLOCK_UNKNOWN] ?: false -> "hidden_number"
            meetingApp != null -> MeetingModeChecker.MATCH_SOURCE
            else -> null
        }
}
