package com.sysadmindoc.callshield.data

import android.provider.CallLog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CallbackDetectorTest {

    @Test
    fun `buildRecentlyDialedQuery targets outgoing calls within cutoff`() {
        val query = CallbackDetector.buildRecentlyDialedQuery(
            nowMillis = 10_000L,
            windowHours = 1
        )

        assertEquals(
            "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} > ?",
            query.selection
        )
        assertArrayEquals(
            arrayOf(
                CallLog.Calls.OUTGOING_TYPE.toString(),
                (10_000L - 3_600_000L).toString()
            ),
            query.selectionArgs
        )
    }

    @Test
    fun `buildRepeatedUrgentCallQuery excludes outgoing calls`() {
        val query = CallbackDetector.buildRepeatedUrgentCallQuery(
            nowMillis = 120_000L,
            windowMinutes = 5
        )

        assertEquals(
            "${CallLog.Calls.TYPE} IN (?, ?) AND ${CallLog.Calls.DATE} > ?",
            query.selection
        )
        assertArrayEquals(
            arrayOf(
                CallLog.Calls.INCOMING_TYPE.toString(),
                CallLog.Calls.MISSED_TYPE.toString(),
                (120_000L - 300_000L).toString()
            ),
            query.selectionArgs
        )
    }

    @Test
    fun `buildRepeatedUrgentCallQuery clamps invalid windows`() {
        val query = CallbackDetector.buildRepeatedUrgentCallQuery(
            nowMillis = 120_000L,
            windowMinutes = 0
        )

        assertEquals((120_000L - 60_000L).toString(), query.selectionArgs.last())
    }
}
