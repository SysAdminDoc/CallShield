package com.sysadmindoc.callshield.ui.screens.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import com.sysadmindoc.callshield.data.BackupRestore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsScreenPassesAutomatedAccessibilityChecks() {
        composeRule.setContent {
            QuietHoursSettings(
                enabled = true,
                startHour = 22,
                endHour = 7,
                onEnabledChange = {},
                onStartChange = {},
                onEndChange = {},
            )
        }

        composeRule.enableAccessibilityChecks()
        composeRule.onRoot().tryPerformAccessibilityChecks()
    }

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

        composeRule.onNodeWithText("Block unknowns during quiet hours").assertIsDisplayed()
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
        composeRule.onNodeWithText("12 AM").performClick()

        composeRule.runOnIdle {
            assertEquals(0, selected)
        }
    }

    @Test
    fun hourLabelsCoverMidnightNoonAndAfternoon() {
        assertEquals("12 AM", formatHourLabel(0))
        assertEquals("12 PM", formatHourLabel(12))
        assertEquals("11 PM", formatHourLabel(23))
    }

    @Test
    fun answeredCallerTrustShowsConfiguredLimitsAndPersistsToggle() {
        var enabled: Boolean? = null
        composeRule.setContent {
            AnsweredCallerTrustSettings(
                enabled = true,
                threshold = 2,
                windowDays = 30,
                onEnabledChange = { enabled = it },
                onThresholdChange = {},
                onWindowDaysChange = {},
            )
        }

        composeRule.onNodeWithText("Answered-caller trust").assertIsDisplayed()
        composeRule.onNodeWithText("2 answered calls").assertIsDisplayed()
        composeRule.onNodeWithText("30 days").assertIsDisplayed()
        composeRule.onNodeWithTag(SETTINGS_ANSWERED_CALLER_TOGGLE_TAG).performClick()

        composeRule.runOnIdle {
            assertEquals(false, enabled)
        }
    }

    @Test
    fun backupProtectionKeepsPassphrasesEphemeralAndValidatesConfirmation() {
        val form = mutableStateOf(BackupProtectionForm())
        composeRule.setContent {
            BackupProtectionControls(
                form = form.value,
                onFormChange = { form.value = it },
            )
        }

        composeRule.onNodeWithTag(SETTINGS_BACKUP_PASSPHRASE_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SETTINGS_BACKUP_ENCRYPTION_TOGGLE_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_BACKUP_PASSPHRASE_TAG).performTextInput("strong backup phrase")
        composeRule.onNodeWithTag(SETTINGS_BACKUP_CONFIRM_TAG).performTextInput("different phrase")

        composeRule.onNodeWithText("Passphrases do not match").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals("strong backup phrase", form.value.passphrase)
            assertEquals("different phrase", form.value.confirmation)
        }
    }

    @Test
    fun restorePreviewPanelShowsCountsAndActions() {
        var selectedMode: BackupRestore.RestoreMode? = null
        var canceled = false
        val preview =
            BackupRestore.RestorePreview(
                counts =
                    BackupRestore.RestoreCounts(
                        blockedNumbers = 2,
                        whitelistNumbers = 1,
                        wildcardRules = 1,
                        keywordRules = 1,
                    ),
                conflicts = BackupRestore.RestoreCounts(blockedNumbers = 1),
                backupTimestamp = 123L,
                payload =
                    BackupRestore.RestorePayload(
                        blockedNumbers = emptyList(),
                        whitelistNumbers = emptyList(),
                        wildcardRules = emptyList(),
                        keywordRules = emptyList(),
                    ),
            )

        composeRule.setContent {
            RestorePreviewPanel(
                preview = preview,
                onMerge = { selectedMode = BackupRestore.RestoreMode.MERGE },
                onReplace = { selectedMode = BackupRestore.RestoreMode.REPLACE },
                onCancel = { canceled = true },
            )
        }

        composeRule.onNodeWithTag(SETTINGS_RESTORE_PREVIEW_TAG).assertIsDisplayed()
        composeRule
            .onNodeWithText("Blocked: 2; Whitelist: 1; Wildcards: 1; Keywords: 1; Ranges: 0; Settings: 0; Logs: 0")
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("1 existing item has a matching key and may be updated during merge.")
            .assertIsDisplayed()

        composeRule.onNodeWithText("Merge").performClick()
        composeRule.runOnIdle {
            assertEquals(BackupRestore.RestoreMode.MERGE, selectedMode)
        }
        composeRule.onNodeWithText("Replace").performClick()
        composeRule.runOnIdle {
            assertEquals(BackupRestore.RestoreMode.REPLACE, selectedMode)
        }
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle {
            assertTrue(canceled)
        }
    }
}
