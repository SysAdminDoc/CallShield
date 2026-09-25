package com.sysadmindoc.callshield.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.sysadmindoc.callshield.ui.screens.main.REPEAT_BADGE_TINT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeModeTest {
    @Test
    fun `storage values round trip and invalid values use light default`() {
        AppThemeMode.entries.forEach { mode ->
            assertEquals(mode, AppThemeMode.fromStorage(mode.storageValue))
        }
        assertEquals(AppThemeMode.Light, AppThemeMode.fromStorage(null))
        assertEquals(AppThemeMode.Light, AppThemeMode.fromStorage("neon"))
    }

    @Test
    fun `system theme follows device brightness`() {
        assertTrue(paletteFor(AppThemeMode.System, systemDark = false).isLight)
        assertFalse(paletteFor(AppThemeMode.System, systemDark = true).isLight)
        assertEquals(
            paletteFor(AppThemeMode.Light, systemDark = true),
            paletteFor(AppThemeMode.System, systemDark = false),
        )
        assertEquals(
            paletteFor(AppThemeMode.Graphite, systemDark = false),
            paletteFor(AppThemeMode.System, systemDark = true),
        )
    }

    @Test
    fun `named themes remain visually distinct`() {
        val backgrounds =
            listOf(AppThemeMode.Light, AppThemeMode.Graphite, AppThemeMode.Amoled)
                .map { paletteFor(it, systemDark = false).background }

        assertEquals(backgrounds.size, backgrounds.distinct().size)
        assertNotEquals(
            paletteFor(AppThemeMode.Graphite, systemDark = false).surface,
            paletteFor(AppThemeMode.Amoled, systemDark = false).surface,
        )
    }

    @Test
    fun `theme text and primary actions meet WCAG AA contrast`() {
        listOf(AppThemeMode.Light, AppThemeMode.Graphite, AppThemeMode.Amoled).forEach { mode ->
            val palette = paletteFor(mode, systemDark = false)
            assertTrue("$mode text", contrastRatio(palette.text, palette.background) >= 4.5f)
            assertTrue("$mode secondary text", contrastRatio(palette.subtext, palette.background) >= 4.5f)
            assertTrue("$mode primary action", contrastRatio(palette.onPrimary, palette.primary) >= 4.5f)
        }
    }

    @Test
    fun `state and secondary text colors keep AA on every surface under a row tint`() {
        listOf(AppThemeMode.Light, AppThemeMode.Graphite, AppThemeMode.Amoled).forEach { mode ->
            val palette = paletteFor(mode, systemDark = false)
            val surfaces =
                listOf(palette.background, palette.surface, palette.surfaceVariant, palette.surfaceBright, palette.surfaceElevated)
            // Rows tint themselves 6% with their state color and show it as text on top.
            val stateColors =
                mapOf(
                    "primary" to palette.primary,
                    "error" to palette.error,
                    "blue" to palette.blue,
                    "warning" to palette.warning,
                    "mauve" to palette.mauve,
                    "peach" to palette.peach,
                    "teal" to palette.teal,
                    "lavender" to palette.lavender,
                    "subtext" to palette.subtext,
                )
            surfaces.forEachIndexed { index, surface ->
                stateColors.forEach { (name, color) ->
                    val ratio = contrastRatio(color, color.copy(alpha = 0.06f).compositeOver(surface))
                    assertTrue("$mode $name on surface #$index: $ratio", ratio >= 4.5f)
                }
                // Muted rows (a source that isn't installed) tint 3% with overlay.
                val overlay = contrastRatio(palette.overlay, palette.overlay.copy(alpha = 0.03f).compositeOver(surface))
                assertTrue("$mode overlay on surface #$index: $overlay", overlay >= 4.5f)
            }
        }
    }

    @Test
    fun `text on the heavier accent tints keeps AA in every theme`() {
        listOf(AppThemeMode.Light, AppThemeMode.Graphite, AppThemeMode.Amoled).forEach { mode ->
            val palette = paletteFor(mode, systemDark = false)
            // The Blocked log's repeat badge draws its count in its accent on the
            // badge tint. Five or more repeats sit on a card tinted red as well.
            val redCard = palette.error.copy(alpha = ACCENT_CARD_TINT).compositeOver(palette.background)
            mapOf(
                "red" to (palette.error to redCard),
                "peach" to (palette.peach to palette.surface),
                "warning" to (palette.warning to palette.surface),
            ).forEach { (name, colors) ->
                val (accent, card) = colors
                val ratio = contrastRatio(accent, accent.copy(alpha = REPEAT_BADGE_TINT).compositeOver(card))
                assertTrue("$mode $name repeat badge: $ratio", ratio >= 4.5f)
            }
            // Selected filter chips mark the choice with a green or blue tint of
            // up to 25% and keep body text on it.
            val surfaces =
                listOf(palette.background, palette.surface, palette.surfaceVariant, palette.surfaceBright, palette.surfaceElevated)
            mapOf("green" to palette.primary, "blue" to palette.blue).forEach { (name, accent) ->
                surfaces.forEachIndexed { index, surface ->
                    val ratio = contrastRatio(palette.text, accent.copy(alpha = 0.25f).compositeOver(surface))
                    assertTrue("$mode text on a selected $name chip over surface #$index: $ratio", ratio >= 4.5f)
                }
            }
        }
    }

    private fun contrastRatio(
        first: Color,
        second: Color,
    ): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
