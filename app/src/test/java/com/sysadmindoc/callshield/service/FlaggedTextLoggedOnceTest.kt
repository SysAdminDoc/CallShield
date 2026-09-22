package com.sysadmindoc.callshield.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.repository.TEXT_DUPLICATE_WINDOW_MS
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Google and Samsung Messages are read by both the SMS receiver and the
 * notification listener, and each used to log the same text: two rows, two
 * alerts, doubled statistics. Both paths now go through one repository call
 * that keys on the canonical sender inside a short window. They see the text
 * differently, as the calls below do: bare digits against +1, a notification
 * body cut short, and an rcs_ prefix on the reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlaggedTextLoggedOnceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fixture = IsolatedRepositoryFixture(context)
    private val repo = fixture.repository

    @After
    fun tearDown() = fixture.close()

    private fun flaggedRows(number: String) = runBlocking { fixture.dao.countFlaggedTextsSince(number, 0L) }

    @Test
    fun `a text both paths flag is logged once`() {
        runBlocking {
            SmsReceiver.logFlaggedSms(repo, "+12125550101", "Your package is held. Call 2125550143 to release it today", "keyword", 80, null, null)
            RcsNotificationListener.logFlaggedNotification(repo, "2125550101", "Your package is held. Call 21255", "keyword", 80, null, null)
        }

        assertEquals(1, flaggedRows("+12125550101"))
    }

    @Test
    fun `whichever path sees the text first logs it, and the other adds nothing`() {
        val logged =
            runBlocking {
                listOf(
                    RcsNotificationListener.logFlaggedNotification(repo, "2125550102", "Win a prize", "keyword", 70, null, null),
                    SmsReceiver.logFlaggedSms(repo, "+12125550102", "Win a prize now", "keyword", 70, null, null),
                )
            }

        assertEquals(listOf(true, false), logged)
        assertEquals(1, flaggedRows("+12125550102"))
    }

    @Test
    fun `an RCS message only the listener sees is still logged`() {
        runBlocking { RcsNotificationListener.logFlaggedNotification(repo, "2125550103", "RCS spam", "keyword", 70, null, null) }

        assertEquals(1, flaggedRows("+12125550103"))
    }

    @Test
    fun `another text from the same sender after the window gets its own row`() {
        val first = 1_800_000_000_000L
        runBlocking {
            repo.logFlaggedText("+12125550104", "one", "keyword", 70, timestamp = first)
            repo.logFlaggedText("+12125550104", "two", "keyword", 70, timestamp = first + TEXT_DUPLICATE_WINDOW_MS + 1)
        }

        assertEquals(2, flaggedRows("+12125550104"))
    }

    @Test
    fun `a text's alert says it was flagged, and a call's that it was blocked`() {
        assertEquals(
            context.getString(R.string.notif_flagged_text_title),
            NotificationHelper.blockedAlertTitle(context, isCall = false, typeText = "SMS"),
        )
        assertEquals(
            context.getString(R.string.notif_blocked_title, "call"),
            NotificationHelper.blockedAlertTitle(context, isCall = true, typeText = "call"),
        )
    }
}
