package com.sysadmindoc.callshield.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.sysadmindoc.callshield.ui.screens.activity.ActivityTab
import com.sysadmindoc.callshield.ui.theme.CatGreen
import org.junit.Rule
import org.junit.Test

class MainActivityAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun appShellNavigationAndActivityTabsPassStrictAccessibilityChecks() {
        composeRule.setContent {
            Surface {
                Column {
                    Row {
                        NavItem(selected = true, onClick = {}, icon = Icons.Default.Shield, label = "Home", color = CatGreen)
                        NavItem(selected = false, onClick = {}, icon = Icons.Default.History, label = "Activity", color = CatGreen)
                        NavItem(selected = false, onClick = {}, icon = Icons.Default.Search, label = "Lookup", color = CatGreen)
                    }
                    Row {
                        ActivityTab(selected = true, label = "Recent calls", onClick = {})
                        ActivityTab(selected = false, label = "Blocked", onClick = {})
                    }
                    SearchResultsView(results = emptyList(), onTap = {})
                }
            }
        }

        composeRule.runStrictAccessibilityChecks()
        composeRule.onNodeWithText("Home").assertIsDisplayed()
        composeRule.onNodeWithText("Recent calls").assertIsDisplayed()
    }
}
