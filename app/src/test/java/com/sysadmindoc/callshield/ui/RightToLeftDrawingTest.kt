package com.sysadmindoc.callshield.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.ui.screens.main.GroupedCallItem
import com.sysadmindoc.callshield.ui.screens.stats.RegionTileMap
import com.sysadmindoc.callshield.ui.screens.stats.WeeklyBarChart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Canvas drawing doesn't mirror with the layout, so these read the drawn pixels on both sides. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RightToLeftDrawingTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Draws straight to a bitmap, since captureToImage waits for a frame Robolectric never delivers. */
    private fun render(
        direction: LayoutDirection,
        content: @Composable () -> Unit,
    ): PixelMap {
        lateinit var root: View
        composeRule.setContent {
            root = LocalView.current
            CompositionLocalProvider(LocalLayoutDirection provides direction, content = content)
        }
        return composeRule.runOnIdle {
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            bitmap.asImageBitmap().toPixelMap()
        }
    }

    /** The mean column of the matching pixels, as a share of the width. */
    private fun PixelMap.centerOf(matches: (red: Float, green: Float, blue: Float) -> Boolean): Float {
        var columns = 0L
        var count = 0L
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = this[x, y]
                if (matches(pixel.red, pixel.green, pixel.blue)) {
                    columns += x
                    count++
                }
            }
        }
        assertTrue("nothing was drawn in the color this test looks for", count > 0)
        return columns.toFloat() / count / width
    }

    /** The column with the most warm pixels, as a share of the width. A log row's strip runs its full height. */
    private fun PixelMap.tallestWarmColumn(): Float {
        val warmPixels = IntArray(width)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = this[x, y]
                if (pixel.red - pixel.blue > 0.06f) warmPixels[x]++
            }
        }
        val tallest = warmPixels.indices.maxBy { warmPixels[it] }
        assertTrue("nothing warm was drawn", warmPixels[tallest] > 0)
        return tallest.toFloat() / width
    }

    private fun renderChart(direction: LayoutDirection): Float =
        render(direction) {
            Column(Modifier.fillMaxWidth()) {
                WeeklyBarChart(listOf("Sun" to 10, "Mon" to 0, "Tue" to 0, "Wed" to 0))
            }
        }.centerOf { red, green, blue -> green - red > 0.2f && green - blue > 0.1f }

    @Test
    fun `the first day's bar sits on the left in English`() {
        val center = renderChart(LayoutDirection.Ltr)

        assertTrue("center at $center", center < 0.25f)
    }

    @Test
    fun `the first day's bar sits on the right in Arabic, over its label`() {
        val center = renderChart(LayoutDirection.Rtl)

        assertTrue("center at $center", center > 0.75f)
    }

    @Test
    fun `a day's label sits centered under its bar`() {
        val bar = renderChart(LayoutDirection.Ltr)

        val label = composeRule.onNodeWithText("Sun").fetchSemanticsNode().boundsInRoot.center.x
        val width = composeRule.onRoot().fetchSemanticsNode().size.width

        assertEquals(bar, label / width, 0.01f)
    }

    @Test
    fun `California stays on the west side of the map in Arabic`() {
        val center =
            render(LayoutDirection.Rtl) {
                Box(Modifier.fillMaxWidth()) { RegionTileMap(mapOf("CA" to 10)) }
            }.centerOf { red, _, blue -> red - blue > 0.2f }

        assertTrue("center at $center", center < 0.25f)
    }

    private fun renderRow(direction: LayoutDirection): Float =
        render(direction) {
            Box(Modifier.fillMaxWidth()) {
                GroupedCallItem(
                    call = BlockedCall(number = "+14155550123", type = "robocall", matchReason = "database"),
                    count = 1,
                    onTap = {},
                    onBlock = {},
                )
            }
        }.tallestWarmColumn()

    @Test
    fun `a log row's accent strip is on the left in English`() {
        val column = renderRow(LayoutDirection.Ltr)

        assertTrue("strip at $column", column < 0.05f)
    }

    @Test
    fun `a log row's accent strip moves to the right in Arabic, where its padding went`() {
        val column = renderRow(LayoutDirection.Rtl)

        assertTrue("strip at $column", column > 0.95f)
    }
}
