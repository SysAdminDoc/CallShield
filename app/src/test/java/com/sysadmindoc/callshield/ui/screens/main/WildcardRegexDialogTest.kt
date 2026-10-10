package com.sysadmindoc.callshield.ui.screens.main

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.sysadmindoc.callshield.data.model.WildcardRule
import com.sysadmindoc.callshield.ui.theme.CallShieldTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Screening skips regex shapes that can backtrack badly, so a rule saved with
 * one showed as active and never matched. The dialog now refuses it up front.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "en-rUS")
class WildcardRegexDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a regex screening would skip is refused, and the simple form of it is added`() {
        val nested = """^\+1(\d{3}){3}$"""
        val simple = """^\+1\d{9}$"""
        // The premise: screening never matches the nested form.
        assertFalse(WildcardRule(pattern = nested, isRegex = true).matches("+12125550101"))
        val added = mutableListOf<String>()
        composeRule.setContent {
            CallShieldTheme { AddWildcardDialog(onDismiss = {}) { pattern, _, _, _ -> added += pattern } }
        }

        composeRule.onNodeWithTag(BLOCKLIST_REGEX_CHECKBOX_TAG).performClick()
        composeRule.onNodeWithText("Regex").performTextInput(nested)
        composeRule.onNodeWithText("Add").performClick()

        composeRule.onNodeWithText("too complex", substring = true).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(emptyList<String>(), added) }

        composeRule.onNodeWithText("Regex").performTextClearance()
        composeRule.onNodeWithText("Regex").performTextInput(simple)
        composeRule.onNodeWithText("Add").performClick()

        composeRule.runOnIdle { assertEquals(listOf(simple), added) }
    }
}
