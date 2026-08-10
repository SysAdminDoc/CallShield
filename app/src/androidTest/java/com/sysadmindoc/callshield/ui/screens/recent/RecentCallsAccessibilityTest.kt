package com.sysadmindoc.callshield.ui.screens.recent

import android.provider.CallLog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.sysadmindoc.callshield.ui.runStrictAccessibilityChecks
import org.junit.Rule
import org.junit.Test

class RecentCallsAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun recentCallRowSpeaksDirectionRiskAndActions() {
        composeRule.setContent {
            RecentCallItem(
                call =
                    RecentCall(
                        number = "+12125550101",
                        type = CallLog.Calls.MISSED_TYPE,
                        date = 1_753_094_800_000L,
                        duration = 0,
                        isSpam = true,
                        spamReason = "database",
                    ),
                onOpenDetail = {},
                onTemporaryAllow = {},
                onTemporaryBlock = {},
            )
        }

        composeRule.runStrictAccessibilityChecks()
    }
}
