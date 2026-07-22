package com.sysadmindoc.callshield.ui.screens.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RegionCnapRulesSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun editsRegionAndNameRulesAsOneSavedPolicy() {
        var saved: Triple<Boolean, Set<String>, Set<String>>? = null
        composeRule.setContent {
            RegionCnapRulesSheet(
                regionBlockEnabled = true,
                allowedRegions = setOf("NY"),
                cnapTrustPatterns = setOf("SCHOOL*"),
                onSave = { enabled, regions, patterns -> saved = Triple(enabled, regions, patterns) },
                onDismiss = {},
            )
        }

        composeRule.onNodeWithTag(REGION_RULES_ENABLED_TAG).assertIsOn()
        composeRule.onNodeWithTag(REGION_RULES_CODES_TAG).performTextReplacement("ny, NJ")
        composeRule
            .onNodeWithTag(CNAP_TRUST_PATTERNS_TAG)
            .performScrollTo()
            .performTextReplacement("SCHOOL*\nCITY HOSPITAL")
        composeRule
            .onNodeWithText("Exact number, system, prefix", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Save").performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(Triple(true, linkedSetOf("NY", "NJ"), linkedSetOf("SCHOOL*", "CITY HOSPITAL")), saved)
        }
    }
}
