package com.sysadmindoc.callshield.ui.screens.main

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.domain.model.BlockReasonCode
import com.sysadmindoc.callshield.ui.TemporaryDecisionDuration
import com.sysadmindoc.callshield.ui.runStrictAccessibilityChecks
import org.junit.Rule
import org.junit.Test

class BlockedLogAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun blockedLogRowSpeaksReasonAndExposesNonSwipeActions() {
        composeRule.setContent {
            BlockedCallItem(
                call =
                    BlockedCall(
                        id = 7,
                        number = "+12125550101",
                        matchReason = "database",
                        reasonCode = BlockReasonCode.DATABASE,
                        confidence = 92,
                    ),
                onTap = {},
                onTemporaryAllow = { _: TemporaryDecisionDuration -> },
                onTemporaryBlock = { _: TemporaryDecisionDuration -> },
            )
        }

        composeRule
            .onNodeWithContentDescription(
                "This item was blocked because it is in the spam database.",
            )
            .assertIsDisplayed()
        composeRule.runStrictAccessibilityChecks()
    }
}
