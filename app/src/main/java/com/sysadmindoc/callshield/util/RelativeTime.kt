package com.sysadmindoc.callshield.util

import android.text.format.DateUtils

/**
 * How long ago [time] was at [now], the way the phone's language says it:
 * "5 minutes ago", "Yesterday", or past a week the date. Null under a minute,
 * and for a time ahead of [now], where each screen says "just now" its own
 * way. [abbreviate] gives "5 min. ago" where space is short.
 */
fun relativeTimeSpan(
    time: Long,
    now: Long,
    abbreviate: Boolean = false,
): String? {
    if (now - time < DateUtils.MINUTE_IN_MILLIS) return null
    val flags = if (abbreviate) DateUtils.FORMAT_ABBREV_RELATIVE else 0
    return DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS, flags).toString()
}
