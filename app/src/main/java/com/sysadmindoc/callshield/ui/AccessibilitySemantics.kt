package com.sysadmindoc.callshield.ui

import android.os.Build
import android.text.Spannable
import android.text.SpannableString
import android.text.style.TtsSpan
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Makes a gesture-driven telephony action reachable through switch access and
 * TalkBack, while keeping the gesture's visual container at the Android 48 dp
 * minimum touch target.
 */
internal fun Modifier.accessibleSwipeActions(actions: List<CustomAccessibilityAction>): Modifier =
    heightIn(min = MIN_INTERACTIVE_TARGET_DP.dp).semantics {
        customActions = actions
    }

/** Adds a spoken state and native expand/collapse actions to an expandable control. */
fun Modifier.expandableStateSemantics(
    expanded: Boolean,
    expandedStateDescription: String,
    collapsedStateDescription: String,
    onExpandedChange: (Boolean) -> Unit,
): Modifier =
    semantics {
        stateDescription = if (expanded) expandedStateDescription else collapsedStateDescription
        if (expanded) {
            collapse {
                onExpandedChange(false)
                true
            }
        } else {
            expand {
                onExpandedChange(true)
                true
            }
        }
    }

/**
 * Renders duration text through TextView so Android 16's native duration TTS span reaches
 * accessibility services. Older releases retain the same visible plain text.
 */
@Composable
@Suppress("FunctionNaming", "LongParameterList", "ktlint:standard:function-naming")
fun DurationTtsText(
    text: String,
    durationSeconds: Int,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    durationText: String = text,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextView(context).apply {
                includeFontPadding = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
        },
        update = { view ->
            view.text = buildDurationTtsText(text, durationText, durationSeconds)
            view.setTextColor(color.toArgb())
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSize.value)
        },
    )
}

internal fun buildDurationTtsText(
    text: String,
    durationText: String,
    durationSeconds: Int,
): CharSequence =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
        val start = text.indexOf(durationText)
        if (text.isNotEmpty() && durationText.isNotEmpty() && start >= 0) {
            buildDurationTtsTextApi36(
                text = text,
                start = start,
                end = start + durationText.length,
                durationSeconds = durationSeconds.coerceAtLeast(0),
            )
        } else {
            text
        }
    } else {
        text
    }

@RequiresApi(Build.VERSION_CODES.BAKLAVA)
private fun buildDurationTtsTextApi36(
    text: String,
    start: Int,
    end: Int,
    durationSeconds: Int,
): CharSequence {
    val hours = durationSeconds / SECONDS_PER_HOUR
    val minutes = durationSeconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val seconds = durationSeconds % SECONDS_PER_MINUTE
    val span =
        TtsSpan
            .DurationBuilder()
            .setHours(hours)
            .setMinutes(minutes)
            .setSeconds(seconds)
            .build()
    return SpannableString(text).apply {
        setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3_600
private const val MIN_INTERACTIVE_TARGET_DP = 48
