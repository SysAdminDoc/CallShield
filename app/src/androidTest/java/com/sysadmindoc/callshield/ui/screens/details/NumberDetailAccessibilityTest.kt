package com.sysadmindoc.callshield.ui.screens.details

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.sysadmindoc.callshield.ui.runStrictAccessibilityChecks
import com.sysadmindoc.callshield.ui.theme.CatRed
import org.junit.Rule
import org.junit.Test

class NumberDetailAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun numberDetailSummaryAndTimelinePassStrictAccessibilityChecks() {
        composeRule.setContent {
            Column {
                StatChip(label = "Blocked", value = "3", color = CatRed)
                TimelineRow(label = "First seen", value = "Today")
                TimelineRow(label = "Last seen", value = "A moment ago")
            }
        }

        composeRule.runStrictAccessibilityChecks()
    }
}
