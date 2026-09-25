package com.sysadmindoc.callshield.ui.screens.details

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReportIssueBodyTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `a number with nothing in the log isn't reported as seen zero times`() {
        // Issues #24 and #27 both arrived as "Seen 0 times": the reporter had
        // looked the number up by hand, so nothing from it was in the log.
        val body = reportIssueBody(resources, "+18056377456", 0)

        assertTrue(body, body.contains("+18056377456"))
        assertTrue(body, body.contains("No calls or texts"))
        assertFalse(body, body.contains("Seen 0"))
    }

    @Test
    fun `logged calls and texts are counted`() {
        assertTrue(reportIssueBody(resources, "+18056377456", 1).endsWith("Seen 1 time"))
        assertTrue(reportIssueBody(resources, "+18056377456", 3).endsWith("Seen 3 times"))
    }

    @Test
    fun `the body keeps the tracker's headings whatever the count`() {
        for (count in listOf(0, 2)) {
            val body = reportIssueBody(resources, "+18056377456", count)
            assertTrue(body, body.startsWith("## Phone Number\n+18056377456\n\n## Type\n"))
            assertTrue(body, body.contains("\n\n## Description\n"))
        }
    }
}
