package com.sysadmindoc.callshield.util

import android.content.Context
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A [SimpleDateFormat] that honors the device 12/24-hour setting and locale
 * conventions, instead of a hardcoded `h:mm a` that always forces AM/PM.
 *
 * @param withYear include the year (for detail views), else month/day + time.
 */
fun localizedDateTimeFormat(
    context: Context,
    withYear: Boolean = false,
): SimpleDateFormat {
    val locale = Locale.getDefault()
    val is24Hour = DateFormat.is24HourFormat(context)
    val timeSkeleton = if (is24Hour) "Hm" else "hm a"
    val skeleton = if (withYear) "MMMd yyyy $timeSkeleton" else "MMMd $timeSkeleton"
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
}
