package com.sysadmindoc.callshield.ui

import android.content.res.Resources
import com.sysadmindoc.callshield.R

/** The unit a wait is shown in. */
internal enum class WaitUnit { SECONDS, MINUTES, HOURS }

internal data class RoundedWait(
    val unit: WaitUnit,
    val count: Int,
)

private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3600

// Up to an hour and a half reads better in minutes ("68 minutes", not "2 hours").
private const val MINUTES_SHOWN_UP_TO = 90

/**
 * A Retry-After wait the way a person reads it: seconds under a minute, minutes
 * up to an hour and a half, hours after that. It rounds up, so whoever waits that
 * long is never sent back early. The report service's duplicate check answers
 * with the time left to midnight UTC, up to a day, which read "Wait 4039 seconds".
 */
internal fun roundedWait(seconds: Int): RoundedWait {
    val wait = seconds.coerceAtLeast(1)
    return when {
        wait < SECONDS_PER_MINUTE -> RoundedWait(WaitUnit.SECONDS, wait)
        wait <= MINUTES_SHOWN_UP_TO * SECONDS_PER_MINUTE ->
            RoundedWait(WaitUnit.MINUTES, (wait + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE)
        else -> RoundedWait(WaitUnit.HOURS, (wait + SECONDS_PER_HOUR - 1) / SECONDS_PER_HOUR)
    }
}

/** "Too many reports. Wait 68 minutes and try again." for a wait of [seconds]. */
internal fun rateLimitedMessage(
    resources: Resources,
    seconds: Int,
): String {
    val (unit, count) = roundedWait(seconds)
    val plural =
        when (unit) {
            WaitUnit.SECONDS -> R.plurals.contribute_rate_limited
            WaitUnit.MINUTES -> R.plurals.contribute_rate_limited_minutes
            WaitUnit.HOURS -> R.plurals.contribute_rate_limited_hours
        }
    return resources.getQuantityString(plural, count, count)
}
