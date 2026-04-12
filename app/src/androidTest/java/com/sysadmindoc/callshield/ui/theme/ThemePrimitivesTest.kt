package com.sysadmindoc.callshield.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

/**
 * Covers the reusable premium primitives the rest of the UI depends on.
 *
 * These are simple but *load-bearing*: a regression in PremiumCard's click
 * dispatch or accentGlow's modifier composition would silently break every
 * screen that uses them. They can't be exercised from pure-JVM tests
 * because they resolve Compose composition/layout, so they live in
 * androidTest and run on an emulator via the CI workflow.
 */
class ThemePrimitivesTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun premiumCard_without_onClick_renders_content() {
        composeRule.setContent {
            PremiumCard { Text("hello card") }
        }
        composeRule.onNodeWithText("hello card").assertIsDisplayed()
    }

    @Test
    fun premiumCard_with_onClick_forwards_taps() {
        var tapped = 0
        composeRule.setContent {
            PremiumCard(onClick = { tapped++ }) { Text("tap me") }
        }
        composeRule.onNodeWithText("tap me").performClick()
        composeRule.runOnIdle {
            assert(tapped == 1) { "expected single click, got $tapped" }
        }
    }

    @Test
    fun sectionHeader_renders_label_text() {
        composeRule.setContent {
            SectionHeader(title = "DETECTION", color = Color.Green)
        }
        composeRule.onNodeWithText("DETECTION").assertIsDisplayed()
    }

    @Test
    fun accentGlow_composes_without_throwing() {
        // Pure smoke check — confirms accentGlow doesn't crash at layout time
        // on an empty box (the radius/alpha math has been a regression hotspot).
        composeRule.setContent {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .accentGlow(Color.Red, radius = 200f, alpha = 0.08f)
            ) { Text("glow") }
        }
        composeRule.onNodeWithText("glow").assertIsDisplayed()
    }
}
