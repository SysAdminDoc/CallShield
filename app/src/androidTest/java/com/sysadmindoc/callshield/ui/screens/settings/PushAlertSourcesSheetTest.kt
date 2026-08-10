package com.sysadmindoc.callshield.ui.screens.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.sysadmindoc.callshield.ui.runStrictAccessibilityChecks
import org.junit.Rule
import org.junit.Test

class PushAlertSourcesSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pushAlertSourceSheetPassesStrictAccessibilityChecks() {
        composeRule.setContent {
            PushAlertSourcesSheet(
                disabledPackages = emptySet(),
                onToggle = { _, _ -> },
                onReset = {},
                onDismiss = {},
            )
        }

        composeRule.runStrictAccessibilityChecks()
        composeRule.onNodeWithText("Trusted notification sources").assertIsDisplayed()
    }
}
