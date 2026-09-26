package com.sysadmindoc.callshield.ui.screens.details

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReportIssueUrlTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    private fun report(loggedEntries: Int) = Uri.parse(reportIssueUrl(resources, "+18056377456", loggedEntries))

    @Test
    fun `a number with nothing in the log isn't reported as seen zero times`() {
        // Issues #24 and #27 both arrived as "Seen 0 times": the reporter had
        // looked the number up by hand, so nothing from it was in the log.
        val description = report(0).getQueryParameter("description").orEmpty()

        assertTrue(description, description.contains("No calls or texts"))
        assertFalse(description, description.contains("Seen 0"))
    }

    @Test
    fun `logged calls and texts are counted`() {
        assertEquals("Seen 1 time", report(1).getQueryParameter("description"))
        assertEquals("Seen 3 times", report(3).getQueryParameter("description"))
    }

    @Test
    fun `the report opens the spam form, which labels it`() {
        // A labels= parameter needs triage rights on the repository, so it was
        // dropped for every reporter and in-app reports arrived unlabelled.
        val url = report(2)

        assertEquals("spam_report.yml", url.getQueryParameter("template"))
        assertEquals("+18056377456", url.getQueryParameter("number"))
        assertEquals("[SPAM] +18056377456", url.getQueryParameter("title"))
        assertNull(url.getQueryParameter("labels"))
    }

    @Test
    fun `every prefilled field is a field of the form`() {
        val form =
            listOf(File("../.github/ISSUE_TEMPLATE/spam_report.yml"), File(".github/ISSUE_TEMPLATE/spam_report.yml"))
                .first { it.exists() }
                .readText()
        assertTrue(form, form.contains("labels: [\"spam-report\"]"))
        val fields = report(2).queryParameterNames - setOf("template", "title")
        for (field in fields) {
            assertTrue("$field isn't an id in the spam form", form.contains("id: $field\n") || form.contains("id: $field\r\n"))
        }
    }
}
