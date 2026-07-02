package com.sysadmindoc.callshield.data

import android.provider.Telephony
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsContextCheckerTest {
    @Test
    fun `buildRecentIncomingSmsQuery targets recent inbox rows`() {
        val query =
            SmsContextChecker.buildRecentIncomingSmsQuery(
                nowMillis = 1_800_000L,
                windowMinutes = 30,
            )

        assertEquals("${Telephony.Sms.Inbox.DATE} > ?", query.selection)
        assertArrayEquals(arrayOf("0"), query.selectionArgs)
    }

    @Test
    fun `Android 17 delayed otp broadcast counts current message when provider has only prior rows`() {
        val signal =
            SmsContextChecker.evaluateSmsBurst(
                observations = listOf(
                    SmsBurstObservation("55555", timestamp = 1_700_000L),
                    SmsBurstObservation("55555", timestamp = 1_200_000L),
                ),
                sender = "55555",
                nowMillis = 1_800_000L,
                config = SmsBurstConfig(
                    windowMinutes = 30,
                ),
            )

        assertEquals(SmsBurstKind.SENDER, signal?.kind)
        assertEquals(3, signal?.count)
    }

    @Test
    fun `current provider row is not double counted`() {
        val signal =
            SmsContextChecker.evaluateSmsBurst(
                observations = listOf(
                    SmsBurstObservation("55555", timestamp = 1_798_000L),
                    SmsBurstObservation("55555", timestamp = 1_200_000L),
                ),
                sender = "55555",
                nowMillis = 1_800_000L,
                config = SmsBurstConfig(
                    windowMinutes = 30,
                ),
            )

        assertNull(signal)
    }

    @Test
    fun `distinct same-prefix senders trigger prefix burst`() {
        val signal =
            SmsContextChecker.evaluateSmsBurst(
                observations = listOf(
                    SmsBurstObservation("+1 212-555-0101", timestamp = 1_700_000L),
                    SmsBurstObservation("+1 212-555-0102", timestamp = 1_600_000L),
                    SmsBurstObservation("+1 212-555-0103", timestamp = 1_500_000L),
                    SmsBurstObservation("+1 212-555-0104", timestamp = 1_400_000L),
                ),
                sender = "+1 212-555-0105",
                nowMillis = 1_800_000L,
                config = SmsBurstConfig(
                    windowMinutes = 30,
                ),
            )

        assertEquals(SmsBurstKind.PREFIX, signal?.kind)
        assertEquals(5, signal?.count)
        assertEquals("212555", signal?.prefix)
    }

    @Test
    fun `stale observations do not trigger burst`() {
        val signal =
            SmsContextChecker.evaluateSmsBurst(
                observations = listOf(
                    SmsBurstObservation("55555", timestamp = 1L),
                    SmsBurstObservation("55555", timestamp = 2L),
                ),
                sender = "55555",
                nowMillis = 1_800_000L,
                config = SmsBurstConfig(
                    windowMinutes = 10,
                ),
            )

        assertNull(signal)
    }
}
