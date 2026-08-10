package com.sysadmindoc.callshield.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.sysadmindoc.callshield.ui.runStrictAccessibilityChecks
import com.sysadmindoc.callshield.ui.theme.CatGreen
import org.junit.Rule
import org.junit.Test

class SettingsAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsCardAndTogglePassStrictAccessibilityChecks() {
        composeRule.setContent {
            Column {
                SettingsCard(title = "Protection") {
                    SettingsToggle(
                        title = "Block spam calls",
                        subtitle = "Reject calls from known spam numbers",
                        icon = Icons.Default.Shield,
                        checked = true,
                        tintColor = CatGreen,
                        onCheckedChange = {},
                    )
                }
            }
        }

        composeRule.runStrictAccessibilityChecks()
    }
}
