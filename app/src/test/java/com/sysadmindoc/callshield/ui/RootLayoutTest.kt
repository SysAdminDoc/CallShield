package com.sysadmindoc.callshield.ui

import androidx.compose.material3.adaptive.HingeInfo
import androidx.compose.material3.adaptive.Posture
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the root splits the shell from a number's details. Hinge bounds are in
 * window pixels at twice the dp, the way a foldable reports them.
 */
class RootLayoutTest {
    private val density = Density(2f)

    private fun layout(
        width: Dp,
        height: Dp,
        vararg hinges: HingeInfo,
        origin: Offset = Offset.Zero,
    ) = rootLayoutFor(width, height, Posture(hingeList = hinges.toList()), density, origin)

    /** A hinge from [from] to [to] dp, down the window when [vertical], else across it. */
    private fun hinge(
        from: Float,
        to: Float,
        vertical: Boolean,
        separating: Boolean = true,
        span: Float = 4000f,
    ) = HingeInfo(
        bounds = if (vertical) Rect(from * 2, 0f, to * 2, span) else Rect(0f, from * 2, span, to * 2),
        isFlat = !separating,
        isVertical = vertical,
        isSeparating = separating,
        isOccluding = false,
    )

    @Test
    fun `a phone keeps one pane either way up`() {
        assertEquals(RootLayout.OnePane, layout(411.dp, 891.dp))
        // Wide enough for two, but each would be too short.
        assertEquals(RootLayout.OnePane, layout(891.dp, 411.dp))
    }

    @Test
    fun `a portrait tablet keeps one pane`() {
        assertEquals(RootLayout.OnePane, layout(800.dp, 1280.dp))
    }

    @Test
    fun `a landscape tablet splits in half with a divider`() {
        assertEquals(RootLayout.TwoPane(639.5.dp, 1.dp, stacked = false, atHinge = false), layout(1280.dp, 800.dp))
        assertEquals(RootLayout.TwoPane(419.5.dp, 1.dp, stacked = false, atHinge = false), layout(840.dp, 480.dp))
    }

    @Test
    fun `a half-open book fold splits at the hinge even when narrow`() {
        assertEquals(
            RootLayout.TwoPane(400.dp, 20.dp, stacked = false, atHinge = true),
            layout(760.dp, 900.dp, hinge(400f, 420f, vertical = true)),
        )
        // A fold with no gap leaves no gap.
        assertEquals(
            RootLayout.TwoPane(380.dp, 0.dp, stacked = false, atHinge = true),
            layout(760.dp, 900.dp, hinge(380f, 380f, vertical = true)),
        )
    }

    @Test
    fun `the hinge wins over the half split in a wide window`() {
        assertEquals(
            RootLayout.TwoPane(500.dp, 20.dp, stacked = false, atHinge = true),
            layout(1280.dp, 800.dp, hinge(500f, 520f, vertical = true)),
        )
    }

    @Test
    fun `tabletop puts the shell above the hinge`() {
        assertEquals(
            RootLayout.TwoPane(400.dp, 20.dp, stacked = true, atHinge = true),
            layout(700.dp, 840.dp, hinge(400f, 420f, vertical = false)),
        )
    }

    @Test
    fun `a flat fold counts as one screen`() {
        assertEquals(RootLayout.OnePane, layout(700.dp, 800.dp, hinge(350f, 350f, vertical = true, separating = false)))
        assertEquals(
            RootLayout.TwoPane(639.5.dp, 1.dp, stacked = false, atHinge = false),
            layout(1280.dp, 800.dp, hinge(640f, 640f, vertical = true, separating = false)),
        )
    }

    @Test
    fun `a hinge at or past the root's edge is ignored`() {
        assertEquals(RootLayout.OnePane, layout(700.dp, 800.dp, hinge(0f, 10f, vertical = true)))
        assertEquals(RootLayout.OnePane, layout(700.dp, 800.dp, hinge(690f, 760f, vertical = true)))
        assertEquals(RootLayout.OnePane, layout(700.dp, 800.dp, hinge(800f, 820f, vertical = false)))
    }

    @Test
    fun `hinge bounds are taken from where the root sits in the window`() {
        // The root starts 50 dp down the window, so a hinge 450 dp down the
        // window is 400 dp down the root.
        assertEquals(
            RootLayout.TwoPane(400.dp, 20.dp, stacked = true, atHinge = true),
            layout(700.dp, 840.dp, hinge(450f, 470f, vertical = false), origin = Offset(0f, 100f)),
        )
    }
}
