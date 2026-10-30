package com.sysadmindoc.callshield.service

import android.app.Application
import android.content.Intent
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
import org.robolectric.shadows.ShadowWindowManagerImpl

/**
 * A call screening rejects never rings, so no call-state change closes an
 * overlay a checker opened for it mid-screening. These cover the withdrawal
 * that closes it instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallerIdOverlayWithdrawTest {
    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var controller: ServiceController<CallerIdOverlayService>
    private var startId = 0

    @Before
    fun setUp() {
        ShadowSettings.setCanDrawOverlays(true)
        controller = Robolectric.buildService(CallerIdOverlayService::class.java).create()
    }

    @After
    fun tearDown() {
        controller.destroy()
    }

    @Test
    fun `withdrawing the shown caller's overlay removes it`() {
        CallerIdOverlayService.startIncoming(application, CALLER, 40, "invalid_format")
        deliverNextStart()
        assertEquals(1, overlayViews().size)

        CallerIdOverlayService.withdrawIncoming(application, CALLER)
        deliverNextStart()

        assertTrue(overlayViews().isEmpty())
    }

    @Test
    fun `a withdrawal for an earlier caller leaves the newer overlay up`() {
        CallerIdOverlayService.startIncoming(application, CALLER, 40, "invalid_format")
        deliverNextStart()
        CallerIdOverlayService.startIncoming(application, OTHER_CALLER, 40, "invalid_format")
        deliverNextStart()

        CallerIdOverlayService.withdrawIncoming(application, CALLER)

        assertNull(shadowOf(application).nextStartedService)
        assertEquals(1, overlayViews().size)
    }

    @Test
    fun `the service only withdraws the overlay of the caller it shows`() {
        CallerIdOverlayService.startIncoming(application, CALLER, 40, "invalid_format")
        deliverNextStart()

        controller.get().onStartCommand(withdrawIntent(OTHER_CALLER), 0, ++startId)

        assertEquals(1, overlayViews().size)
    }

    @Test
    fun `closing an overlay stops the service only for the newest start`() {
        CallerIdOverlayService.startIncoming(application, CALLER, 40, "invalid_format")
        deliverNextStart()
        CallerIdOverlayService.withdrawIncoming(application, CALLER)
        deliverNextStart()

        // Android ignores stopSelf(id) when a start newer than id is queued,
        // so the next caller's overlay can't be torn down as it shows.
        assertEquals(startId, shadowOf(controller.get()).stopSelfId)
        assertTrue(overlayViews().isEmpty())
    }

    @Test
    fun `a withdrawal with nothing shown stops the service`() {
        controller.get().onStartCommand(withdrawIntent(CALLER), 0, ++startId)

        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertTrue(overlayViews().isEmpty())
    }

    private fun deliverNextStart() {
        val intent = shadowOf(application).nextStartedService ?: error("no service start was sent")
        controller.get().onStartCommand(intent, 0, ++startId)
    }

    private fun withdrawIntent(number: String): Intent =
        Intent(application, CallerIdOverlayService::class.java)
            .setAction(CallerIdOverlayService.ACTION_WITHDRAW)
            .putExtra("number", number)

    private fun overlayViews(): List<View> = Shadow.extract<ShadowWindowManagerImpl>(controller.get().getSystemService(WindowManager::class.java)).views

    private companion object {
        const val CALLER = "+12025550142"
        const val OTHER_CALLER = "+12125550143"
    }
}
