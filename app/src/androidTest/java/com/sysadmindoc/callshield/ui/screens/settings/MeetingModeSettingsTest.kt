package com.sysadmindoc.callshield.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sysadmindoc.callshield.ui.runStrictAccessibilityChecks
import com.sysadmindoc.callshield.ui.setThemedContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MeetingModeSettingsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun themed(content: @Composable () -> Unit) = composeRule.setThemedContent(content)

    @Test
    fun toggleTurnsMeetingModeOn() {
        var enabled: Boolean? = null
        themed {
            MeetingModeSettings(
                enabled = false,
                selectedCount = 0,
                notificationAccessGranted = true,
                onEnabledChange = { enabled = it },
                onChooseApps = {},
                onGrantAccess = {},
            )
        }

        composeRule.onNodeWithTag(SETTINGS_MEETING_MODE_TOGGLE_TAG).performClick()

        composeRule.runOnIdle { assertEquals(true, enabled) }
    }

    @Test
    fun enabledWithNoAppsAndNoAccessSaysWhatIsMissing() {
        var choseApps = false
        var askedForAccess = false
        themed {
            MeetingModeSettings(
                enabled = true,
                selectedCount = 0,
                notificationAccessGranted = false,
                onEnabledChange = {},
                onChooseApps = { choseApps = true },
                onGrantAccess = { askedForAccess = true },
            )
        }

        composeRule.onNodeWithText("Pick at least one app. Meeting mode does nothing until you do.").assertIsDisplayed()
        composeRule.onNodeWithText("Meeting mode needs notification access to see when a meeting starts.").assertIsDisplayed()
        composeRule.onNodeWithText("Choose meeting apps").performClick()
        composeRule.onNodeWithText("Grant notification access").performClick()

        composeRule.runOnIdle {
            assertTrue(choseApps)
            assertTrue(askedForAccess)
        }
    }

    @Test
    fun meetingModeCardPassesStrictAccessibilityChecks() {
        themed {
            MeetingModeSettings(
                enabled = true,
                selectedCount = 2,
                notificationAccessGranted = true,
                onEnabledChange = {},
                onChooseApps = {},
                onGrantAccess = {},
            )
        }

        composeRule.onNodeWithText("2 apps picked").assertIsDisplayed()
        composeRule.runStrictAccessibilityChecks()
    }

    @Test
    fun meetingAppsSheetListsTheCatalogAndLocksAppsThatAreMissing() {
        themed {
            MeetingAppsSheet(selectedPackages = emptySet(), onToggle = { _, _ -> }, onDismiss = {})
        }

        composeRule.onNodeWithText("Meeting apps").assertIsDisplayed()
        // The emulator image ships none of the catalog apps, so the list is
        // alphabetical and every row is locked. Discord sorts first.
        val discordRow = "${MEETING_APP_TAG_PREFIX}com.discord"
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasTestTag(discordRow)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(discordRow).assertIsNotEnabled()
    }
}
