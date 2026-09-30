package com.sysadmindoc.callshield.service

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowTileService

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ExpectingCallTileServiceTest {
    @Test
    fun `a tap on a locked phone opens the window only after the unlock`() {
        val tile = Robolectric.setupService(ExpectingCallTileService::class.java)
        var toggles = 0
        tile.toggleWindow = { toggles++ }
        Shadow.extract<ShadowTileService>(tile).setLocked(true)

        tile.onClick()

        // Robolectric's unlockAndRun unlocks, then runs; a direct toggle would leave the phone locked.
        assertFalse(tile.isLocked)
        assertEquals(1, toggles)
    }

    @Test
    fun `a tap on an unlocked phone toggles at once`() {
        val tile = Robolectric.setupService(ExpectingCallTileService::class.java)
        var toggles = 0
        tile.toggleWindow = { toggles++ }

        tile.onClick()

        assertEquals(1, toggles)
    }
}
