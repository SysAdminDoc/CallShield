package com.sysadmindoc.callshield.service

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/**
 * The caller ID popup had no live region, 10 to 13sp text, small buttons and an
 * English web search, so a TalkBack user never heard the risk while the phone
 * rang. Its views are built inside the service, so the sizes and live regions
 * are checked in the source.
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
    fun `text is at least 14sp and every button has a 48dp target`() {
        assertTrue(OVERLAY_TEXT_SP >= 14f)
        assertTrue(OVERLAY_TOUCH_TARGET_DP >= 48f)
        val literalSizes = Regex("""textSize = (\d+(?:\.\d+)?)f""").findAll(source).map { it.groupValues[1].toFloat() }.toList()
        assertEquals("only the 24sp number may set a literal size", listOf(24f), literalSizes)
        val buttons = Regex("""Button\(context\)""").findAll(source).count()
        assertTrue("found only $buttons buttons", buttons >= 4)
        assertEquals(buttons, Regex("""minHeight = context\.touchTargetPx\(\)""").findAll(source).count())
    }

    @Test
    fun `TalkBack hears the header and the verdict as they change`() {
        assertEquals(2, Regex("""accessibilityLiveRegion = android\.view\.View\.ACCESSIBILITY_LIVE_REGION_POLITE""").findAll(source).count())
    }
}
