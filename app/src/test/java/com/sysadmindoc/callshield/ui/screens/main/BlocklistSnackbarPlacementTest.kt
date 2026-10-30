package com.sysadmindoc.callshield.ui.screens.main

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.repository.SpamRepositoryAdapter
import com.sysadmindoc.callshield.domain.usecase.ExportLogsUseCase
import com.sysadmindoc.callshield.domain.usecase.ManageBlocklistUseCase
import com.sysadmindoc.callshield.domain.usecase.SyncDatabaseUseCase
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.CallShieldTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Undo bar and the full-width Add button both sat at the bottom of the
 * Rules screen, so the bar covered the button until it went away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "en-rUS")
class BlocklistSnackbarPlacementTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fixture: IsolatedRepositoryFixture
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        fixture = IsolatedRepositoryFixture(context)
        runBlocking { fixture.repository.blockNumber("+12125550101") }
        val adapter = SpamRepositoryAdapter(fixture.repository)
        viewModel =
            MainViewModel(
                appContext = context,
                repo = fixture.repository,
                syncDatabase = SyncDatabaseUseCase(adapter),
                manageBlocklist = ManageBlocklistUseCase(adapter),
                exportLogs = ExportLogsUseCase(context),
            )
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `the Undo bar sits above the Add button, and at the bottom on the Database tab`() {
        composeRule.setContent { CallShieldTheme { BlocklistScreen(viewModel) } }
        val unblock = context.getString(R.string.cd_unblock)
        composeRule.waitUntil(TIMEOUT_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.onAllNodesWithContentDescription(unblock).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription(unblock).performClick()
        awaitText(context.getString(R.string.blocklist_number_removed))

        val bar = snackbarBounds()
        val addButton = composeRule.onNodeWithText(context.getString(R.string.blocklist_action_add_number)).fetchSemanticsNode().boundsInRoot
        assertTrue("Undo bar $bar overlaps the Add button $addButton", bar.bottom <= addButton.top)

        composeRule
            .onNodeWithText(context.getString(R.string.blocklist_tab_database_short))
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        val rootBottom =
            composeRule
                .onRoot()
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        val gap = with(composeRule.density) { 12.dp.toPx() }
        assertEquals("Undo bar on the Database tab", rootBottom - gap, snackbarBounds().bottom, 1f)
    }

    private fun snackbarBounds(): Rect = composeRule.onNodeWithTag(BLOCKLIST_SNACKBAR_HOST_TAG).fetchSemanticsNode().boundsInRoot

    private fun awaitText(text: String) {
        composeRule.waitUntil(TIMEOUT_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
