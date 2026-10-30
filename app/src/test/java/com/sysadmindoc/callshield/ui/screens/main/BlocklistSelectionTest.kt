package com.sysadmindoc.callshield.ui.screens.main

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.data.repository.SpamRepositoryAdapter
import com.sysadmindoc.callshield.domain.model.BlocklistSort
import com.sysadmindoc.callshield.domain.usecase.ExportLogsUseCase
import com.sysadmindoc.callshield.domain.usecase.ManageBlocklistUseCase
import com.sysadmindoc.callshield.domain.usecase.SyncDatabaseUseCase
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.CallShieldTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Rules > Blocked sorts, and a long press picks several rows to remove with one Undo. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "en-rUS-w411dp-h891dp")
class BlocklistSelectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fixture: IsolatedRepositoryFixture

    @Before
    fun setUp() {
        fixture = IsolatedRepositoryFixture(context)
        runBlocking {
            // Blocked in this order, so newest first is C B A, number order B C A,
            // and most blocked B A C.
            listOf(A, B, C).forEach {
                fixture.repository.blockNumber(it)
                Thread.sleep(2)
            }
            repeat(2) { fixture.dao.insertBlockedCall(BlockedCall(number = B, timestamp = it + 1L)) }
            fixture.dao.insertBlockedCall(BlockedCall(number = A, timestamp = 9L))
        }
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `picking a sort reorders the list and stores the choice`() {
        show()
        assertEquals(listOf(B, C, A), rowOrder())

        composeRule.onNodeWithText(context.getString(R.string.blocklist_sort_newest)).performClick()
        awaitOrder(listOf(C, B, A))

        composeRule.onNodeWithText(context.getString(R.string.blocklist_sort_most_blocked)).performScrollTo().performClick()
        awaitOrder(listOf(B, A, C))
        assertEquals(BlocklistSort.MOST_BLOCKED, runBlocking { fixture.repository.blocklistSort.first() })
    }

    @Test
    fun `a stored sort is what a new screen opens with`() {
        runBlocking { fixture.repository.setBlocklistSort(BlocklistSort.NEWEST) }

        show()

        awaitOrder(listOf(C, B, A))
        composeRule.onNodeWithText(context.getString(R.string.blocklist_sort_newest)).assertIsSelected()
    }

    @Test
    fun `long-pressed rows unblock together, and one Undo brings them all back`() {
        show()
        val select = context.getString(R.string.blocklist_select_action)
        assertEquals(select, row(B).fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)

        row(B).performTouchInput { longClick() }
        awaitText(context.resources.getQuantityString(R.plurals.blocklist_selection_count, 1, 1))
        selectable(C).performClick()

        val count = context.resources.getQuantityString(R.plurals.blocklist_selection_count, 2, 2)
        awaitText(count)
        assertEquals(LiveRegionMode.Polite, composeRule.onNodeWithText(count).fetchSemanticsNode().config[SemanticsProperties.LiveRegion])
        val picked = selectable(B).fetchSemanticsNode()
        assertEquals(Role.Checkbox, picked.config[SemanticsProperties.Role])

        barButton(R.string.blocklist_selection_unblock).performClick()

        awaitText(context.resources.getQuantityString(R.plurals.blocklist_selection_unblocked, 2, 2))
        awaitOrder(listOf(A))
        assertEquals(0, composeRule.onAllNodesWithTag(BLOCKLIST_SELECTION_BAR_TAG).fetchSemanticsNodes().size)
        // One number left has nothing to sort, so the chips go too.
        assertEquals(0, composeRule.onAllNodesWithText(context.getString(R.string.blocklist_sort_newest)).fetchSemanticsNodes().size)

        composeRule.onNodeWithText(context.getString(R.string.blocklist_undo)).performClick()
        awaitOrder(listOf(B, C, A))
    }

    @Test
    fun `selected rules delete together, and Cancel leaves the rest alone`() {
        runBlocking {
            fixture.repository.addWildcardRule("+1800*")
            fixture.repository.addWildcardRule("+1888*")
            fixture.repository.addWildcardRule("+1877*")
        }
        show()
        composeRule
            .onNodeWithText(context.getString(R.string.blocklist_tab_wildcards_short))
            .performScrollTo()
            .performClick()
        awaitRows(3)

        rowWithText("+1800*").performTouchInput { longClick() }
        awaitText(context.resources.getQuantityString(R.plurals.blocklist_selection_count, 1, 1))
        composeRule.onNodeWithText(context.getString(R.string.dialog_cancel)).performClick()
        awaitRows(3)
        assertEquals(0, composeRule.onAllNodesWithTag(BLOCKLIST_SELECTION_BAR_TAG).fetchSemanticsNodes().size)

        rowWithText("+1800*").performTouchInput { longClick() }
        awaitText(context.resources.getQuantityString(R.plurals.blocklist_selection_count, 1, 1))
        composeRule.onNode(hasContentDescription("+1888*") and isToggleable()).performClick()
        barButton(R.string.blocklist_selection_delete).performClick()

        awaitText(context.resources.getQuantityString(R.plurals.blocklist_selection_removed, 2, 2))
        awaitRows(1)
        assertEquals(listOf("+1877*"), runBlocking { fixture.dao.getAllWildcardRules().first() }.map { it.pattern })

        composeRule.onNodeWithText(context.getString(R.string.blocklist_undo)).performClick()
        awaitRows(3)
    }

    private fun show() {
        val adapter = SpamRepositoryAdapter(fixture.repository)
        val viewModel =
            MainViewModel(
                appContext = context,
                repo = fixture.repository,
                syncDatabase = SyncDatabaseUseCase(adapter),
                manageBlocklist = ManageBlocklistUseCase(adapter),
                exportLogs = ExportLogsUseCase(context),
            )
        composeRule.setContent { CallShieldTheme { BlocklistScreen(viewModel) } }
        awaitRows(3)
    }

    private fun label(number: String) = PhoneFormatter.formatIsolated(number)

    private fun row(number: String): SemanticsNodeInteraction = rowWithText(label(number))

    private fun rowWithText(text: String): SemanticsNodeInteraction = composeRule.onNode(hasTestTag(BLOCKLIST_SELECTABLE_ROW_TAG) and hasText(text))

    /** A row's tap target while rows are selected. */
    private fun selectable(number: String): SemanticsNodeInteraction = composeRule.onNode(hasContentDescription(label(number)) and isToggleable())

    private fun barButton(labelRes: Int): SemanticsNodeInteraction = composeRule.onNode(hasText(context.getString(labelRes)) and hasAnyAncestor(hasTestTag(BLOCKLIST_SELECTION_BAR_TAG)))

    private fun rows(): List<SemanticsNode> = composeRule.onAllNodesWithTag(BLOCKLIST_SELECTABLE_ROW_TAG).fetchSemanticsNodes()

    private fun rowOrder(): List<String> =
        rows().sortedBy { it.boundsInRoot.top }.map { node ->
            val texts =
                node.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .map { it.text }
            NUMBERS.single { label(it) in texts }
        }

    private fun awaitRows(count: Int) {
        composeRule.waitUntil(TIMEOUT_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            rows().size == count
        }
    }

    private fun awaitOrder(order: List<String>) {
        composeRule.waitUntil(TIMEOUT_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            rows().size == order.size && rowOrder() == order
        }
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(TIMEOUT_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val A = "+12125550103"
        const val B = "+12125550101"
        const val C = "+12125550102"
        val NUMBERS = listOf(A, B, C)
        const val TIMEOUT_MS = 5_000L
    }
}
