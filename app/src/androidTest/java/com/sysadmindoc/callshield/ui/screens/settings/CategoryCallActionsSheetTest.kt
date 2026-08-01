package com.sysadmindoc.callshield.ui.screens.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.sysadmindoc.callshield.data.CallCategory
import com.sysadmindoc.callshield.data.CategoryCallAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CategoryCallActionsSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsPrecedenceAndChangesOneCategoryAction() {
        var changed: Pair<CallCategory, CategoryCallAction>? = null
        composeRule.setContent {
            CategoryCallActionsSheet(
                actions = mapOf(CallCategory.Robocall to CategoryCallAction.SILENCE),
                onActionChange = { category, action -> changed = category to action },
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText("Emergency contacts and manually trusted numbers", substring = true).assertIsDisplayed()
        composeRule
            .onNodeWithTag("${CATEGORY_CALL_ACTION_TAG_PREFIX}robocall:silence")
            .assertIsSelected()
        composeRule
            .onNodeWithTag("${CATEGORY_CALL_ACTION_TAG_PREFIX}scam:allow")
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(CallCategory.Scam to CategoryCallAction.ALLOW, changed)
        }
    }
}
