package com.sysadmindoc.callshield.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.sysadmindoc.callshield.ui.screens.main.REPEAT_BADGE_TINT
import com.sysadmindoc.callshield.ui.screens.main.RULE_CONFLICT_TINT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test
    fun `the rule-conflict warning keeps AA over its dialog in every theme`() {
        listOf(AppThemeMode.Light, AppThemeMode.Graphite, AppThemeMode.Amoled).forEach { mode ->
            val palette = paletteFor(mode, systemDark = false)
            // The add-rule dialogs sit on surfaceBright.
            val ratio = contrastRatio(palette.warning, palette.warning.copy(alpha = RULE_CONFLICT_TINT).compositeOver(palette.surfaceBright))
            assertTrue("$mode rule-conflict warning: $ratio", ratio >= 4.5f)
        }
    }

    @Test
    fun `no chip draws its accent as text on its own heavy tint`() {
        // A selected chip tints itself with an accent. The same accent as its
        // label fell to 4.33:1 in Light on a 20% tint (the log-cleanup chip),
        // so from 15% up a chip's label is body text.
        val calls =
            File("src/main/java")
                .walk()
                .filter { it.extension == "kt" }
                .flatMap { file -> chipColorArguments(file.readText()).map { file.name to it } }
                .toList()
        // The app has nine chip color calls; finding far fewer means the scan broke.
        assertTrue("found only ${calls.size} chip color calls", calls.size >= 8)

        assertEquals(emptyList<String>(), calls.flatMap { (name, arguments) -> accentOnOwnTint(arguments).map { "$name: $it" } })
    }

    @Test
    fun `the chip check catches every way of writing an accent label on its own tint`() {
        listOf(
            "selectedContainerColor = CatGreen.copy(alpha = 0.2f), selectedLabelColor = CatGreen",
            "selectedContainerColor = CatGreen.copy(0.2F), selectedLabelColor = CatGreen",
            "selectedLabelColor = CatPeach, selectedContainerColor = CatPeach.copy(alpha = CHIP_TINT)",
            "selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), selectedLabelColor = CatGreen",
            "containerColor = CatRed.copy(alpha = 0.2f), labelColor = CatRed",
        ).forEach { assertTrue(it, accentOnOwnTint(it).isNotEmpty()) }
        listOf(
            "selectedContainerColor = CatGreen.copy(alpha = 0.2f), selectedLabelColor = CatText",
            "selectedContainerColor = option.color.copy(alpha = 0.1f), selectedLabelColor = option.color",
            "selectedContainerColor = SurfaceBright, selectedLabelColor = CatGreen",
        ).forEach { assertTrue(it, accentOnOwnTint(it).isEmpty()) }
        // Every chip's colors function counts, not only the filter chip's.
        assertEquals(1, chipColorArguments("FilterChipDefaults.elevatedFilterChipColors(selectedLabelColor = CatText)").size)
        assertEquals(1, chipColorArguments("InputChipDefaults.inputChipColors(selectedLabelColor = CatText)").size)
    }

    /** Each label in one chip colors call's [arguments] that is drawn in the accent its container is tinted with. */
    private fun accentOnOwnTint(arguments: String): List<String> =
        listOf("selectedContainerColor" to "selectedLabelColor", "containerColor" to "labelColor").mapNotNull { (container, label) ->
            val tint = Regex("""\b$container\s*=\s*([\w.]+?)\.copy\(\s*(?:alpha\s*=\s*)?([^,)]+?)\s*\)""").find(arguments)
            val text = Regex("""\b$label\s*=\s*([\w.]+)""").find(arguments)?.groupValues?.get(1)
            if (tint == null || text == null) return@mapNotNull null
            // A named alpha can't be read here, so it counts as heavy.
            val alpha = tint.groupValues[2].trimEnd('f', 'F').toFloatOrNull()
            "$text on its own ${tint.groupValues[2]} tint".takeIf {
                sameColor(tint.groupValues[1], text) && (alpha == null || alpha >= 0.15f)
            }
        }

    private fun sameColor(
        first: String,
        second: String,
    ): Boolean = (SCHEME_ACCENTS[first] ?: first) == (SCHEME_ACCENTS[second] ?: second)

    /** The arguments of every chip colors call (`filterChipColors(`, `elevatedFilterChipColors(`, `inputChipColors(`...) in [source]. */
    private fun chipColorArguments(source: String): List<String> =
        Regex("""[A-Za-z]*[Cc]hipColors\(""")
            .findAll(source)
            .map { call ->
                val start = call.range.last + 1
                var depth = 1
                var end = start
                while (depth > 0 && end < source.length) {
                    when (source[end]) {
                        '(' -> depth++
                        ')' -> depth--
                    }
                    end++
                }
                source.substring(start, end - 1)
            }.toList()

    private companion object {
        /** Accents the Material color scheme also exposes under its own names. */
        val SCHEME_ACCENTS =
            mapOf(
                "MaterialTheme.colorScheme.primary" to "CatGreen",
                "MaterialTheme.colorScheme.secondary" to "CatBlue",
                "MaterialTheme.colorScheme.tertiary" to "CatMauve",
                "MaterialTheme.colorScheme.error" to "CatRed",
            )
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
