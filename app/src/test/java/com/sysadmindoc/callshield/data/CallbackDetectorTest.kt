package com.sysadmindoc.callshield.data

import android.provider.CallLog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallbackDetectorTest {

    @Test
    fun `buildRecentlyDialedQuery targets outgoing calls within cutoff`() {
        val query = CallbackDetector.buildRecentlyDialedQuery(
            nowMillis = 10_000L,
            windowHours = 1,
            last7Digits = "5551234"
        )

        assertEquals(
            "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} > ? AND ${CallLog.Calls.NUMBER} LIKE ?",
            query.selection
        )
        assertArrayEquals(
            arrayOf(
                CallLog.Calls.OUTGOING_TYPE.toString(),
                (10_000L - 3_600_000L).toString(),
                "%5551234"
            ),
            query.selectionArgs
        )
    }

    @Test
    fun `buildRepeatedUrgentCallQuery excludes outgoing calls`() {
        val query = CallbackDetector.buildRepeatedUrgentCallQuery(
            nowMillis = 120_000L,
            windowMinutes = 5,
            last7Digits = "5551234"
        )

        assertEquals(
            "${CallLog.Calls.TYPE} IN (?, ?) AND ${CallLog.Calls.DATE} > ? AND ${CallLog.Calls.NUMBER} LIKE ?",
            query.selection
        )
        assertArrayEquals(
            arrayOf(
                CallLog.Calls.INCOMING_TYPE.toString(),
                CallLog.Calls.MISSED_TYPE.toString(),
                (120_000L - 300_000L).toString(),
                "%5551234"
            ),
            query.selectionArgs
        )
    }

    @Test
    fun `buildRepeatedUrgentCallQuery clamps invalid windows`() {
        val query = CallbackDetector.buildRepeatedUrgentCallQuery(
            nowMillis = 120_000L,
            windowMinutes = 0,
            last7Digits = "5551234"
        )

        // Penultimate arg is the date cutoff; last arg is the NUMBER LIKE
        // pattern. Clamping pushes the cutoff back by exactly 1 minute.
        assertEquals((120_000L - 60_000L).toString(), query.selectionArgs[query.selectionArgs.size - 2])
        assertEquals("%5551234", query.selectionArgs.last())
    }

    // v1.6.3 — number-prefilter regression tests

    @Test
    fun `buildRecentlyDialedQuery prefilters on trailing digits`() {
        val query = CallbackDetector.buildRecentlyDialedQuery(
            nowMillis = 10_000L,
            windowHours = 24,
            last7Digits = "1234567"
        )
        assertTrue(
            "selection must constrain the NUMBER column",
            query.selection.contains("${CallLog.Calls.NUMBER} LIKE ?")
        )
        assertEquals("%1234567", query.selectionArgs.last())
    }
}
