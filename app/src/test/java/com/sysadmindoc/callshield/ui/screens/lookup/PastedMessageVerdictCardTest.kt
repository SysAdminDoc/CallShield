package com.sysadmindoc.callshield.ui.screens.lookup

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.PastedMessageVerdict
import com.sysadmindoc.callshield.data.remote.UrlSafetyChecker
import com.sysadmindoc.callshield.data.remote.UrlThreatSource
import com.sysadmindoc.callshield.ui.theme.CallShieldTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "en-rUS")
class PastedMessageVerdictCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun show(
        body: String,
        dangerousLinks: List<UrlSafetyChecker.UrlCheckResult> = emptyList(),
    ) {
        val verdict = PastedMessageVerdict.of(body, aggressive = false, dangerousLinks = dangerousLinks)
        composeRule.setContent { CallShieldTheme { PastedMessageVerdictCard(verdict) } }
    }

    @Test
    fun `a dangerous link says what to do if it was already opened`() {
        val link =
            UrlSafetyChecker.UrlCheckResult(
                url = "https://parcel-desk.example.invalid/a1",
                isMalicious = true,
                threat = "phishing",
                source = UrlThreatSource.OPENPHISH,
            )
        show("Your parcel is held, pay the fee at https://parcel-desk.example.invalid/a1", listOf(link))

        // A heading, so TalkBack's headings navigation lands on it.
        composeRule.onNode(hasText(app.getString(R.string.link_opened_title)) and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.link_opened_steps)).assertIsDisplayed()
    }

    @Test
    fun `a text with no dangerous link has no already opened steps`() {
        show("Your parcel is held. Dial *21*+12125550143# to release it")

        assertEquals(0, composeRule.onAllNodesWithText(app.getString(R.string.link_opened_title)).fetchSemanticsNodes().size)
    }

    @Test
    fun `a pasted forwarding code says what dialing it does and how to undo it`() {
        show("Your parcel is held. Dial *21*+12125550143# to release it")

        composeRule.onNodeWithText(app.getString(R.string.signal_call_forwarding_code)).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.call_forwarding_code_detail)).assertIsDisplayed()
    }

    @Test
    fun `a text without one doesn't explain forwarding`() {
        show("Worried your calls are forwarded? Dial ##002# to turn all call forwarding off.")

        assertEquals(0, composeRule.onAllNodesWithText(app.getString(R.string.call_forwarding_code_detail)).fetchSemanticsNodes().size)
    }
}
