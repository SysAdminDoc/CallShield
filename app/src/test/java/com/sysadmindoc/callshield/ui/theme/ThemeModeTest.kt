package com.sysadmindoc.callshield.ui.theme

import android.app.UiModeManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {
    @Test
    fun `explicit themes choose matching splash resource family`() {
        assertFalse(
            shouldUseDarkApplicationResources(
                AppThemeMode.Light,
                UiModeManager.MODE_NIGHT_YES,
                systemConfigurationDark = true,
            ),
        )
        assertTrue(
            shouldUseDarkApplicationResources(
                AppThemeMode.Graphite,
                UiModeManager.MODE_NIGHT_NO,
                systemConfigurationDark = false,
            ),
        )
        assertTrue(
            shouldUseDarkApplicationResources(
                AppThemeMode.Amoled,
                UiModeManager.MODE_NIGHT_NO,
                systemConfigurationDark = false,
            ),
        )
    }

    @Test
    fun `system theme follows the device night mode`() {
        assertTrue(
            shouldUseDarkApplicationResources(
                AppThemeMode.System,
                UiModeManager.MODE_NIGHT_YES,
                systemConfigurationDark = false,
            ),
        )
        assertFalse(
            shouldUseDarkApplicationResources(
                AppThemeMode.System,
                UiModeManager.MODE_NIGHT_NO,
                systemConfigurationDark = true,
            ),
        )
        assertTrue(
            shouldUseDarkApplicationResources(
                AppThemeMode.System,
                UiModeManager.MODE_NIGHT_AUTO,
                systemConfigurationDark = true,
            ),
        )
    }
}
