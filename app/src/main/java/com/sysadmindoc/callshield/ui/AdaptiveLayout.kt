package com.sysadmindoc.callshield.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.Posture
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.ui.theme.Black
import com.sysadmindoc.callshield.ui.theme.CatSubtext
import com.sysadmindoc.callshield.ui.theme.DividerColor

/** Window width from which the shell and a number's details sit side by side. */
internal val TWO_PANE_MIN_WIDTH = 840.dp

/**
 * A wide window shorter than this keeps one pane: a phone on its side is wide
 * enough for two, but each would be too short to use.
 */
internal val TWO_PANE_MIN_HEIGHT = 480.dp

/** Shell width from which its tabs move from the bottom bar to a rail at the side. */
internal val NAV_RAIL_MIN_WIDTH = 600.dp

/** The line between the panes when no hinge separates them. */
private val PANE_DIVIDER = 1.dp

internal const val SHELL_PANE_TAG = "pane_shell"
internal const val DETAIL_PANE_TAG = "pane_detail"
internal const val NAV_BAR_TAG = "shell_nav_bar"
internal const val NAV_RAIL_TAG = "shell_nav_rail"

/** How the root lays out the tab shell and a number's details. */
internal sealed interface RootLayout {
    /** The shell alone; a number's details replace it while one is open. */
    data object OnePane : RootLayout

    /**
     * The shell before [split] and the details after [split] plus [gap],
     * measured from the root's left edge (or its top when [stacked]). When
     * [atHinge], the gap is the hinge itself, so nothing is laid across it.
     */
    data class TwoPane(
        val split: Dp,
        val gap: Dp,
        val stacked: Boolean,
        val atHinge: Boolean,
    ) : RootLayout
}

/**
 * A separating hinge (a half-open fold, or one that hides pixels) always splits
 * the panes along it: side by side for a book fold, top over bottom for
 * tabletop. Without one, a window at least [TWO_PANE_MIN_WIDTH] wide and
 * [TWO_PANE_MIN_HEIGHT] tall is split in half. A flat fold counts as one
 * screen. Hinge bounds come in window pixels; [origin] is where the root sits
 * in the window.
 */
internal fun rootLayoutFor(
    width: Dp,
    height: Dp,
    posture: Posture,
    density: Density,
    origin: Offset = Offset.Zero,
): RootLayout {
    with(density) {
        val widthPx = width.toPx()
        val heightPx = height.toPx()
        for (hinge in posture.hingeList) {
            if (!hinge.isSeparating) continue
            val bounds = hinge.bounds.translate(-origin)
            if (hinge.isVertical && bounds.left > 0f && bounds.right < widthPx) {
                return RootLayout.TwoPane(bounds.left.toDp(), bounds.width.toDp(), stacked = false, atHinge = true)
            }
            if (!hinge.isVertical && bounds.top > 0f && bounds.bottom < heightPx) {
                return RootLayout.TwoPane(bounds.top.toDp(), bounds.height.toDp(), stacked = true, atHinge = true)
            }
        }
    }
    return if (width >= TWO_PANE_MIN_WIDTH && height >= TWO_PANE_MIN_HEIGHT) {
        RootLayout.TwoPane((width - PANE_DIVIDER) / 2, PANE_DIVIDER, stacked = false, atHinge = false)
    } else {
        RootLayout.OnePane
    }
}

/**
 * Lays [shell] and [detail] out as [layout] says. Each pane gives up the bar
 * and cutout insets on the side it shares with the other, so neither pads for
 * a system bar on the far side of the window.
 */
@Composable
internal fun RootPanes(
    layout: RootLayout.TwoPane,
    rootWidth: Dp,
    shell: @Composable () -> Unit,
    detail: @Composable () -> Unit,
) {
    // A hinge's gap shows nothing; a plain split gets a divider line.
    val gapColor = if (layout.atHinge) Black else DividerColor
    if (layout.stacked) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(layout.split)
                    .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .testTag(SHELL_PANE_TAG),
            ) { shell() }
            Box(Modifier.fillMaxWidth().height(layout.gap).background(gapColor))
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .testTag(DETAIL_PANE_TAG),
            ) { detail() }
        }
    } else {
        // The split is measured from the left edge. Right to left, the shell
        // is on the right, so it gets what lies past the gap.
        val shellWidth =
            if (LocalLayoutDirection.current == LayoutDirection.Ltr) layout.split else rootWidth - layout.split - layout.gap
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .width(shellWidth)
                    .fillMaxHeight()
                    .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.End))
                    .testTag(SHELL_PANE_TAG),
            ) { shell() }
            Box(Modifier.width(layout.gap).fillMaxHeight().background(gapColor))
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))
                    .testTag(DETAIL_PANE_TAG),
            ) { detail() }
        }
    }
}

/** The detail pane before a number is picked. */
@Composable
internal fun DetailPanePlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize().background(Black).safeDrawingPadding().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_callshield_brand_art),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
            )
            Text(
                stringResource(R.string.detail_pane_empty),
                color = CatSubtext,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}
