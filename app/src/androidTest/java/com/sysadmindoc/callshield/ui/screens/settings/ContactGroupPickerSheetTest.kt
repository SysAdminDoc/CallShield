package com.sysadmindoc.callshield.ui.screens.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.sysadmindoc.callshield.data.ContactGroup
import com.sysadmindoc.callshield.ui.runStrictAccessibilityChecks
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ContactGroupPickerSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun togglesGroupsAndSupportsTheExplicitAllContactsScope() {
        val groupKey = "a".repeat(64)
        var selectedKeys by mutableStateOf(emptySet<String>())
        composeRule.setContent {
            ContactGroupPickerSheet(
                groups = listOf(ContactGroup(groupKey, "Family", "Local", 4)),
                selectedKeys = selectedKeys,
                loading = false,
                permissionGranted = true,
                onSelectionChange = { selectedKeys = it },
                onDismiss = {},
            )
        }

        composeRule.runStrictAccessibilityChecks()

        composeRule.onNodeWithTag(CONTACT_SCOPE_ALL_TAG).assertIsSelected()
        composeRule.onNodeWithTag("$CONTACT_SCOPE_GROUP_TAG_PREFIX$groupKey").performClick().assertIsOn()
        composeRule.runOnIdle { assertEquals(setOf(groupKey), selectedKeys) }

        composeRule.onNodeWithTag("$CONTACT_SCOPE_GROUP_TAG_PREFIX$groupKey").performClick().assertIsOff()
        composeRule.onNodeWithTag(CONTACT_SCOPE_ALL_TAG).assertIsSelected()
        composeRule.runOnIdle { assertEquals(emptySet<String>(), selectedKeys) }

        composeRule.onNodeWithTag("$CONTACT_SCOPE_GROUP_TAG_PREFIX$groupKey").performClick().assertIsOn()
        composeRule.onNodeWithTag(CONTACT_SCOPE_ALL_TAG).performClick().assertIsSelected()
        composeRule.runOnIdle { assertEquals(emptySet<String>(), selectedKeys) }
    }
}
