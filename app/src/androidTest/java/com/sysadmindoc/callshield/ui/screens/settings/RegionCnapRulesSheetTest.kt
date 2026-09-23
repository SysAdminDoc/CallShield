package com.sysadmindoc.callshield.ui.screens.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.sysadmindoc.callshield.ui.runStrictAccessibilityChecks
import com.sysadmindoc.callshield.ui.setThemedContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RegionCnapRulesSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun editsRegionAndNameRulesAsOneSavedPolicy() {
        var saved: SavedPolicy? = null
        composeRule.setThemedContent {
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

    @Test
    fun dragHandleKeepsItsTouchTargetWhileARowScrollsUnderIt() {
        composeRule.setThemedContent {
            RegionCnapRulesSheet(
                regionBlockEnabled = true,
                allowedRegions = setOf("NY"),
                // Long pattern lists make the sheet scroll on any screen size.
                cnapTrustPatterns = (1..8).map { "SCHOOL $it" }.toSet(),
                cnapBlockPatterns = (1..8).map { "MEDICARE $it" }.toSet(),
                onSave = { _, _, _, _ -> },
                onDismiss = {},
            )
        }
        val scroller = composeRule.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag(REGION_RULES_ENABLED_TAG)))
        val viewport = scroller.fetchSemanticsNode()
        val rowTop =
            composeRule
                .onNodeWithTag(REGION_RULES_ENABLED_TAG)
                .fetchSemanticsNode()
                .boundsInRoot.top
        // Leave the toggle row 16dp past the top of the scroll area, where the
        // keyboard parked it when this failed. Its touch bounds then reach up
        // into the drag handle.
        val overhang = with(composeRule.density) { 16.dp.toPx() }
        val distance = rowTop - viewport.boundsInRoot.top + overhang
        assertTrue(
            "The sheet has to scroll far enough to put a row under the handle.",
            viewport.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() >= distance,
        )
        scroller.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, distance) }

        composeRule.runStrictAccessibilityChecks()
    }

    private data class SavedPolicy(
        val enabled: Boolean,
        val regions: Set<String>,
        val trustPatterns: Set<String>,
        val blockPatterns: Set<String>,
    )
}
