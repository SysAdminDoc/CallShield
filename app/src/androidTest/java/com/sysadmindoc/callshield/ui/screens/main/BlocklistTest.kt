package com.sysadmindoc.callshield.ui.screens.main

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.sysadmindoc.callshield.data.TimeSchedule
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.WhitelistEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BlocklistTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun addNumberDialogNormalizesAndSubmitsManualBlock() {
        var submittedNumber = ""
        var submittedDescription = ""
        composeRule.setContent {
            AddNumberDialog(onDismiss = {}) { number, description ->
                submittedNumber = number
                submittedDescription = description
            }
        }

        composeRule.onNodeWithText("Phone Number").performTextInput("(212) 555-0101")
        composeRule.onNodeWithText("Description (optional)").performTextInput("Persistent scam")
        composeRule.onNodeWithText("Block").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals("2125550101", submittedNumber)
            assertEquals("Persistent scam", submittedDescription)
        }
    }

    @Test
    fun wildcardDialogShowsInvalidRegexValidation() {
        var addCount = 0
        composeRule.setContent {
            AddWildcardDialog(onDismiss = {}) { _: String, _: Boolean, _: String, _: TimeSchedule ->
                addCount++
            }
        }

        composeRule.onNodeWithTag(BLOCKLIST_REGEX_CHECKBOX_TAG).performClick()
        composeRule.onNodeWithText("Regex").performTextInput("[")
        composeRule.onNodeWithText("Add").performClick()

        composeRule.onNodeWithText("Invalid regex", substring = true).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(0, addCount)
        }
    }

    @Test
    fun blockDialogNamesHigherPriorityEmergencyAllowInline() {
        composeRule.setContent {
            AddNumberDialog(
                existingWhitelist =
                    listOf(
                        WhitelistEntry(
                            number = "+12125550101",
                            description = "Doctor",
                            isEmergency = true,
                        ),
                    ),
                onDismiss = {},
                onAdd = { _, _ -> },
            )
        }

        composeRule.onNodeWithText("Phone Number").performTextInput("+1 (212) 555-0101")

        composeRule
            .onNodeWithText("Emergency allow wins over Exact block", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun blocklistItemDeleteActionRemovesManualBlock() {
        var removed = 0
        composeRule.setContent {
            SwipeToRemoveBlocklistItem(
                number = manualSpamNumber(),
                onRemove = { removed++ },
            )
        }

        composeRule.onNodeWithContentDescription("Unblock number").performClick()

        composeRule.runOnIdle {
            assertEquals(1, removed)
        }
    }

    @Test
    fun blocklistItemSwipeLeftRemovesManualBlock() {
        var removed = 0
        composeRule.setContent {
            SwipeToRemoveBlocklistItem(
                number = manualSpamNumber(),
                onRemove = { removed++ },
            )
        }

        composeRule.onNodeWithTag(BLOCKLIST_SWIPE_ITEM_TAG).performTouchInput {
            swipeLeft()
        }

        composeRule.runOnIdle {
            assertEquals(1, removed)
        }
    }

    private fun manualSpamNumber(): SpamNumber =
        SpamNumber(
            id = 1,
            number = "+12125550101",
            type = "spam",
            description = "Manual block",
            source = "user",
            isUserBlocked = true,
        )
}
