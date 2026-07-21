package com.sysadmindoc.callshield.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for [SpamActionReceiver] — drives the real
 * BroadcastReceiver against the framework NotificationManager, off-device.
 *
 * The notification cancel happens synchronously inside `onReceive` (before the
 * async repo/community work), so we can assert it deterministically without
 * awaiting the background coroutine. This locks the v1.7.14 fix where the
 * feedback notification was cancelled under the wrong ID.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpamActionReceiverRobolectricTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun postFeedbackNotification(number: String): Int {
        val id = NotificationHelper.feedbackNotificationId(number)
        nm.createNotificationChannel(
            NotificationChannel("test", "test", NotificationManager.IMPORTANCE_LOW),
        )
        val notif =
            android.app.Notification
                .Builder(context, "test")
                .setContentTitle("after-call")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        nm.notify(id, notif)
        return id
    }

    @Test
    fun `FEEDBACK_SPAM cancels the feedback notification by its stable id`() {
        val number = "+15551234567"
        val id = postFeedbackNotification(number)
        assertTrue(shadowOf(nm).getNotification(id) != null)

        SpamActionReceiver().onReceive(
            context,
            Intent("com.sysadmindoc.callshield.FEEDBACK_SPAM").putExtra("number", number),
        )

        assertFalse(
            "feedback notification must be cancelled under feedbackNotificationId",
            shadowOf(nm).getNotification(id) != null,
        )
    }

    @Test
    fun `FEEDBACK_NOT_SPAM cancels the same feedback notification`() {
        val number = "+15559876543"
        val id = postFeedbackNotification(number)

        SpamActionReceiver().onReceive(
            context,
            Intent("com.sysadmindoc.callshield.FEEDBACK_NOT_SPAM").putExtra("number", number),
        )

        assertFalse(shadowOf(nm).getNotification(id) != null)
    }

    @Test
    fun `unknown action is a no-op and leaves notifications intact`() {
        val number = "+15550000000"
        val id = postFeedbackNotification(number)

        SpamActionReceiver().onReceive(
            context,
            Intent("com.sysadmindoc.callshield.UNKNOWN").putExtra("number", number),
        )

        assertTrue(shadowOf(nm).getNotification(id) != null)
    }
}
