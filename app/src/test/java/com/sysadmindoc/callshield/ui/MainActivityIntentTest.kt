package com.sysadmindoc.callshield.ui

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MainActivityIntentTest {
    @Test
    fun `telephone deep link is normalized before opening details`() {
        val request =
            Intent(Intent.ACTION_VIEW, Uri.parse("tel:%E2%80%8E+1%20(212)%20555-0101"))
                .toLaunchRequest(nextId = 4)

        assertEquals(4, request.id)
        assertEquals("+12125550101", request.deepLinkNumber)
    }

    @Test
    fun `untrusted open-number extra is normalized and bounded`() {
        val request =
            Intent()
                .putExtra("open_number", "+" + "1".repeat(10_000))
                .toLaunchRequest(nextId = 1)

        assertEquals("+" + "1".repeat(24), request.deepLinkNumber)
    }

    @Test
    fun `invalid and short deep links do not open number details`() {
        val unicodeOnly =
            Intent(Intent.ACTION_VIEW, Uri.parse("tel:%D9%A1%D9%A2%D9%A3%D9%A4%D9%A5"))
                .toLaunchRequest(nextId = 1)
        val tooShort =
            Intent().putExtra("open_number", "911").toLaunchRequest(nextId = 2)

        assertNull(unicodeOnly.deepLinkNumber)
        assertNull(tooShort.deepLinkNumber)
    }

    @Test
    fun `telephone URI is ignored for unrelated actions`() {
        val request = Intent(Intent.ACTION_SEND, Uri.parse("tel:+12125550101")).toLaunchRequest(nextId = 1)

        assertNull(request.deepLinkNumber)
    }

    @Test
    fun `plain launcher start carries no shortcut action`() {
        // ACTION_MAIN must not become a "shortcut request": a non-null
        // shortcutAction makes tabRequestId non-null, and every activity
        // recreation would then force the selected tab back to Home.
        val request = Intent(Intent.ACTION_MAIN).toLaunchRequest(nextId = 1)

        assertNull(request.shortcutAction)
    }

    @Test
    fun `known shortcut actions are preserved`() {
        val request = Intent("com.sysadmindoc.callshield.LOOKUP").toLaunchRequest(nextId = 1)

        assertEquals("com.sysadmindoc.callshield.LOOKUP", request.shortcutAction)
    }
}
