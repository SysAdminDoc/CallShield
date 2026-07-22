package com.sysadmindoc.callshield.ui.screens.main

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.sysadmindoc.callshield.data.model.BlockedCall
import org.junit.Rule
import org.junit.Test

class ExpandableCardSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun blockedCallCardAnnouncesExpandedAndCollapsedState() {
        composeRule.setContent {
            BlockedCallItem(
                call =
                    BlockedCall(
                        id = 1,
                        number = "+12125550101",
                        timestamp = 1_700_000_000_000L,
                        type = "call",
                        matchReason = "manual block",
                        confidence = 100,
                    ),
                onTap = {},
                onTemporaryAllow = {},
                onTemporaryBlock = {},
            )
        }

        composeRule
            .onNodeWithContentDescription("Expand details")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
            .performClick()
        composeRule
            .onNodeWithContentDescription("Collapse details")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
    }
}
