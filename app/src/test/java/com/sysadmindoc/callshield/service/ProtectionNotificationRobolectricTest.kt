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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProtectionNotificationRobolectricTest {
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
    fun `lost screening role posts one actionable protection-health notification`() {
        assertTrue(NotificationHelper.notifyCallScreeningRoleLost(context))

        val notification =
            requireNotNull(
                shadowOf(notificationManager).getNotification(NotificationHelper.PROTECTION_HEALTH_NOTIFICATION_ID),
            )
        assertEquals(
            context.getString(R.string.notif_role_lost_title),
            notification.extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals(
            context.getString(R.string.notif_role_lost_text),
            notification.extras.getString(Notification.EXTRA_TEXT),
        )
        assertEquals(
            listOf(context.getString(R.string.notif_role_lost_action)),
            notification.actions.map { it.title.toString() },
        )
    }
}
