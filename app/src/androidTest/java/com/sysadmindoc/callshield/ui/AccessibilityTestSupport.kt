package com.sysadmindoc.callshield.ui

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator

/** Runs the same strict accessibility floor for every Compose surface test. */
fun ComposeTestRule.runStrictAccessibilityChecks() {
    enableAccessibilityChecks(
        AccessibilityValidator().setThrowExceptionFor(
            AccessibilityCheckResult.AccessibilityCheckResultType.WARNING,
        ),
    )
    onRoot().tryPerformAccessibilityChecks()
}
