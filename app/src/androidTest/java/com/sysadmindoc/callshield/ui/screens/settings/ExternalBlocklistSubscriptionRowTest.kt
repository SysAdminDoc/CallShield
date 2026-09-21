package com.sysadmindoc.callshield.ui.screens.settings

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.sysadmindoc.callshield.data.model.ExternalBlocklistSubscription
import com.sysadmindoc.callshield.ui.runStrictAccessibilityChecks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * TalkBack reads a subscription row as one item that toggles the list, with
 * removing it as an action on that item. By touch only the switch toggles:
 * turning a list off deletes its numbers, and a tap on its name mustn't.
 */
class ExternalBlocklistSubscriptionRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val subscription =
        ExternalBlocklistSubscription(
            id = "daily",
            label = "Daily list",
            url = "https://lists.example.test/path/daily.txt",
            lastNumberCount = 3,
        )

    @Test
    fun eachRowIsOneNodeThatTogglesAndOffersRemove() {
        val toggled = mutableListOf<Boolean>()
        var removed = false
        composeRule.setContent {
            ExternalBlocklistSubscriptionRow(
                subscription = subscription,
                onToggle = { toggled += it },
                onRemove = { removed = true },
            )
        }

        composeRule.onAllNodes(hasClickAction()).assertCountEquals(1)
        val row = composeRule.onNode(hasText("Daily list") and hasText("lists.example.test") and hasClickAction())
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        row.assertIsOn()
        composeRule.onAllNodesWithText(subscription.url, substring = true).assertCountEquals(0)

        // TalkBack's double tap is the row's click action.
        row.performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(false), toggled)

        // A tap on the list's name does nothing; one on the switch toggles.
        composeRule.onNodeWithText("Daily list", useUnmergedTree = true).performClick()
        assertEquals(listOf(false), toggled)
        composeRule.onNodeWithTag(EXTERNAL_BLOCKLIST_SWITCH_TAG, useUnmergedTree = true).performClick()
        assertEquals(listOf(false, false), toggled)

        val remove =
            row
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single { it.label.contains("Daily list") }
        remove.action()
        assertTrue(removed)

        composeRule.runStrictAccessibilityChecks()
    }
}
