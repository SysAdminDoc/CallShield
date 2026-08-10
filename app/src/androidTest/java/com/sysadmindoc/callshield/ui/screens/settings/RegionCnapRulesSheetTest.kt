package com.sysadmindoc.callshield.ui.screens.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.sysadmindoc.callshield.ui.runStrictAccessibilityChecks
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RegionCnapRulesSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun editsRegionAndNameRulesAsOneSavedPolicy() {
        var saved: SavedPolicy? = null
        composeRule.setContent {
            RegionCnapRulesSheet(
                regionBlockEnabled = true,
                allowedRegions = setOf("NY"),
                cnapTrustPatterns = setOf("SCHOOL*"),
                cnapBlockPatterns = setOf("MEDICARE*"),
                onSave = { enabled, regions, trustPatterns, blockPatterns ->
                    saved = SavedPolicy(enabled, regions, trustPatterns, blockPatterns)
                },
                onDismiss = {},
            )
        }

        composeRule.runStrictAccessibilityChecks()

        composeRule.onNodeWithTag(REGION_RULES_ENABLED_TAG).assertIsOn()
        composeRule.onNodeWithTag(REGION_RULES_CODES_TAG).performTextReplacement("ny, NJ")
        composeRule
            .onNodeWithTag(CNAP_TRUST_PATTERNS_TAG)
            .performScrollTo()
            .performTextReplacement("SCHOOL*\nCITY HOSPITAL")
        composeRule
            .onNodeWithTag(CNAP_BLOCK_PATTERNS_TAG)
            .performScrollTo()
            .performTextReplacement("MEDICARE*\nAUTO WARRANTY?")
        composeRule
            .onNodeWithText("Exact number, system, prefix", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Save").performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(
                SavedPolicy(
                    enabled = true,
                    regions = linkedSetOf("NY", "NJ"),
                    trustPatterns = linkedSetOf("SCHOOL*", "CITY HOSPITAL"),
                    blockPatterns = linkedSetOf("MEDICARE*", "AUTO WARRANTY?"),
                ),
                saved,
            )
        }
    }

    private data class SavedPolicy(
        val enabled: Boolean,
        val regions: Set<String>,
        val trustPatterns: Set<String>,
        val blockPatterns: Set<String>,
    )
}
