package com.sysadmindoc.callshield.util

import android.app.Application
import android.content.ClipboardManager
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.ReportFraudHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * Android 13 shows its own card whenever an app copies text, and the copy
 * toasts sat on top of it. They now show only on older versions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-rUS")
class CopyConfirmationTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    @Config(sdk = [32])
    fun `before Android 13 a copy says so with a toast`() {
        context.copyToClipboard("Phone", NUMBER, "Copied")

        assertEquals(NUMBER, clipText())
        assertEquals("Copied", ShadowToast.getTextOfLatestToast())
    }

    @Test
    @Config(sdk = [33])
    fun `from Android 13 the system card confirms a copy and no toast shows`() {
        context.copyToClipboard("Phone", NUMBER, "Copied")

        assertEquals(NUMBER, clipText())
        assertEquals(0, ShadowToast.shownToastCount())
    }

    @Test
    @Config(sdk = [32])
    fun `before Android 13 the FTC report toasts the paste instruction`() {
        assertTrue(ReportFraudHelper.report(context, NUMBER))

        assertEquals(NUMBER, clipText())
        assertEquals(context.getString(R.string.ftc_report_toast), ShadowToast.getTextOfLatestToast())
    }

    @Test
    @Config(sdk = [33])
    fun `from Android 13 the FTC report opens the form without a toast`() {
        assertTrue(ReportFraudHelper.report(context, NUMBER))

        assertEquals(NUMBER, clipText())
        assertEquals(ReportFraudHelper.FTC_REPORT_URL, shadowOf(context).nextStartedActivity.dataString)
        assertEquals(0, ShadowToast.shownToastCount())
    }

    private fun clipText(): String =
        context
            .getSystemService(ClipboardManager::class.java)
            .primaryClip!!
            .getItemAt(0)
            .text
            .toString()

    private companion object {
        const val NUMBER = "+12125550101"
    }
}
