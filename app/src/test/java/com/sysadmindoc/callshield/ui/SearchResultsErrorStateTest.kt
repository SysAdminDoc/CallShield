package com.sysadmindoc.callshield.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.ui.theme.CallShieldTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** A search whose query failed says so and offers Retry; it used to read as "No results found". */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "en-rUS")
class SearchResultsErrorStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun showEmptySearch(refresh: LoadState) {
        val done = LoadState.NotLoading(endOfPaginationReached = true)
        val pages = flowOf(PagingData.from(emptyList<SpamNumber>(), LoadStates(refresh = refresh, prepend = done, append = done)))
        composeRule.setContent {
            CallShieldTheme { SearchResultsView(pages.collectAsLazyPagingItems(), total = 0, onTap = {}) }
        }
    }

    @Test
    fun `a failed search shows the load error and Retry`() {
        showEmptySearch(LoadState.Error(IOException("database is locked")))

        composeRule.onNodeWithText("Couldn't load the database").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
        composeRule.onNodeWithText("No results found").assertDoesNotExist()
    }

    @Test
    fun `a search that ran and matched nothing still says no results`() {
        showEmptySearch(LoadState.NotLoading(endOfPaginationReached = true))

        composeRule.onNodeWithText("No results found").assertIsDisplayed()
        composeRule.onNodeWithText("Couldn't load the database").assertDoesNotExist()
    }
}
