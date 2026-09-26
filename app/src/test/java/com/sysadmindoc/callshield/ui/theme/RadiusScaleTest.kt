package com.sysadmindoc.callshield.ui.theme

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.service.OVERLAY_CORNER_DP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Corner radii the product allows, in dp. */
private val CORNER_SCALE = setOf(0f, 4f, 6f, 8f, 10f, 12f)

/**
 * The product bans pill and oval backdrops: corners come from the 0/4/6/8/10/12dp
 * scale. The 2026-09-26 mockup pass brought back 20 and 24dp chips, an 18dp
 * card and 42dp circular icon backdrops while every gate stayed green, so the
 * sources are scanned here.
 */
class RadiusScaleTest {
    @Test
    fun `the shared shape tokens stay on the scale`() {
        listOf(ShapeXs, ShapeSm, ShapeMd, ShapeLg, ShapeXl).forEach { assertTrue("$it", it <= 12.dp) }
    }

    @Test
    fun `Material component defaults use the scale too`() {
        // Dialogs and bottom sheets take their corners from MaterialTheme.shapes,
        // which default to 28dp and never appear in a source scan.
        val shapes = with(CallShieldShapes) { listOf(extraSmall, small, medium, large, extraLarge) }
        shapes.forEach { shape ->
            listOf(shape.topStart, shape.topEnd, shape.bottomEnd, shape.bottomStart).forEach { corner ->
                assertTrue("$shape", corner.toPx(Size(1000f, 1000f), Density(1f)) <= 12f)
            }
        }
    }

    @Test
    fun `no source rounds a corner past the scale`() {
        val sources = mainSources()
        // Dozens of Compose files use rounded shapes; finding few means the scan broke.
        assertTrue("found only ${sources.size} sources", sources.size >= 30)
        assertEquals(emptyList<String>(), sources.flatMap { (name, text) -> radiusViolations(text).map { "$name: $it" } })
    }

    @Test
    fun `the caller-ID overlay draws its corners on the scale`() {
        // The overlay is a View, not Compose, so the source scan can't see it.
        // It rounded its card at 16dp until 2026-09-26.
        assertTrue("$OVERLAY_CORNER_DP", OVERLAY_CORNER_DP in CORNER_SCALE)
        val overlay = mainSources().single { it.first == "CallerIdOverlayService.kt" }.second
        assertEquals(emptyList<String>(), Regex("""overlayDpF\((\d+(?:\.\d+)?)f\)""").findAll(overlay).map { it.value }.toList())
    }

    @Test
    fun `circles stay small dots and swatches`() {
        assertEquals(emptyList<String>(), mainSources().flatMap { (name, text) -> largeCircles(text).map { "$name: $it" } })
    }

    @Test
    fun `progress bars are flat`() {
        // Material's default round caps, gap and stop dot make a thin bar a pill.
        val bars =
            mainSources().flatMap { (name, text) ->
                Regex("""LinearProgressIndicator\(""").findAll(text).map { name to callArguments(text, it.range.last) }.toList()
            }
        assertTrue("found only ${bars.size} progress bars", bars.size >= 4)
        assertEquals(emptyList<String>(), bars.filterNot { (_, call) -> "StrokeCap.Butt" in call }.map { it.first })
    }

    @Test
    fun `the scans catch each way of writing a pill`() {
        listOf(
            "RoundedCornerShape(24.dp)",
            "RoundedCornerShape(12.5.dp)",
            "RoundedCornerShape(percent = 50)",
            "RoundedCornerShape(50)",
            "RoundedCornerShape(topStart = 16.dp, topEnd = 8.dp)",
            // Off the scale without being large: 3dp on a 6dp bar is a pill.
            "RoundedCornerShape(3.dp)",
            "RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)",
        ).forEach { assertTrue(it, radiusViolations(it).isNotEmpty()) }
        listOf(
            "RoundedCornerShape(12.dp)",
            "RoundedCornerShape(ShapeXl)",
            "RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)",
            "RoundedCornerShape(0)",
        ).forEach { assertTrue(it, radiusViolations(it).isEmpty()) }
        assertTrue(largeCircles("Modifier\n    .size(44.dp)\n    .clip(CircleShape)").isNotEmpty())
        assertTrue(largeCircles("Modifier.size(if (selected) 20.dp else 14.dp).background(color, CircleShape)").isEmpty())
    }

    private fun mainSources(): List<Pair<String, String>> =
        File("src/main/java")
            .walk()
            .filter { it.extension == "kt" }
            .map { it.name to it.readText() }
            .toList()

    /** Each `RoundedCornerShape(...)` in [source] with a corner off the 0/4/6/8/10/12dp scale or given as a percentage. */
    private fun radiusViolations(source: String): List<String> =
        Regex("""RoundedCornerShape\(([^()]*)\)""")
            .findAll(source)
            .mapNotNull { call ->
                val arguments = call.groupValues[1]
                val tooRound = Regex("""(\d+(?:\.\d+)?)\.dp""").findAll(arguments).any { it.groupValues[1].toFloat() !in CORNER_SCALE }
                val percent = "percent" in arguments || Regex("""^\s*([1-9]\d*)\s*$""").matches(arguments)
                call.value.takeIf { tooRound || percent }
            }.toList()

    /** The text between the parenthesis at [open] and the one that closes it. */
    private fun callArguments(
        source: String,
        open: Int,
    ): String {
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return source.substring(open + 1, index)
            }
        }
        return source.substring(open + 1)
    }

    /** Each `CircleShape` in [source] drawn on something larger than a 24dp dot or swatch. */
    private fun largeCircles(source: String): List<String> =
        Regex("""CircleShape""")
            .findAll(source)
            .mapNotNull { match ->
                val before = source.substring(maxOf(0, match.range.first - 400), match.range.first)
                if (before.trimEnd().endsWith("import androidx.compose.foundation.shape.")) return@mapNotNull null
                val size = Regex("""\.size\(([^()]*(?:\([^()]*\)[^()]*)*)\)""").findAll(before).lastOrNull() ?: return@mapNotNull null
                val largest = Regex("""(\d+(?:\.\d+)?)\.dp""").findAll(size.groupValues[1]).maxOfOrNull { it.groupValues[1].toFloat() }
                "CircleShape on ${size.value}".takeIf { largest != null && largest > 24f }
            }.toList()
}
