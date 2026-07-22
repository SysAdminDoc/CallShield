package com.sysadmindoc.callshield.ui.screens.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.sysadmindoc.callshield.data.NotificationScreeningSources
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NotificationScreeningSourcesSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun defaultsShowPrivateSourceOffAndMessagesOn() {
        var toggled: Pair<String, Boolean>? = null
        composeRule.setContent {
            NotificationScreeningSourcesSheet(
                enabledPackages = NotificationScreeningSources.defaultEnabledPackages,
                onToggle = { packageName, enabled -> toggled = packageName to enabled },
                onReset = {},
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText("reads notification sender and text", substring = true).assertIsDisplayed()
        composeRule
            .onNodeWithTag("${NOTIFICATION_SCREENING_SOURCE_TAG_PREFIX}com.google.android.apps.messaging")
            .assertIsOn()
        composeRule
            .onNodeWithTag("${NOTIFICATION_SCREENING_SOURCE_TAG_PREFIX}com.whatsapp")
            .performScrollTo()
            .assertIsOff()
            .performClick()

        composeRule.runOnIdle {
            assertEquals("com.whatsapp" to true, toggled)
        }
    }
}
