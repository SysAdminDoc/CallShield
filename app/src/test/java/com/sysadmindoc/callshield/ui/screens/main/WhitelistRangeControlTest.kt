package com.sysadmindoc.callshield.ui.screens.main

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sysadmindoc.callshield.data.model.WhitelistEntry
import com.sysadmindoc.callshield.ui.theme.CallShieldTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An allow-list entry shows the number block it lets through, and offers one only where it can cover one. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "en-rUS")
class WhitelistRangeControlTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `an entry with a block shows the numbers it covers`() {
        composeRule.setContent {
            CallShieldTheme { WhitelistItem(WhitelistEntry(id = 1, number = "+15552345678", rangeDigits = 2), {}, {}) }
        }

        composeRule.onNodeWithText("Also lets through ⁦(555) 234-56XX⁩").assertIsDisplayed()
    }

    @Test
    fun `picking a block from the menu sets it, and a temporary allow isn't offered one`() {
        val picked = mutableListOf<Int>()
        composeRule.setContent {
            CallShieldTheme {
                Column {
                    WhitelistItem(WhitelistEntry(id = 1, number = "+15552345678"), {}, {}) { picked += it }
                    WhitelistItem(WhitelistEntry(id = 2, number = "+15553335678", expiresAt = Long.MAX_VALUE), {}, {})
                    WhitelistItem(WhitelistEntry(id = 3, number = "24680"), {}, {})
                }
            }
        }

        composeRule.onAllNodesWithTag(WHITELIST_RANGE_TAG).assertCountEquals(1)
        composeRule.onNodeWithText("Let its whole block of lines through too").performClick()
        composeRule.onNodeWithText("This number only").assertIsDisplayed()
        composeRule.onNodeWithText("⁦(555) 234-5XXX⁩ (1,000 numbers)").performClick()

        assertEquals(listOf(3), picked)
    }
}
