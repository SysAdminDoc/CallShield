package com.sysadmindoc.callshield.data

import android.content.Context
import android.provider.CallLog
import com.sysadmindoc.callshield.util.filterAsciiDigits
import com.sysadmindoc.callshield.util.filterAsciiDigitsLast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

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
class CallbackDetector
    @Inject
    constructor() {
        internal data class CallLogQuery(
            val selection: String,
            val selectionArgs: Array<String>,
        )

        /**
         * Check if the user has dialed this number recently (outgoing call).
         * If so, incoming calls from that number should be allowed through
         * for [windowHours] hours — they're likely calling back.
         */
        suspend fun wasRecentlyDialed(
            context: Context,
            number: String,
            windowHours: Int = 24,
        ): Boolean =
            withContext(Dispatchers.IO) {
                val digits = filterAsciiDigitsLast(number, 10)
                if (digits.length < 7) return@withContext false
                val last7 = digits.takeLast(7)

                try {
                    val query = buildRecentlyDialedQuery(System.currentTimeMillis(), windowHours, last7)
                    val cursor =
                        context.contentResolver.query(
                            CallLog.Calls.CONTENT_URI,
                            arrayOf(CallLog.Calls.NUMBER),
                            query.selection,
                            query.selectionArgs,
                            "${CallLog.Calls.DATE} DESC",
                        )
                    cursor?.use { c ->
                        val numIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                        if (numIdx < 0) return@withContext false
                        while (c.moveToNext()) {
                            val dialedDigits = filterAsciiDigitsLast(c.getString(numIdx) ?: "", 10)
                            if (dialedDigits == digits) return@withContext true
                        }
                    }
                } catch (_: SecurityException) {
                }
                false
            }

        /**
         * Check if this number has called multiple times within a short window.
         * If someone calls 2+ times in 5 minutes, it's likely urgent/legitimate.
         * Robocallers don't do this — they cycle through numbers.
         */
        suspend fun isRepeatedUrgentCall(
            context: Context,
            number: String,
            windowMinutes: Int = 5,
            threshold: Int = 2,
        ): Boolean =
            withContext(Dispatchers.IO) {
                val digits = filterAsciiDigitsLast(number, 10)
                if (digits.length < 7) return@withContext false
                val last7 = digits.takeLast(7)

                try {
                    val query = buildRepeatedUrgentCallQuery(System.currentTimeMillis(), windowMinutes, last7)
                    val cursor =
                        context.contentResolver.query(
                            CallLog.Calls.CONTENT_URI,
                            arrayOf(CallLog.Calls.NUMBER),
                            query.selection,
                            query.selectionArgs,
                            null,
                        )
                    var count = 0
                    cursor?.use { c ->
                        val numIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                        if (numIdx < 0) return@withContext false
                        while (c.moveToNext()) {
                            val callDigits = filterAsciiDigitsLast(c.getString(numIdx) ?: "", 10)
                            if (callDigits == digits) count++
                        }
                    }
                    count >= threshold
                } catch (_: SecurityException) {
                    false
                }
            }

        suspend fun wasAnsweredRepeatedly(
            context: Context,
            number: String,
            windowDays: Int = DEFAULT_ANSWERED_CALLER_WINDOW_DAYS,
            threshold: Int = DEFAULT_ANSWERED_CALLER_THRESHOLD,
            minDurationSeconds: Int = MIN_ANSWERED_CALL_DURATION_SECONDS,
        ): Boolean =
            withContext(Dispatchers.IO) {
                val digits = filterAsciiDigitsLast(number, 10)
                if (digits.length < 7) return@withContext false
                val last7 = digits.takeLast(7)
                val safeThreshold = threshold.coerceAtLeast(1)

                try {
                    val query =
                        buildAnsweredCallerQuery(
                            nowMillis = System.currentTimeMillis(),
                            windowDays = windowDays,
                            minDurationSeconds = minDurationSeconds,
                            last7Digits = last7,
                        )
                    val cursor =
                        context.contentResolver.query(
                            CallLog.Calls.CONTENT_URI,
                            arrayOf(CallLog.Calls.NUMBER),
                            query.selection,
                            query.selectionArgs,
                            "${CallLog.Calls.DATE} DESC",
                        )
                    var count = 0
                    cursor?.use { c ->
                        val numIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                        if (numIdx < 0) return@withContext false
                        while (c.moveToNext()) {
                            val answeredDigits = filterAsciiDigitsLast(c.getString(numIdx) ?: "", 10)
                            if (answeredDigits == digits) {
                                count++
                                if (count >= safeThreshold) return@withContext true
                            }
                        }
                    }
                } catch (_: SecurityException) {
                    return@withContext false
                }
                false
            }

        suspend fun hasRecentEmergencyCall(
            context: Context,
            windowMinutes: Int = DEFAULT_EMERGENCY_CALLBACK_WINDOW_MINUTES,
        ): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    val query =
                        buildRecentEmergencyCallQuery(
                            nowMillis = System.currentTimeMillis(),
                            windowMinutes = windowMinutes,
                        )
                    val cursor =
                        context.contentResolver.query(
                            CallLog.Calls.CONTENT_URI,
                            arrayOf(CallLog.Calls.NUMBER),
                            query.selection,
                            query.selectionArgs,
                            "${CallLog.Calls.DATE} DESC",
                        )
                    cursor?.use { c ->
                        val numIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                        if (numIdx < 0) return@withContext false
                        while (c.moveToNext()) {
                            if (isEmergencyNumberCandidate(c.getString(numIdx) ?: "")) {
                                return@withContext true
                            }
                        }
                    }
                } catch (_: SecurityException) {
                    return@withContext false
                }
                false
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
                selectionArgs =
                    arrayOf(
                        CallLog.Calls.OUTGOING_TYPE.toString(),
                        cutoff,
                        "%$last7Digits",
                    ),
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
                selectionArgs =
                    arrayOf(
                        CallLog.Calls.INCOMING_TYPE.toString(),
                        CallLog.Calls.MISSED_TYPE.toString(),
                        cutoff,
                        "%$last7Digits",
                    ),
            )
        }

        internal fun buildAnsweredCallerQuery(
            nowMillis: Long,
            windowDays: Int,
            minDurationSeconds: Int,
            last7Digits: String,
        ): CallLogQuery {
            val safeWindowDays = windowDays.coerceAtLeast(1)
            val safeDurationSeconds = minDurationSeconds.coerceAtLeast(1)
            val cutoff = (nowMillis - safeWindowDays * MILLIS_PER_DAY).toString()
            return CallLogQuery(
                selection =
                    "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} > ? " +
                        "AND ${CallLog.Calls.DURATION} >= ? AND ${CallLog.Calls.NUMBER} LIKE ?",
                selectionArgs =
                    arrayOf(
                        CallLog.Calls.INCOMING_TYPE.toString(),
                        cutoff,
                        safeDurationSeconds.toString(),
                        "%$last7Digits",
                    ),
            )
        }

        internal fun buildRecentEmergencyCallQuery(
            nowMillis: Long,
            windowMinutes: Int,
        ): CallLogQuery {
            val safeWindowMinutes = windowMinutes.coerceAtLeast(1)
            val cutoff = (nowMillis - safeWindowMinutes * MILLIS_PER_MINUTE).toString()
            return CallLogQuery(
                selection = "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} > ?",
                selectionArgs =
                    arrayOf(
                        CallLog.Calls.OUTGOING_TYPE.toString(),
                        cutoff,
                    ),
            )
        }

        internal fun isEmergencyNumberCandidate(number: String): Boolean {
            val digits = filterAsciiDigits(number)
            return digits in EMERGENCY_NUMBERS ||
                (
                    digits.length == EMERGENCY_COUNTRY_PREFIXED_DIGITS &&
                        digits.startsWith(EMERGENCY_COUNTRY_PREFIX) &&
                        digits.drop(1) in EMERGENCY_NUMBERS
                )
        }

        companion object {
            const val DEFAULT_ANSWERED_CALLER_WINDOW_DAYS = 30
            const val DEFAULT_ANSWERED_CALLER_THRESHOLD = 2
            const val DEFAULT_EMERGENCY_CALLBACK_WINDOW_MINUTES = 240
            const val MIN_ANSWERED_CALL_DURATION_SECONDS = 1
            private const val MILLIS_PER_DAY = 86_400_000L
            private const val MILLIS_PER_MINUTE = 60_000L
            private const val EMERGENCY_COUNTRY_PREFIXED_DIGITS = 4
            private const val EMERGENCY_COUNTRY_PREFIX = "1"
            private val EMERGENCY_NUMBERS = setOf("000", "111", "112", "118", "119", "911", "999")

            val shared: CallbackDetector = CallbackDetector()

            suspend fun wasRecentlyDialed(
                context: Context,
                number: String,
                windowHours: Int = 24,
            ): Boolean = shared.wasRecentlyDialed(context, number, windowHours)

            suspend fun isRepeatedUrgentCall(
                context: Context,
                number: String,
                windowMinutes: Int = 5,
                threshold: Int = 2,
            ): Boolean = shared.isRepeatedUrgentCall(context, number, windowMinutes, threshold)

            suspend fun wasAnsweredRepeatedly(
                context: Context,
                number: String,
                windowDays: Int = DEFAULT_ANSWERED_CALLER_WINDOW_DAYS,
                threshold: Int = DEFAULT_ANSWERED_CALLER_THRESHOLD,
                minDurationSeconds: Int = MIN_ANSWERED_CALL_DURATION_SECONDS,
            ): Boolean = shared.wasAnsweredRepeatedly(context, number, windowDays, threshold, minDurationSeconds)

            suspend fun hasRecentEmergencyCall(
                context: Context,
                windowMinutes: Int = DEFAULT_EMERGENCY_CALLBACK_WINDOW_MINUTES,
            ): Boolean = shared.hasRecentEmergencyCall(context, windowMinutes)

            internal fun buildRecentlyDialedQuery(
                nowMillis: Long,
                windowHours: Int,
                last7Digits: String,
            ): CallLogQuery = shared.buildRecentlyDialedQuery(nowMillis, windowHours, last7Digits)

            internal fun buildRepeatedUrgentCallQuery(
                nowMillis: Long,
                windowMinutes: Int,
                last7Digits: String,
            ): CallLogQuery = shared.buildRepeatedUrgentCallQuery(nowMillis, windowMinutes, last7Digits)

            internal fun buildAnsweredCallerQuery(
                nowMillis: Long,
                windowDays: Int,
                minDurationSeconds: Int,
                last7Digits: String,
            ): CallLogQuery = shared.buildAnsweredCallerQuery(nowMillis, windowDays, minDurationSeconds, last7Digits)

            internal fun buildRecentEmergencyCallQuery(
                nowMillis: Long,
                windowMinutes: Int,
            ): CallLogQuery = shared.buildRecentEmergencyCallQuery(nowMillis, windowMinutes)

            internal fun isEmergencyNumberCandidate(number: String): Boolean = shared.isEmergencyNumberCandidate(number)
        }
    }
