package com.sysadmindoc.callshield.service

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.UnknownCallWindow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** The Quick Settings tile is the fastest way to turn protection off, so it waits like the app's switches do. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProtectionHoldTileTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fixture: IsolatedRepositoryFixture

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        ProtectionHold.resetForTests()
        UnknownCallWindow.shared.clear()
        fixture = IsolatedRepositoryFixture(context)
        SpamRepository.replaceInstanceForTests(fixture.repository)
    }

    @After
    fun tearDown() {
        UnknownCallWindow.shared.clear()
        ProtectionHold.resetForTests()
        SpamRepository.replaceInstanceForTests(null)
        fixture.close()
    }

    @Test
    fun `the tile turning protection off during a stranger's call waits and says when`() {
        UnknownCallWindow.shared.callStarted("+12129460197")
        val tile = Robolectric.buildService(CallShieldTileService::class.java).create().get()

        tile.onClick()
        val deadline = System.currentTimeMillis() + 10_000L
        while (ShadowToast.getTextOfLatestToast() == null) {
            check(System.currentTimeMillis() < deadline) { "The tile never said when" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }

        assertEquals(context.getString(R.string.protection_hold_after_call), ShadowToast.getTextOfLatestToast())
        assertEquals(
            setOf(ProtectionHold.Switch.BLOCK_CALLS, ProtectionHold.Switch.BLOCK_SMS),
            ProtectionHold.pending(context).value.switchesOff,
        )
        assertTrue(runBlocking { fixture.repository.blockCallsEnabled.first() })
        assertTrue(runBlocking { fixture.repository.blockSmsEnabled.first() })
    }
}
