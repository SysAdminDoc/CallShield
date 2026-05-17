package com.sysadmindoc.callshield.ui.screens.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun quietHoursToggleInvokesPersistenceCallback() {
        var enabled: Boolean? = null
        composeRule.setContent {
            QuietHoursSettings(
                enabled = false,
                startHour = 22,
                endHour = 7,
                onEnabledChange = { enabled = it },
                onStartChange = {},
                onEndChange = {},
            )
        }

        composeRule.onNodeWithTag(SETTINGS_QUIET_HOURS_TOGGLE_TAG).performClick()

        composeRule.runOnIdle {
            assertEquals(true, enabled)
        }
    }

    @Test
    fun quietHoursShowsAllDayValidationWhenStartAndEndMatch() {
        composeRule.setContent {
            QuietHoursSettings(
                enabled = true,
                startHour = 22,
                endHour = 22,
                onEnabledChange = {},
                onStartChange = {},
                onEndChange = {},
            )
        }

        composeRule.onNodeWithText("Quiet Hours").assertIsDisplayed()
        composeRule.onNodeWithText("Start").assertIsDisplayed()
        composeRule.onNodeWithText("End").assertIsDisplayed()
        composeRule.onAllNodesWithText("10 PM").assertCountEquals(2)
        composeRule.onNodeWithText("Start and end match, so quiet-hours blocking runs all day.").assertIsDisplayed()
    }

    @Test
    fun hourPickerSelectsNewHour() {
        var selected = -1
        composeRule.setContent {
            HourPicker(selected = 22, onSelect = { selected = it })
        }

        composeRule.onNodeWithText("10 PM").performClick()
        composeRule.onNodeWithText("11 PM").performClick()

        composeRule.runOnIdle {
            assertEquals(23, selected)
        }
    }

    @Test
    fun hourLabelsCoverMidnightNoonAndAfternoon() {
        assertEquals("12 AM", formatHourLabel(0))
        assertEquals("12 PM", formatHourLabel(12))
        assertEquals("11 PM", formatHourLabel(23))
    }
}
