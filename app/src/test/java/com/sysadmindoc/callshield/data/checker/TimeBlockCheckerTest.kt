package com.sysadmindoc.callshield.data.checker

import android.content.Context
import androidx.datastore.preferences.core.preferencesOf
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.SpamRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Quiet hours is a realtime CALLS feature. These tests pin two contracts:
 *
 * 1. The checker never runs for SMS/RCS (an OTP/bank/delivery message at
 *    23:00 must not be cancelled and logged as "blocked during quiet hours")
 *    and never runs on historical scans (which would classify old entries by
 *    the wall-clock hour the scan happens to run at).
 * 2. start == end means a 24-hour window — the Settings screen tells the
 *    user "quiet-hours blocking runs all day" for that configuration, and
 *    TimeSchedule uses the same all-day convention. It used to silently
 *    disable the feature instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimeBlockCheckerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val enabledPrefs =
        preferencesOf(
            SpamRepository.KEY_TIME_BLOCK to true,
            SpamRepository.KEY_TIME_BLOCK_START to 8,
            SpamRepository.KEY_TIME_BLOCK_END to 8,
        )

    private fun ctx(
        realtimeCall: Boolean,
        smsBody: String? = null,
    ) = CheckContext(
        appContext = context,
        number = "+12122340101",
        realtimeCall = realtimeCall,
        smsBody = smsBody,
        prefs = enabledPrefs,
    )

    @Test
    fun `disabled for sms even when the toggle is on`() =
        runBlocking {
            assertFalse(TimeBlockChecker().isEnabled(ctx(realtimeCall = true, smsBody = "your code is 123456")))
        }

    @Test
    fun `disabled for historical scans even when the toggle is on`() =
        runBlocking {
            assertFalse(TimeBlockChecker().isEnabled(ctx(realtimeCall = false)))
        }

    @Test
    fun `enabled for a realtime call`() =
        runBlocking {
            assertTrue(TimeBlockChecker().isEnabled(ctx(realtimeCall = true)))
        }

    @Test
    fun `matching start and end hours block all day, matching the settings copy`() =
        runBlocking {
            // start == end == 8 above: whatever the current wall-clock hour,
            // the 24-hour window must block.
            val result = TimeBlockChecker().check(ctx(realtimeCall = true))
            assertNotNull("start == end must be a 24h window, not a silent disable", result)
            assertTrue(result!!.shouldBlock)
        }

    // check() reads the wall clock, so the window arithmetic itself is pinned
    // through the pure helper. These are the branches that decide whether a
    // real call rings.

    @Test
    fun `overnight window wraps midnight and is end-exclusive`() {
        // 22:00 → 06:00
        assertTrue(TimeBlockChecker.isInQuietWindow(22, 22, 6))
        assertTrue(TimeBlockChecker.isInQuietWindow(23, 22, 6))
        assertTrue(TimeBlockChecker.isInQuietWindow(0, 22, 6))
        assertTrue(TimeBlockChecker.isInQuietWindow(5, 22, 6))
        assertFalse("end hour is exclusive", TimeBlockChecker.isInQuietWindow(6, 22, 6))
        assertFalse(TimeBlockChecker.isInQuietWindow(7, 22, 6))
        assertFalse(TimeBlockChecker.isInQuietWindow(21, 22, 6))
    }

    @Test
    fun `same-day window blocks from start until end, exclusive`() {
        // 09:00 → 17:00
        assertFalse(TimeBlockChecker.isInQuietWindow(8, 9, 17))
        assertTrue(TimeBlockChecker.isInQuietWindow(9, 9, 17))
        assertTrue(TimeBlockChecker.isInQuietWindow(16, 9, 17))
        assertFalse("end hour is exclusive", TimeBlockChecker.isInQuietWindow(17, 9, 17))
    }

    @Test
    fun `matching hours block every hour of the day`() {
        (0..23).forEach { hour ->
            assertTrue("hour $hour must block", TimeBlockChecker.isInQuietWindow(hour, 8, 8))
        }
    }
}
