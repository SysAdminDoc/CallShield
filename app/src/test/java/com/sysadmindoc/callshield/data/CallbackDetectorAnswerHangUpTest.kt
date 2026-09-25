package com.sysadmindoc.callshield.data

import android.provider.CallLog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CallbackDetectorAnswerHangUpTest {
    @Test
    fun `repeated urgent query remains unchanged when answer hang up is off`() {
        val query =
            CallbackDetector.buildRepeatedUrgentCallQuery(
                nowMillis = 120_000L,
                windowMinutes = 5,
                last7Digits = "5551234",
                minIncomingDurationSeconds = 0,
            )

        assertEquals(
            "${CallLog.Calls.TYPE} IN (?, ?) AND ${CallLog.Calls.DATE} > ? AND ${CallLog.Calls.NUMBER} LIKE ?",
            query.selection,
        )
        assertArrayEquals(
            arrayOf(
                CallLog.Calls.INCOMING_TYPE.toString(),
                CallLog.Calls.MISSED_TYPE.toString(),
                (120_000L - 300_000L).toString(),
                "%5551234",
            ),
            query.selectionArgs,
        )
    }

    @Test
    fun `answered caller query uses fifteen second floor while answer hang up is on`() {
        val query =
            CallbackDetector.buildAnsweredCallerQuery(
                nowMillis = 3_000_000_000L,
                windowDays = 30,
                minDurationSeconds = CallbackDetector.AUTO_ANSWER_MIN_TRUSTED_SECONDS,
                last7Digits = "5551234",
            )

        assertEquals("15", query.selectionArgs[2])
    }

    @Test
    fun `repeated urgent query preserves missed calls and filters short incoming calls`() {
        val query =
            CallbackDetector.buildRepeatedUrgentCallQuery(
                nowMillis = 120_000L,
                windowMinutes = 5,
                last7Digits = "5551234",
                minIncomingDurationSeconds = CallbackDetector.AUTO_ANSWER_MIN_TRUSTED_SECONDS,
            )

        assertEquals(
            "(${CallLog.Calls.TYPE} = ? OR (${CallLog.Calls.TYPE} = ? AND " +
                "${CallLog.Calls.DURATION} >= ?)) AND ${CallLog.Calls.DATE} > ? " +
                "AND ${CallLog.Calls.NUMBER} LIKE ?",
            query.selection,
        )
        assertArrayEquals(
            arrayOf(
                CallLog.Calls.MISSED_TYPE.toString(),
                CallLog.Calls.INCOMING_TYPE.toString(),
                "15",
                (120_000L - 300_000L).toString(),
                "%5551234",
            ),
            query.selectionArgs,
        )
    }
}
