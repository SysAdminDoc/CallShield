package com.sysadmindoc.callshield.ui.screens.more

import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.sysadmindoc.callshield.ui.runStrictAccessibilityChecks
import com.sysadmindoc.callshield.ui.setThemedContent
import org.junit.Rule
import org.junit.Test

class ProtectionTestAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun protectionTestScreenPassesStrictAccessibilityChecks() {
        composeRule.setThemedContent { ProtectionTestScreen() }
        composeRule.runStrictAccessibilityChecks()
    }
}
