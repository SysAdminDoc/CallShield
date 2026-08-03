package com.sysadmindoc.callshield.service

import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.ui.theme.AppThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerIdOverlayPrivacyTest {
    @Test
    fun `live caller enrichment is disabled without opt in`() {
        assertFalse(shouldRunLiveCallerEnrichment(confidence = 45, optedIn = false))
    }

    @Test
    fun `live caller enrichment rejects clean local calls even when enabled`() {
        assertFalse(shouldRunLiveCallerEnrichment(confidence = 0, optedIn = true))
    }

    @Test
    fun `live caller enrichment accepts opted in locally suspicious calls`() {
        assertTrue(shouldRunLiveCallerEnrichment(confidence = 45, optedIn = true))
    }

    @Test
    fun `heuristic overlay reasons use localized resources`() {
        assertEquals(R.string.overlay_reason_neighbor_spoof, overlayReasonLabelRes("neighbor_spoof"))
        assertEquals(R.string.overlay_reason_hot_campaign, overlayReasonLabelRes(" hot_campaign_range "))
    }

    @Test
    fun `unknown internal reason is recognized while human detail is preserved`() {
        assertTrue(looksLikeInternalOverlayReason("experimental_signal_v2"))
        assertFalse(looksLikeInternalOverlayReason("Washington, DC"))
    }

    @Test
    fun `light overlay uses a distinct light palette`() {
        val light = overlayPaletteFor(AppThemeMode.Light, systemDark = false)
        val graphite = overlayPaletteFor(AppThemeMode.Graphite, systemDark = false)

        assertTrue(light.isLight)
        assertFalse(graphite.isLight)
        assertNotEquals(light.background, graphite.background)
        assertNotEquals(light.text, graphite.text)
        assertEquals(0xF5, light.background ushr 24)
    }

    @Test
    fun `system overlay follows current night mode`() {
        assertEquals(
            overlayPaletteFor(AppThemeMode.Light, systemDark = false),
            overlayPaletteFor(AppThemeMode.System, systemDark = false),
        )
        assertEquals(
            overlayPaletteFor(AppThemeMode.Graphite, systemDark = true),
            overlayPaletteFor(AppThemeMode.System, systemDark = true),
        )
    }
}
