package com.sysadmindoc.callshield.data

import android.content.Context
import android.provider.CallLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Callback detection — stolen from SpamBlocker.
 * 1. Dialed Number Recognition: don't block numbers the user recently called
 * 2. Repeated Call Allow-Through: if same number calls 2x in 5 min, allow (urgent)
 *
 * These two features dramatically reduce false positives.
 *
 * ## Perf note (v1.6.3)
 *
 * Both queries previously scanned the full CallLog window (24h outgoing,
 * 5min incoming) and post-filtered the number in Kotlin. On heavy users
 * that's up to hundreds of rows decoded per screening call. We now add a
 * `NUMBER LIKE '%<last7>'` prefilter so SQLite narrows the result set
 * before handing the cursor back — Kotlin still post-filters because
 * CallLog stores raw as-dialed formats (parentheses, spaces) that our
 * digit-suffix match tolerates.
 */
object CallbackDetector {

    internal data class CallLogQuery(
        val selection: String,
        val selectionArgs: Array<String>,
    )

    /**
     * Check if the user has dialed this number recently (outgoing call).
     * If so, incoming calls from that number should be allowed through
     * for [windowHours] hours — they're likely calling back.
     */
    suspend fun wasRecentlyDialed(context: Context, number: String, windowHours: Int = 24): Boolean = withContext(Dispatchers.IO) {
        val digits = number.filter { it.isDigit() }.takeLast(10)
        if (digits.length < 7) return@withContext false
        val last7 = digits.takeLast(7)

        try {
            val query = buildRecentlyDialedQuery(System.currentTimeMillis(), windowHours, last7)
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER),
                query.selection,
                query.selectionArgs,
                "${CallLog.Calls.DATE} DESC"
            )
            cursor?.use { c ->
                val numIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                if (numIdx < 0) return@withContext false
                while (c.moveToNext()) {
                    val dialedDigits = (c.getString(numIdx) ?: "").filter { it.isDigit() }.takeLast(10)
                    if (dialedDigits == digits) return@withContext true
                }
            }
        } catch (_: SecurityException) {}
        false
    }

    /**
     * Check if this number has called multiple times within a short window.
     * If someone calls 2+ times in 5 minutes, it's likely urgent/legitimate.
     * Robocallers don't do this — they cycle through numbers.
     */
    suspend fun isRepeatedUrgentCall(
        context: Context, number: String,
        windowMinutes: Int = 5, threshold: Int = 2
    ): Boolean = withContext(Dispatchers.IO) {
        val digits = number.filter { it.isDigit() }.takeLast(10)
        if (digits.length < 7) return@withContext false
        val last7 = digits.takeLast(7)

        try {
            val query = buildRepeatedUrgentCallQuery(System.currentTimeMillis(), windowMinutes, last7)
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER),
                query.selection,
                query.selectionArgs,
                null
            )
            var count = 0
            cursor?.use { c ->
                val numIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                if (numIdx < 0) return@withContext false
                while (c.moveToNext()) {
                    val callDigits = (c.getString(numIdx) ?: "").filter { it.isDigit() }.takeLast(10)
                    if (callDigits == digits) count++
                }
            }
            count >= threshold
        } catch (_: SecurityException) {
            false
        }
    }

    internal fun buildRecentlyDialedQuery(
        nowMillis: Long,
        windowHours: Int,
        last7Digits: String,
    ): CallLogQuery {
        val safeWindowHours = windowHours.coerceAtLeast(1)
        val cutoff = (nowMillis - safeWindowHours * 3_600_000L).toString()
        // Use NUMBER LIKE '%<last7>' to let SQLite do the heavy lifting;
        // Kotlin post-filters the exact 10-digit match because CallLog
        // formats vary (parentheses, dashes, country-code presence).
        return CallLogQuery(
            selection = "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} > ? AND ${CallLog.Calls.NUMBER} LIKE ?",
            selectionArgs = arrayOf(
                CallLog.Calls.OUTGOING_TYPE.toString(),
                cutoff,
                "%$last7Digits"
            )
        )
    }

    internal fun buildRepeatedUrgentCallQuery(
        nowMillis: Long,
        windowMinutes: Int,
        last7Digits: String,
    ): CallLogQuery {
        val safeWindowMinutes = windowMinutes.coerceAtLeast(1)
        val cutoff = (nowMillis - safeWindowMinutes * 60_000L).toString()
        return CallLogQuery(
            selection = "${CallLog.Calls.TYPE} IN (?, ?) AND ${CallLog.Calls.DATE} > ? AND ${CallLog.Calls.NUMBER} LIKE ?",
            selectionArgs = arrayOf(
                CallLog.Calls.INCOMING_TYPE.toString(),
                CallLog.Calls.MISSED_TYPE.toString(),
                cutoff,
                "%$last7Digits"
            )
        )
    }
}
