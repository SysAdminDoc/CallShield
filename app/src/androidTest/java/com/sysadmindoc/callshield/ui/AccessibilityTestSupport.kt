package com.sysadmindoc.callshield.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator
import com.sysadmindoc.callshield.ui.theme.AppThemeMode
import com.sysadmindoc.callshield.ui.theme.Black
import com.sysadmindoc.callshield.ui.theme.CallShieldTheme

/** Tag on the root [setThemedContent] draws. */
const val THEMED_TEST_ROOT_TAG = "callshield_themed_test_root"

/**
 * Sets [content] the way the app draws every screen: inside [CallShieldTheme]
 * on the theme background. Outside the theme the palette falls back to the
 * AMOLED tokens while the test activity paints a light window, so contrast
 * checks measure color pairs no user ever sees.
 */
fun ComposeContentTestRule.setThemedContent(content: @Composable () -> Unit) {
    setContent {
        // Light, not the default: its accent pairs sit closest to the 4.5:1 floor
        // (about 5.2:1 against 6 to 17:1 in AMOLED), so it's the theme where a
        // contrast regression shows. The default moved to AMOLED on 2026-09-26.
        CallShieldTheme(themeMode = AppThemeMode.Light) {
            Surface(
                modifier = Modifier.fillMaxSize().testTag(THEMED_TEST_ROOT_TAG),
                color = Black,
                content = content,
            )
        }
    }
}

/** Runs the same strict accessibility floor for every Compose surface test. */
fun ComposeTestRule.runStrictAccessibilityChecks() {
    onNodeWithTag(THEMED_TEST_ROOT_TAG, useUnmergedTree = true)
        .assertExists("Render the content with setThemedContent before running the strict accessibility checks.")
    enableAccessibilityChecks(
        AccessibilityValidator().setThrowExceptionFor(
            AccessibilityCheckResult.AccessibilityCheckResultType.WARNING,
        ),
    )
    onRoot().tryPerformAccessibilityChecks()
}
