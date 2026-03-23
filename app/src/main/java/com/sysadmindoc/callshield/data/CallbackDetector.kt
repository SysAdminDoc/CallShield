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
 */
object CallbackDetector {

    /**
     * Check if the user has dialed this number recently (outgoing call).
     * If so, incoming calls from that number should be allowed through
     * for [windowHours] hours — they're likely calling back.
     */
    suspend fun wasRecentlyDialed(context: Context, number: String, windowHours: Int = 24): Boolean = withContext(Dispatchers.IO) {
        val digits = number.filter { it.isDigit() }.takeLast(10)
        if (digits.length < 7) return@withContext false

        try {
            val cutoff = (System.currentTimeMillis() - windowHours * 3600_000L).toString()
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER),
                "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} > ?",
                arrayOf(CallLog.Calls.OUTGOING_TYPE.toString(), cutoff),
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

        try {
            val cutoff = (System.currentTimeMillis() - windowMinutes * 60_000L).toString()
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER),
                "${CallLog.Calls.DATE} > ?",
                arrayOf(cutoff),
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
}
