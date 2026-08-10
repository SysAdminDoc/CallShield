package com.sysadmindoc.callshield.ui.screens.more

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.sysadmindoc.callshield.ui.runStrictAccessibilityChecks
import com.sysadmindoc.callshield.ui.theme.CatBlue
import com.sysadmindoc.callshield.ui.theme.CatGreen
import org.junit.Rule
import org.junit.Test

class MoreAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun moreNavigationAndExternalLinksPassStrictAccessibilityChecks() {
        composeRule.setContent {
            MoreNavCard(
                icon = Icons.Default.Settings,
                title = "Settings",
                subtitle = "Permissions and protection controls",
                color = CatBlue,
                onClick = {},
            )
            QuickLink(
                icon = Icons.Default.Share,
                label = "Report a problem",
                subtitle = "Open the issue tracker",
                color = CatGreen,
                external = true,
                onClick = {},
            )
        }

        composeRule.runStrictAccessibilityChecks()
    }
}
