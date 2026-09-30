package com.sysadmindoc.callshield.ui.screens.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sysadmindoc.callshield.data.ExternalBlocklistParser
import com.sysadmindoc.callshield.data.model.ExternalBlocklistSubscription
import com.sysadmindoc.callshield.data.model.ListCatalogEntry
import com.sysadmindoc.callshield.data.model.ListNumberPlan
import com.sysadmindoc.callshield.ui.theme.CallShieldTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A recommended list shows where it's from and its license before it can be added. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "en-rUS")
class ListCatalogSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val entry =
        ListCatalogEntry(
            id = "opencallshield-co",
            name = "OpenCallShield",
            url = "https://raw.githubusercontent.com/jhonsu01/OpenCallShield/main/spam_numbers.json",
            homepage = "https://github.com/jhonsu01/OpenCallShield",
            license = "MIT",
            licenseUrl = "https://github.com/jhonsu01/OpenCallShield/blob/main/LICENSE",
            format = "json",
            numberPlan = ListNumberPlan("CO", "57", "0", listOf(10)),
        )

    @Test
    fun `a list shows its country and license beside Add, and Add adds that list`() {
        val added = mutableListOf<ListCatalogEntry>()
        composeRule.setContent {
            CallShieldTheme { Column { ListCatalogSection(listOf(entry), emptyList()) { added += it } } }
        }

        composeRule.onNodeWithText("Colombia").assertIsDisplayed()
        composeRule.onNodeWithText("MIT license").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Add OpenCallShield").performClick()

        assertEquals(listOf(entry), added)
    }

    @Test
    fun `a list already subscribed shows Added instead of Add`() {
        val subscribed =
            ExternalBlocklistSubscription(
                id = ExternalBlocklistParser.idForUrl(entry.url),
                label = "My Colombian list",
                url = entry.url,
            )
        composeRule.setContent {
            CallShieldTheme { Column { ListCatalogSection(listOf(entry), listOf(subscribed)) {} } }
        }

        composeRule.onNodeWithText("Added").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Add OpenCallShield").assertDoesNotExist()
    }
}
