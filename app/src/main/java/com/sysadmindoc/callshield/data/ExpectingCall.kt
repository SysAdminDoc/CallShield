package com.sysadmindoc.callshield.data

import androidx.datastore.preferences.core.Preferences
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
}
