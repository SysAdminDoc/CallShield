package com.sysadmindoc.callshield.ui.screens.more

import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.sysadmindoc.callshield.ui.runStrictAccessibilityChecks
import com.sysadmindoc.callshield.ui.setThemedContent
import org.junit.Rule
import org.junit.Test

class ChangelogAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun changelogScreenPassesStrictAccessibilityChecks() {
        composeRule.setThemedContent { ChangelogScreen() }
        composeRule.runStrictAccessibilityChecks()
    }
}
