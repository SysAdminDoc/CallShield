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
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.telecom.CallRedirectionService
import android.telecom.PhoneAccountHandle
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.OutgoingCallGuard
import com.sysadmindoc.callshield.data.PhoneIdentityCanonicalizer
import com.sysadmindoc.callshield.ui.CallAnywayActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutgoingCallHoldRobolectricTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val number = "+19005550123"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var fixture: IsolatedRepositoryFixture

    @Before
    fun setUp() {
        shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationHelper.createChannels(context)
        notificationManager.cancelAll()
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        OutgoingCallGuard.resetForTests()
        fixture = IsolatedRepositoryFixture(context)
        runBlocking { fixture.repository.setOutgoingCallHold(true) }
    }

    @After
    fun tearDown() {
        scope.cancel()
        fixture.close()
        notificationManager.cancelAll()
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        OutgoingCallGuard.resetForTests()
        PhoneIdentityCanonicalizer.resetCacheForTests()
    }

    /** Telecom's end of one outgoing call: records how CallShield answered it. */
    private class TelecomCall {
        val answers = CopyOnWriteArrayList<String>()
        val adapter: Any =
            Proxy.newProxyInstance(ADAPTER.classLoader, arrayOf(ADAPTER)) { proxy, method, args ->
                when (method.name) {
                    "asBinder" -> null
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "TelecomCall"
                    else -> answers.add(method.name).let { null }
                }
            }
    }

    private fun redirectionService() =
        Robolectric.setupService(CallShieldRedirectionService::class.java).also {
            it.repository = fixture.repository
            it.scope = scope
        }

    /** Hands a call to the service the way Telecom does, through its binder. */
    private fun CallShieldRedirectionService.place(
        dialed: String,
        call: TelecomCall,
        interactive: Boolean = true,
    ) {
        val binder = onBind(Intent(CallRedirectionService.SERVICE_INTERFACE))
        val account = PhoneAccountHandle(ComponentName(context, "Telecom"), "sim")
        PLACE_CALL.invoke(binder, call.adapter, Uri.fromParts("tel", dialed, null), account, interactive)
    }

    private fun TelecomCall.awaitAnswers(): List<String> {
        // Wall time: Robolectric's SystemClock only moves when a test moves it.
        val deadline = System.nanoTime() + 10_000_000_000L
        while (answers.isEmpty() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10L)
        }
        return answers.toList()
    }

    /** Gives a check that is still running time to answer, if it (wrongly) would. */
    private fun settle() =
        repeat(50) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10L)
        }

    @Test
    fun `a call to a premium-rate number is held and the notification says why`() {
        val call = TelecomCall()
        redirectionService().place(number, call)

        assertEquals(listOf("cancelCall"), call.awaitAnswers())
        val notification = shadowOf(notificationManager).allNotifications.single()
        assertEquals(context.getString(R.string.outgoing_hold_reason_premium), notification.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `with Do Not Disturb on, a call that would be held goes through`() {
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        val call = TelecomCall()
        redirectionService().place(number, call)

        assertEquals(listOf("placeCallUnmodified"), call.awaitAnswers())
        settle()
        assertEquals(listOf("placeCallUnmodified"), call.answers)
        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
    }

    @Test
    fun `in car mode a call that would be held goes through`() {
        val call = TelecomCall()
        redirectionService().place(number, call, interactive = false)

        assertEquals(listOf("placeCallUnmodified"), call.awaitAnswers())
        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
    }

    @Test
    fun `an earlier call's answer never lands on the call placed after it`() {
        val service = redirectionService()
        val first = TelecomCall()
        val second = TelecomCall()
        // The second call arrives before the first is answered, which gives it
        // the first call's reply channel. The first call's check finishes
        // quickly (car mode places it); the second one is held.
        service.place("+12125550123", first, interactive = false)
        service.place(number, second)

        assertEquals(listOf("cancelCall"), second.awaitAnswers())
        settle()
        assertEquals(listOf("cancelCall"), second.answers)
        assertEquals(emptyList<String>(), first.answers)
        assertEquals(1, shadowOf(notificationManager).allNotifications.size)
    }

    @Test
    fun `a hold waits for a notification that pops up through Do Not Disturb`() {
        val channel = notificationManager.getNotificationChannel(NotificationHelper.CHANNEL_OUTGOING_HOLD)
        assertTrue(NotificationHelper.canShowOutgoingHold(context))

        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        assertFalse("Do Not Disturb hides it", NotificationHelper.canShowOutgoingHold(context))
        channel.setBypassDnd(true)
        assertTrue("the channel may break through", NotificationHelper.canShowOutgoingHold(context))
        channel.setBypassDnd(false)
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        assertTrue(NotificationHelper.canShowOutgoingHold(context))

        channel.importance = NotificationManager.IMPORTANCE_DEFAULT
        assertFalse("below high importance it doesn't pop up over the dialer", NotificationHelper.canShowOutgoingHold(context))
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

    private companion object {
        // Telecom's side of the redirection binder is hidden API, so the test
        // reaches it the way the framework does, by interface.
        val ADAPTER: Class<*> = Class.forName("com.android.internal.telecom.ICallRedirectionAdapter")
        val PLACE_CALL: Method =
            Class
                .forName("com.android.internal.telecom.ICallRedirectionService")
                .getMethod("placeCall", ADAPTER, Uri::class.java, PhoneAccountHandle::class.java, Boolean::class.javaPrimitiveType)
    }
}
