package com.sysadmindoc.callshield.service

import android.Manifest
import android.app.Application
import android.os.Looper
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.OutgoingRiskWarning
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder
import org.robolectric.shadows.ShadowWindowManagerImpl
import org.robolectric.util.ReflectionHelpers

/**
 * On a dual-SIM phone the default manager's first call-state report is the
 * default SIM's own state. A call ringing on the other SIM reads IDLE there,
 * which used to close its overlay the moment it opened, so the overlay
 * watches each SIM and closes once every one of them is idle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallerIdOverlayLineTest {
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val phone = application.getSystemService(TelephonyManager::class.java)
    private val subscriptions = application.getSystemService(SubscriptionManager::class.java)
    private val simOne: TelephonyManager = Shadow.newInstanceOf(TelephonyManager::class.java)
    private val simTwo: TelephonyManager = Shadow.newInstanceOf(TelephonyManager::class.java)
    private lateinit var controller: ServiceController<CallerIdOverlayService>
    private var startId = 0

    @Before
    fun setUp() {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(application).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        shadowOf(phone).setTelephonyManagerForSubscriptionId(1, simOne)
        shadowOf(phone).setTelephonyManagerForSubscriptionId(2, simTwo)
        // The default SIM is idle, as it is while a call rings on the other one.
        shadowOf(phone).setCallState(TelephonyManager.CALL_STATE_IDLE)
        controller = Robolectric.buildService(CallerIdOverlayService::class.java).create()
    }

    @After
    fun tearDown() {
        controller.destroy()
    }

    @Test
    fun `a call on the second SIM keeps its overlay until the call ends`() {
        assertSecondSimCallKeepsOverlay()
    }

    @Test
    @Config(sdk = [30])
    fun `before Android 12 a call on the second SIM keeps its overlay until the call ends`() {
        assertSecondSimCallKeepsOverlay()
    }

    @Test
    fun `a call on the first SIM of a dual-SIM phone closes when it ends`() {
        activeSims(2)
        shadowOf(simOne).setCallState(TelephonyManager.CALL_STATE_RINGING)
        shadowOf(simTwo).setCallState(TelephonyManager.CALL_STATE_IDLE)
        openIncoming()
        assertEquals(1, overlayViews().size)

        shadowOf(simOne).setCallState(TelephonyManager.CALL_STATE_IDLE)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(overlayViews().isEmpty())
    }

    @Test
    fun `a dual-SIM call that ended before the overlay opened closes it at once`() {
        activeSims(2)
        shadowOf(simOne).setCallState(TelephonyManager.CALL_STATE_IDLE)
        shadowOf(simTwo).setCallState(TelephonyManager.CALL_STATE_IDLE)

        openIncoming()

        assertTrue(overlayViews().isEmpty())
    }

    @Test
    fun `an outgoing warning for a call on the second SIM closes when that call ends`() {
        activeSims(2)
        shadowOf(simOne).setCallState(TelephonyManager.CALL_STATE_IDLE)
        shadowOf(simTwo).setCallState(TelephonyManager.CALL_STATE_OFFHOOK)
        val warning = CallerIdOverlayService.outgoingRiskIntent(application, OutgoingRiskWarning(CALLER, "invalid_format", 80))
        controller.get().onStartCommand(warning, 0, ++startId)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, overlayViews().size)

        shadowOf(simTwo).setCallState(TelephonyManager.CALL_STATE_IDLE)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(overlayViews().isEmpty())
    }

    @Test
    fun `an outgoing warning shown while both SIMs still read idle waits for the call`() {
        activeSims(2)
        shadowOf(simOne).setCallState(TelephonyManager.CALL_STATE_IDLE)
        shadowOf(simTwo).setCallState(TelephonyManager.CALL_STATE_IDLE)
        val warning = CallerIdOverlayService.outgoingRiskIntent(application, OutgoingRiskWarning(CALLER, "invalid_format", 80))
        controller.get().onStartCommand(warning, 0, ++startId)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, overlayViews().size)

        shadowOf(simTwo).setCallState(TelephonyManager.CALL_STATE_OFFHOOK)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, overlayViews().size)

        shadowOf(simTwo).setCallState(TelephonyManager.CALL_STATE_IDLE)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(overlayViews().isEmpty())
    }

    @Test
    fun `each SIM's watcher goes when a new overlay replaces it and when the call ends`() {
        activeSims(2)
        shadowOf(simOne).setCallState(TelephonyManager.CALL_STATE_IDLE)
        shadowOf(simTwo).setCallState(TelephonyManager.CALL_STATE_RINGING)
        openIncoming()
        openIncoming()
        assertEquals(listOf(1, 1), listOf(watchers(simOne), watchers(simTwo)))

        shadowOf(simTwo).setCallState(TelephonyManager.CALL_STATE_IDLE)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(overlayViews().isEmpty())
        assertEquals(listOf(0, 0), listOf(watchers(simOne), watchers(simTwo)))
    }

    @Test
    fun `with one SIM an idle first report still closes the overlay`() {
        activeSims(1)

        openIncoming()

        assertTrue(overlayViews().isEmpty())
    }

    private fun assertSecondSimCallKeepsOverlay() {
        activeSims(2)
        shadowOf(simOne).setCallState(TelephonyManager.CALL_STATE_IDLE)
        shadowOf(simTwo).setCallState(TelephonyManager.CALL_STATE_RINGING)

        openIncoming()
        assertEquals(1, overlayViews().size)

        shadowOf(simTwo).setCallState(TelephonyManager.CALL_STATE_IDLE)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(overlayViews().isEmpty())
    }

    private fun activeSims(count: Int) {
        val sims =
            (1..count).map {
                SubscriptionInfoBuilder
                    .newBuilder()
                    .setId(it)
                    .setSimSlotIndex(it - 1)
                    .buildSubscriptionInfo()
            }
        shadowOf(subscriptions).setActiveSubscriptionInfos(*sims.toTypedArray())
    }

    private fun openIncoming() {
        CallerIdOverlayService.startIncoming(application, CALLER, 40, "invalid_format")
        val intent = shadowOf(application).nextStartedService ?: error("no service start was sent")
        controller.get().onStartCommand(intent, 0, ++startId)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun watchers(sim: TelephonyManager): Int = ReflectionHelpers.getField<List<*>>(shadowOf(sim), "telephonyCallbackRegistrations").size

    private fun overlayViews(): List<View> = Shadow.extract<ShadowWindowManagerImpl>(controller.get().getSystemService(WindowManager::class.java)).views

    private companion object {
        const val CALLER = "+12025550142"
    }
}
