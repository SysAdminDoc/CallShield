package com.sysadmindoc.callshield.ui

import android.text.Spanned
import android.text.style.TtsSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AccessibilitySemanticsTest {
    @Test
    fun durationTextUsesAndroid16DurationTtsSpan() {
        val result =
            buildDurationTtsText(
                text = "Quiet period: 9 hours",
                durationText = "9 hours",
                durationSeconds = 9 * 3_600,
            ) as Spanned

        val spans = result.getSpans(0, result.length, TtsSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(TtsSpan.TYPE_DURATION, spans.single().type)
        assertEquals(9, spans.single().args.getInt(TtsSpan.ARG_HOURS))
        assertEquals(0, spans.single().args.getInt(TtsSpan.ARG_MINUTES))
        assertEquals(14, result.getSpanStart(spans.single()))
        assertEquals(21, result.getSpanEnd(spans.single()))
    }

    @Test
    @Config(sdk = [35])
    fun durationTextStaysPlainBelowAndroid16() {
        val text = "15 minutes"
        val result = buildDurationTtsText(text, text, durationSeconds = 900)

        assertSame(text, result)
        assertFalse(result is Spanned)
    }
}
