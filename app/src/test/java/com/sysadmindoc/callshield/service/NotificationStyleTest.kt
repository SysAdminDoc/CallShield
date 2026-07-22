package com.sysadmindoc.callshield.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class NotificationStyleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun setUp() {
        shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationHelper.createChannels(context)
        notificationManager.cancelAll()
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
    }

    @Test
    @Config(sdk = [34])
    fun `after-call feedback keeps correctly labeled actions`() {
        val number = "+15551234567"

        NotificationHelper.notifyAfterCall(context, number)

        val notification = postedNotification(NotificationHelper.feedbackNotificationId(number))
        assertNotEquals(
            Notification.CallStyle::class.java.name,
            notification.extras.getString(Notification.EXTRA_TEMPLATE),
        )
        assertEquals(
            listOf(context.getString(R.string.feedback_spam), context.getString(R.string.feedback_not_spam)),
            notification.actions.map { it.title.toString() },
        )
    }

    @Test
    @Config(sdk = [35])
    fun `manual sync keeps indeterminate progress fallback below Android 16`() {
        NotificationHelper.showSyncProgress(context)

        val notification = postedNotification(NotificationHelper.SYNC_NOTIFICATION_ID)
        assertNotEquals(
            Notification.ProgressStyle::class.java.name,
            notification.extras.getString(Notification.EXTRA_TEMPLATE),
        )
        assertEquals(true, notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    private fun postedNotification(id: Int): Notification = requireNotNull(shadowOf(notificationManager).getNotification(id))
}
