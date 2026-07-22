package com.sysadmindoc.callshield.data

import android.provider.CallLog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallbackDetectorTest {
    @Test
    fun `buildRecentlyDialedQuery targets outgoing calls within cutoff`() {
        val query =
            CallbackDetector.buildRecentlyDialedQuery(
                nowMillis = 10_000L,
                windowHours = 1,
                last7Digits = "5551234",
            )

        assertEquals(
            "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} > ? AND ${CallLog.Calls.NUMBER} LIKE ?",
            query.selection,
        )
        assertArrayEquals(
            arrayOf(
                CallLog.Calls.OUTGOING_TYPE.toString(),
                (10_000L - 3_600_000L).toString(),
                "%5551234",
            ),
            query.selectionArgs,
        )
    }

    @Test
    fun `buildRepeatedUrgentCallQuery excludes outgoing calls`() {
        val query =
            CallbackDetector.buildRepeatedUrgentCallQuery(
                nowMillis = 120_000L,
                windowMinutes = 5,
                last7Digits = "5551234",
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
    fun `buildRepeatedUrgentCallQuery clamps invalid windows`() {
        val query =
            CallbackDetector.buildRepeatedUrgentCallQuery(
                nowMillis = 120_000L,
                windowMinutes = 0,
                last7Digits = "5551234",
            )

        // Penultimate arg is the date cutoff; last arg is the NUMBER LIKE
        // pattern. Clamping pushes the cutoff back by exactly 1 minute.
        assertEquals((120_000L - 60_000L).toString(), query.selectionArgs[query.selectionArgs.size - 2])
        assertEquals("%5551234", query.selectionArgs.last())
    }

    @Test
    fun `urgent retry spacing rejects duplicate and burst call rows`() {
        assertFalse(
            CallbackDetector.hasUrgentRetrySpacing(
                timestamps = listOf(100_000L, 100_000L),
                threshold = 2,
            ),
        )
        assertFalse(
            CallbackDetector.hasUrgentRetrySpacing(
                timestamps = listOf(100_000L, 99_000L, 98_000L),
                threshold = 2,
            ),
        )
    }

    @Test
    fun `urgent retry spacing accepts deliberate retries`() {
        assertTrue(
            CallbackDetector.hasUrgentRetrySpacing(
                timestamps = listOf(100_000L, 80_000L),
                threshold = 2,
            ),
        )
    }

    @Test
    fun `duplicate provider row does not mask a genuinely spaced retry`() {
        // Second attempt was double-logged (161s and 160s): the top-2 rows are
        // only 1s apart, but a real, spaced retry exists in the window.
        assertTrue(
            CallbackDetector.hasUrgentRetrySpacing(
                timestamps = listOf(161_000L, 160_000L, 100_000L),
                threshold = 2,
            ),
        )
        // A pure machine-speed burst still never qualifies.
        assertFalse(
            CallbackDetector.hasUrgentRetrySpacing(
                timestamps = listOf(103_000L, 102_000L, 101_000L, 100_000L),
                threshold = 2,
            ),
        )
    }

    @Test
    fun `urgent retry spacing requires at least two attempts`() {
        assertFalse(
            CallbackDetector.hasUrgentRetrySpacing(
                timestamps = listOf(100_000L),
                threshold = 0,
            ),
        )
    }

    @Test
    fun `buildAnsweredCallerQuery targets answered incoming calls within cutoff`() {
        val query =
            CallbackDetector.buildAnsweredCallerQuery(
                nowMillis = 3_000_000_000L,
                windowDays = 30,
                minDurationSeconds = 1,
                last7Digits = "5551234",
            )

        assertEquals(
            "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} > ? " +
                "AND ${CallLog.Calls.DURATION} >= ? AND ${CallLog.Calls.NUMBER} LIKE ?",
            query.selection,
        )
        assertArrayEquals(
            arrayOf(
                CallLog.Calls.INCOMING_TYPE.toString(),
                (3_000_000_000L - 30L * 86_400_000L).toString(),
                "1",
                "%5551234",
            ),
            query.selectionArgs,
        )
    }

    @Test
    fun `buildAnsweredCallerQuery clamps invalid windows and durations`() {
        val query =
            CallbackDetector.buildAnsweredCallerQuery(
                nowMillis = 86_400_000L,
                windowDays = 0,
                minDurationSeconds = 0,
                last7Digits = "5551234",
            )

        assertEquals("0", query.selectionArgs[1])
        assertEquals("1", query.selectionArgs[2])
        assertEquals("%5551234", query.selectionArgs.last())
    }

    @Test
    fun `buildRecentEmergencyCallQuery targets outgoing calls within cutoff`() {
        val query =
            CallbackDetector.buildRecentEmergencyCallQuery(
                nowMillis = 900_000L,
                windowMinutes = 15,
            )

        assertEquals(
            "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} > ?",
            query.selection,
        )
        assertArrayEquals(
            arrayOf(
                CallLog.Calls.OUTGOING_TYPE.toString(),
                "0",
            ),
            query.selectionArgs,
        )
    }

    @Test
    fun `buildRecentEmergencyCallQuery clamps invalid windows`() {
        val query =
            CallbackDetector.buildRecentEmergencyCallQuery(
                nowMillis = 60_000L,
                windowMinutes = 0,
            )

        assertEquals("0", query.selectionArgs[1])
    }

    @Test
    fun `isEmergencyNumberCandidate accepts known emergency numbers only`() {
        assertTrue(CallbackDetector.isEmergencyNumberCandidate("911"))
        assertTrue(CallbackDetector.isEmergencyNumberCandidate("+1 (911)"))
        assertTrue(CallbackDetector.isEmergencyNumberCandidate("112"))
        assertFalse(CallbackDetector.isEmergencyNumberCandidate("555911"))
        assertFalse(CallbackDetector.isEmergencyNumberCandidate("411"))
    }

    // v1.6.3 — number-prefilter regression tests

    @Test
    fun `buildRecentlyDialedQuery prefilters on trailing digits`() {
        val query =
            CallbackDetector.buildRecentlyDialedQuery(
                nowMillis = 10_000L,
                windowHours = 24,
                last7Digits = "1234567",
            )
        assertTrue(
            "selection must constrain the NUMBER column",
            query.selection.contains("${CallLog.Calls.NUMBER} LIKE ?"),
        )
        assertEquals("%1234567", query.selectionArgs.last())
    }
}
