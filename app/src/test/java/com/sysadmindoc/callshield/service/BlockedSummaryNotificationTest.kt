package com.sysadmindoc.callshield.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.ui.ACTION_OPEN_BLOCKED_LOG
import com.sysadmindoc.callshield.ui.MainActivity
import com.sysadmindoc.callshield.ui.blockedLogRequestId
import com.sysadmindoc.callshield.ui.toLaunchRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BlockedSummaryNotificationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun setUp() {
        shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationHelper.createChannels(context)
        NotificationHelper.clearBlockedSummaryCount()
        notificationManager.cancelAll()
    }

    @After
    fun tearDown() {
        NotificationHelper.clearBlockedSummaryCount()
        notificationManager.cancelAll()
    }

    @Test
    fun `tapping the blocked summary opens the Blocked log`() {
        NotificationHelper.notifyBlocked(context, "+12125550101", "database", isCall = true)
        NotificationHelper.notifyBlocked(context, "+12125550102", "database", isCall = true)

        val summary =
            shadowOf(notificationManager).allNotifications.single {
                it.flags and Notification.FLAG_GROUP_SUMMARY != 0
            }
        val tap = shadowOf(requireNotNull(summary.contentIntent)).savedIntent

        assertEquals(MainActivity::class.java.name, tap.component?.className)
        assertEquals(ACTION_OPEN_BLOCKED_LOG, tap.action)
        assertEquals(9, tap.toLaunchRequest(nextId = 9).blockedLogRequestId())
    }
}
