package com.sysadmindoc.callshield.ui.screens.lookup

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.sysadmindoc.callshield.ui.runStrictAccessibilityChecks
import org.junit.Rule
import org.junit.Test

class LookupAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun lookupVerdictAndEvidenceSurfacePassStrictAccessibilityChecks() {
        composeRule.setContent {
            Column {
                SpamScoreGauge(score = 92, isSpam = true)
                DetailRow(label = "Detection", value = "Manual block", icon = Icons.Default.Block)
            }
        }

        composeRule.runStrictAccessibilityChecks()
    }
}
