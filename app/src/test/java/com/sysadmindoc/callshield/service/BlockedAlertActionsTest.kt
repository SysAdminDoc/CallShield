package com.sysadmindoc.callshield.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.domain.model.BlockReasonCode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A blocked call's alert offered "Block forever" for a number that was already
 * blocked, and nothing to let a real caller through.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BlockedAlertActionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun setUp() {
        shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationHelper.createChannels(context)
        NotificationHelper.clearBlockedSummaryCount()
        NotificationHelper.resetRateLimitForTest()
        notificationManager.cancelAll()
    }

    @After
    fun tearDown() {
        NotificationHelper.clearBlockedSummaryCount()
        notificationManager.cancelAll()
    }

    private fun postedAlert(): Notification = shadowOf(notificationManager).allNotifications.single { it.flags and Notification.FLAG_GROUP_SUMMARY == 0 }

    @Test
    fun `a blocked call's alert offers Not spam instead of Block forever`() {
        NotificationHelper.notifyBlocked(context, "+12125550111", "database", isCall = true)

        val alert = postedAlert()
        assertEquals(listOf("Not spam", "Report"), alert.actions.map { it.title.toString() })
        val notSpam = shadowOf(alert.actions[0].actionIntent).savedIntent
        assertEquals(NotificationHelper.ACTION_NOT_SPAM, notSpam.action)
        assertEquals("database", notSpam.getStringExtra(NotificationHelper.EXTRA_REASON_CODE))
    }

    @Test
    fun `a flagged text's alert still offers Block forever`() {
        NotificationHelper.notifyBlocked(context, "+12125550112", "sms_content", isCall = false, smsBody = "Claim your prize")

        val titles = postedAlert().actions.map { it.title.toString() }
        assertTrue(titles.toString(), "Block forever" in titles)
        assertFalse(titles.toString(), "Not spam" in titles)
    }

    @Test
    fun `Not spam reaches the community database only for a shared-data block`() {
        assertTrue(NotificationHelper.notSpamReachesCommunity(BlockReasonCode.DATABASE))
        assertTrue(NotificationHelper.notSpamReachesCommunity(BlockReasonCode.HOT_LIST))
        assertFalse(NotificationHelper.notSpamReachesCommunity(BlockReasonCode.HEURISTIC))
        assertFalse(NotificationHelper.notSpamReachesCommunity(BlockReasonCode.USER_BLOCKLIST))
        assertFalse(NotificationHelper.notSpamReachesCommunity(BlockReasonCode.ML_SCORER))
    }
}
