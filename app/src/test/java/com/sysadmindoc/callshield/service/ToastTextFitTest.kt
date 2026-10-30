package com.sysadmindoc.callshield.service

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.util.DisplayMetrics
import android.util.TypedValue
import androidx.annotation.StringRes
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * Android 12 and later draw a toast in SystemUI's text_toast layout, which
 * shows two lines of 14sp text and cuts the rest. Measured with native text
 * layout at Android 14's largest font size, in both shipped locales.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToastTextFitTest {
    private val base: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the Not spam and crash log toasts fit two lines at the largest font`() {
        for (locale in LOCALES) {
            val context = largestFont(locale)
            for (message in listOf(R.string.notif_not_spam_allowed, R.string.notif_not_spam_refused, R.string.more_no_crash_logs)) {
                val text = context.getString(message)
                assertTrue("$locale: $text", toastLines(context, message) <= 2)
            }
        }
    }

    /** The reason moved into a notification because it can't fit, which also shows the measure rejects long text. */
    @Test
    fun `the refusal's full reason would be cut off in a toast`() {
        for (locale in LOCALES) {
            assertTrue(locale.toString(), toastLines(largestFont(locale), R.string.notif_not_spam_refused_text) > 2)
        }
    }

    /** Without the font scale the text would measure at 14dp, and short lines would pass anything. */
    @Test
    fun `the measure sizes toast text the way Android 14 does at the largest font`() {
        val metrics = largestFont(Locale.US).resources.displayMetrics
        assertEquals(LARGEST_TOAST_TEXT_DP, toastTextSizePx(metrics) / metrics.density, 0.01f)
    }

    private fun largestFont(locale: Locale): Context =
        base.createConfigurationContext(
            Configuration(base.resources.configuration).apply {
                fontScale = LARGEST_FONT_SCALE
                setLocale(locale)
            },
        )

    private fun toastLines(
        context: Context,
        @StringRes message: Int,
    ): Int {
        val text = context.getString(message)
        val metrics = context.resources.displayMetrics
        val paint =
            TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                textSize = toastTextSizePx(metrics)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
        val width = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, TOAST_TEXT_WIDTH_DP, metrics).toInt()
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            .build()
            .lineCount
    }

    private fun toastTextSizePx(metrics: DisplayMetrics): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TOAST_TEXT_SP, metrics)

    private companion object {
        val LOCALES = listOf(Locale.US, Locale.SIMPLIFIED_CHINESE)

        // Android 14's top Font size step; earlier versions stop at 1.3.
        const val LARGEST_FONT_SCALE = 2.0f
        const val TOAST_TEXT_SP = 14f

        // Android 14 scales large text less than small text: at 2.0 its
        // FontScaleConverter table turns 14sp into 26dp, not 28.
        const val LARGEST_TOAST_TEXT_DP = 26f

        // The toast's 300dp maxWidth less 16dp padding on each side and the
        // 24dp app icon with its 10dp gap. A 360dp screen leaves 262dp
        // without the max, so this is the narrower of the two.
        const val TOAST_TEXT_WIDTH_DP = 234f
    }
}
