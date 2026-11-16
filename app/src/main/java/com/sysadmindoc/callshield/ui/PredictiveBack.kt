package com.sysadmindoc.callshield.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.ui.theme.SurfaceVariant

private const val MAX_SHRINK = 0.1f
private val MAX_SHIFT = 8.dp
private val MAX_CORNER = 12.dp

/**
 * Back for a screen that sits over another one in the shell. While the
 * system's back gesture is held the screen shrinks and moves away from the
 * edge it started at, over a backdrop, so the gesture shows it'll close the
 * screen. Letting go runs [onBack] and a cancelled gesture puts the screen
 * back. A back button or key just runs [onBack].
 *
 * Put the returned modifier on the screen's root, before its background.
 */
@Composable
internal fun rememberPredictiveBack(
    enabled: Boolean = true,
    onBack: () -> Unit,
): Modifier {
    val progress = remember { mutableFloatStateOf(0f) }
    val fromRight = remember { mutableStateOf(false) }
    val currentOnBack by rememberUpdatedState(onBack)
    // Registered only while enabled, as BackHandler was in an if, so a screen
    // that starts listening later is asked before the ones already listening.
    if (enabled) {
        PredictiveBackHandler { events ->
            try {
                events.collect { event ->
                    fromRight.value = event.swipeEdge == BackEventCompat.EDGE_RIGHT
                    progress.floatValue = event.progress
                }
                currentOnBack()
            } finally {
                progress.floatValue = 0f
            }
        }
    }
    val backdrop = SurfaceVariant
    // The state is read only while drawing, so the gesture redraws the screen
    // without recomposing it.
    return Modifier
        .drawBehind { if (progress.floatValue > 0f) drawRect(backdrop) }
        .graphicsLayer {
            val p = progress.floatValue
            if (p > 0f) {
                val scale = 1f - MAX_SHRINK * p
                scaleX = scale
                scaleY = scale
                translationX = (if (fromRight.value) -1f else 1f) * MAX_SHIFT.toPx() * p
                shape = RoundedCornerShape(MAX_CORNER * p)
                clip = true
            }
        }
}
