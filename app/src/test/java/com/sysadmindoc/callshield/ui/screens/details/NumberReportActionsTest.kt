package com.sysadmindoc.callshield.ui.screens.details

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sysadmindoc.callshield.ui.theme.CallShieldTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The number screen's Report sends the anonymous report. GitHub, which files
 * a public issue under the user's account, is the separate smaller action.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "en-rUS")
class NumberReportActionsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val taps = mutableListOf<String>()

    private fun show(note: String?) {
        composeRule.setContent {
            CallShieldTheme {
                Column {
                    NumberReportActions(
                        note = note,
                        onReport = { taps += "anonymous" },
                        onCall = { taps += "call" },
                        onReportOnGitHub = { taps += "github" },
                    )
                }
            }
        }
    }

    @Test
    fun `Report sends the anonymous report and GitHub is its own action`() {
        show(note = null)

        composeRule.onNodeWithText("Report").performClick()
        assertEquals(listOf("anonymous"), taps)

        composeRule.onNodeWithText("Report publicly on GitHub").performClick()
        assertEquals(listOf("anonymous", "github"), taps)

        composeRule.onNodeWithText("Call").performClick()
        assertEquals(listOf("anonymous", "github", "call"), taps)
    }

    @Test
    fun `what's already known about the number shows before either report`() {
        show(note = "This number is already in the CallShield database.")

        composeRule.onNodeWithText("This number is already in the CallShield database.").assertIsDisplayed()
        assertEquals(emptyList<String>(), taps)
    }
}
