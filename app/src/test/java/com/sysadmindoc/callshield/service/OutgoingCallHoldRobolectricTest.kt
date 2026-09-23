package com.sysadmindoc.callshield.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.telecom.CallRedirectionService
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.OutgoingCallGuard
import com.sysadmindoc.callshield.data.PhoneIdentityCanonicalizer
import com.sysadmindoc.callshield.ui.CallAnywayActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutgoingCallHoldRobolectricTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val number = "+19005550123"

    @Before
    fun setUp() {
        shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationHelper.createChannels(context)
        notificationManager.cancelAll()
        OutgoingCallGuard.resetForTests()
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
        OutgoingCallGuard.resetForTests()
        PhoneIdentityCanonicalizer.resetCacheForTests()
    }

    @Test
    fun `a held call says why and offers call anyway`() {
        assertTrue(NotificationHelper.notifyOutgoingCallHeld(context, number, OutgoingCallGuard.Reason.PREMIUM_RATE))

        val notification = shadowOf(notificationManager).allNotifications.single()
        assertEquals(NotificationHelper.CHANNEL_OUTGOING_HOLD, notification.channelId)
        assertEquals(
            context.getString(R.string.outgoing_hold_reason_premium),
            notification.extras.getString(Notification.EXTRA_TEXT),
        )
        val action = notification.actions.single()
        assertEquals(context.getString(R.string.notif_outgoing_call_anyway), action.title.toString())
        val tap = shadowOf(action.actionIntent).savedIntent
        assertEquals(CallAnywayActivity::class.java.name, tap.component?.className)
        assertEquals(number, tap.getStringExtra(CallAnywayActivity.EXTRA_NUMBER))
    }

    @Test
    fun `call anyway lets the number through and opens the dialer with it`() {
        val intent =
            Intent(context, CallAnywayActivity::class.java)
                .putExtra(CallAnywayActivity.EXTRA_NUMBER, number)
        val activity = Robolectric.buildActivity(CallAnywayActivity::class.java, intent).create().get()

        assertTrue(OutgoingCallGuard.isBypassed(number, SystemClock.elapsedRealtime()))
        val dial = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_DIAL, dial.action)
        assertEquals(number, dial.data?.schemeSpecificPart)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `a hold only happens when its notification can reach the user`() {
        assertTrue(NotificationHelper.canShowOutgoingHold(context))

        shadowOf(notificationManager).setNotificationsEnabled(false)
        assertFalse("app notifications off", NotificationHelper.canShowOutgoingHold(context))
        shadowOf(notificationManager).setNotificationsEnabled(true)

        val channel = notificationManager.getNotificationChannel(NotificationHelper.CHANNEL_OUTGOING_HOLD)
        notificationManager.deleteNotificationChannel(NotificationHelper.CHANNEL_OUTGOING_HOLD)
        notificationManager.createNotificationChannel(NotificationChannel(channel.id, channel.name, NotificationManager.IMPORTANCE_NONE))
        assertFalse("hold channel blocked", NotificationHelper.canShowOutgoingHold(context))
        NotificationHelper.createChannels(context)

        shadowOf(context.applicationContext as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse("permission denied", NotificationHelper.canShowOutgoingHold(context))
    }

    @Test
    fun `call anyway on a national-format number also covers its plus-one redial`() {
        shadowOf(context.getSystemService(TelephonyManager::class.java)).setSimCountryIso("us")
        PhoneIdentityCanonicalizer.resetCacheForTests()
        val intent =
            Intent(context, CallAnywayActivity::class.java)
                .putExtra(CallAnywayActivity.EXTRA_NUMBER, "6495550123")
        Robolectric.buildActivity(CallAnywayActivity::class.java, intent).create().get()

        val now = SystemClock.elapsedRealtime()
        assertTrue(OutgoingCallGuard.isBypassed(OutgoingCallGuard.bypassKey("+16495550123", "US"), now))
        assertTrue(OutgoingCallGuard.isBypassed(OutgoingCallGuard.bypassKey("16495550123", "US"), now))
    }

    @Test
    fun `Telecom can bind the redirection service only through its permission`() {
        val service =
            context.packageManager.getServiceInfo(
                ComponentName(context, CallShieldRedirectionService::class.java),
                PackageManager.GET_META_DATA,
            )
        val resolved =
            context.packageManager.queryIntentServices(
                Intent(CallRedirectionService.SERVICE_INTERFACE).setPackage(context.packageName),
                0,
            )

        assertEquals(Manifest.permission.BIND_CALL_REDIRECTION_SERVICE, service.permission)
        assertTrue(service.exported)
        assertEquals(listOf(CallShieldRedirectionService::class.java.name), resolved.map { it.serviceInfo.name })
    }
}
