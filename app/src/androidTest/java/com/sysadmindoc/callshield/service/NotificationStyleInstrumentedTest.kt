package com.sysadmindoc.callshield.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationStyleInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun setUp() {
        InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        NotificationHelper.createChannels(context)
        notificationManager.cancelAll()
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
    }

    @Test
    fun notificationStylesMatchPlatformLevel() {
        NotificationHelper.showSyncProgress(context)

        val sync = postedNotification(NotificationHelper.SYNC_NOTIFICATION_ID)
        assertTrue(sync.flags and Notification.FLAG_ONGOING_EVENT != 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            assertEquals(PROGRESS_STYLE_TEMPLATE, sync.extras.getString(Notification.EXTRA_TEMPLATE))
        } else {
            assertNotEquals(PROGRESS_STYLE_TEMPLATE, sync.extras.getString(Notification.EXTRA_TEMPLATE))
            assertEquals(true, sync.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        }
        if (InstrumentationRegistry.getArguments().getString("visualNotification") == "true") {
            Thread.sleep(VISUAL_REVIEW_WINDOW_MS)
        }
    }

    private fun postedNotification(id: Int): Notification {
        repeat(20) {
            notificationManager
                .activeNotifications
                .singleOrNull { it.id == id }
                ?.notification
                ?.let { return it }
            Thread.sleep(100)
        }
        error("Notification $id was not posted")
    }

    private companion object {
        const val PROGRESS_STYLE_TEMPLATE = "android.app.Notification\$ProgressStyle"
        const val VISUAL_REVIEW_WINDOW_MS = 30_000L
    }
}
