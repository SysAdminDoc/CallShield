package com.sysadmindoc.callshield.service

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The caller ID popup had no live region, 10 to 13sp text, small buttons and an
 * English web search, so a TalkBack user never heard the risk while the phone
 * rang. Its views are built inside the service, so the text sizes and live
 * regions are checked in the source and the buttons on a rendered overlay.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayAccessibilityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val source =
        File("src/main/java/com/sysadmindoc/callshield/service/CallerIdOverlayService.kt").readText()

    @Test
    fun `the verdict line names the reason that decided it`() {
        assertEquals("80% risk: Premium-rate number", overlayVerdictLine(context, 80, "Premium-rate number"))
        assertEquals("Score: 80%", overlayVerdictLine(context, 80, ""))
    }

    @Test
    fun `the web search is in the phone's language`() {
        assertEquals("https://www.google.com/search?q=2125550101%20phone%20number%20spam", overlaySearchUrl(context, "2125550101"))

        val chinese = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
        val url = overlaySearchUrl(chinese, "2125550101")
        assertTrue(url, url.startsWith("https://www.google.com/search?q=2125550101%20") && "phone" !in url)
    }

    @Test
    fun `text is at least 14sp and only the number sets its own size`() {
        assertTrue(OVERLAY_TEXT_SP >= 14f)
        assertTrue(OVERLAY_TOUCH_TARGET_DP >= 48f)
        val literalSizes = Regex("""textSize = (\d+(?:\.\d+)?)f""").findAll(source).map { it.groupValues[1].toFloat() }.toList()
        assertEquals("only the 24sp number may set a literal size", listOf(24f), literalSizes)
    }

    @Test
    fun `every button the overlay draws has a 48dp target`() {
        ShadowSettings.setCanDrawOverlays(true)
        val controller = Robolectric.buildService(CallerIdOverlayService::class.java).create()
        try {
            CallerIdOverlayService.startIncoming(context, "+12025550142", 40, "invalid_format")
            val intent = shadowOf(context as Application).nextStartedService ?: error("no service start was sent")
            controller.get().onStartCommand(intent, 0, 1)
            shadowOf(Looper.getMainLooper()).idle()
            val window = Shadow.extract<ShadowWindowManagerImpl>(controller.get().getSystemService(WindowManager::class.java))
            val buttons = window.views.flatMap { buttonsIn(it) }
            // Search, Block, Dismiss, the SIT tone, and Undo and Dismiss on the blocked strip.
            assertEquals(6, buttons.size)
            val target = (OVERLAY_TOUCH_TARGET_DP * context.resources.displayMetrics.density).roundToInt()
            for (button in buttons) {
                assertTrue("${button.text} is ${button.minHeight}px tall", button.minHeight >= target && button.minimumHeight >= target)
            }
        } finally {
            controller.destroy()
        }
    }

    private fun buttonsIn(view: View): List<Button> =
        when (view) {
            is Button -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { buttonsIn(view.getChildAt(it)) }
            else -> emptyList()
        }

    @Test
    fun `TalkBack hears the header and the verdict as they change`() {
        assertEquals(2, Regex("""accessibilityLiveRegion = android\.view\.View\.ACCESSIBILITY_LIVE_REGION_POLITE""").findAll(source).count())
    }
}
