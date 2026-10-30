package com.sysadmindoc.callshield.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.CallCategory
import com.sysadmindoc.callshield.data.CategoryCallAction
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.data.repository.SpamRepositoryAdapter
import com.sysadmindoc.callshield.domain.usecase.ExportLogsUseCase
import com.sysadmindoc.callshield.domain.usecase.ManageBlocklistUseCase
import com.sysadmindoc.callshield.domain.usecase.SyncDatabaseUseCase
import com.sysadmindoc.callshield.ui.screens.lookup.LookupMode
import com.sysadmindoc.callshield.ui.screens.lookup.LookupModeRow
import com.sysadmindoc.callshield.ui.screens.main.BlockedLogScreen
import com.sysadmindoc.callshield.ui.screens.main.BlocklistScreen
import com.sysadmindoc.callshield.ui.screens.settings.CATEGORY_CALL_ACTION_TAG_PREFIX
import com.sysadmindoc.callshield.ui.screens.settings.CategoryCallActionsSheet
import com.sysadmindoc.callshield.ui.screens.settings.LogSettings
import com.sysadmindoc.callshield.ui.theme.CallShieldTheme
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

/**
 * Chips where only one can be on read as radio buttons in a group. Material's
 * FilterChip calls every chip a check box, which tells a TalkBack user they can
 * pick several.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "en-rUS-w411dp-h891dp")
class SingleChoiceChipRoleTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fixture: IsolatedRepositoryFixture

    @Before
    fun setUp() {
        fixture = IsolatedRepositoryFixture(context)
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `the Activity log's type filter is one choice, and the reason filter reads as a drop-down`() {
        runBlocking {
            fixture.dao.insertBlockedCalls(listOf(BlockedCall(number = CALLER, timestamp = System.currentTimeMillis(), matchReason = "heuristic", logKey = "block")))
        }
        composeRule.setContent { CallShieldTheme { BlockedLogScreen(viewModel()) } }
        val all = context.getString(R.string.blocked_log_filter_all, 1)
        val reason = context.getString(R.string.blocked_log_filter_reason)
        awaitText(all)
        awaitText(reason)

        assertOneChoice(listOf(all, context.getString(R.string.blocked_log_filter_calls), context.getString(R.string.blocked_log_filter_sms)).map(::hasText))
        assertEquals(Role.DropdownList, composeRule.onNodeWithText(reason).fetchSemanticsNode().config[SemanticsProperties.Role])
    }

    @Test
    fun `the blocklist sort is one choice`() {
        runBlocking { listOf(CALLER, OTHER_CALLER).forEach { fixture.repository.blockNumber(it) } }
        composeRule.setContent { CallShieldTheme { BlocklistScreen(viewModel()) } }
        val sorts = listOf(R.string.blocklist_sort_newest, R.string.blocklist_sort_number, R.string.blocklist_sort_most_blocked).map(context::getString)
        awaitText(sorts.first())

        assertOneChoice(sorts.map(::hasText))
    }

    @Test
    fun `Lookup's Number and Message switch is one choice`() {
        composeRule.setContent { CallShieldTheme { LookupModeRow(LookupMode.MESSAGE) {} } }

        assertOneChoice(listOf(R.string.lookup_mode_number, R.string.lookup_mode_message).map { hasText(context.getString(it)) })
    }

    @Test
    fun `the log's keep-for days are one choice`() {
        runBlocking {
            fixture.repository.setAutoCleanup(true)
            fixture.repository.setCleanupDays(30)
        }
        composeRule.setContent { CallShieldTheme { LogSettings(viewModel()) } }
        val days = listOf(7, 14, 30, 90).map { context.getString(R.string.settings_days, it) }
        awaitText(days.first())

        assertOneChoice(days.map(::hasText))
    }

    @Test
    fun `a category's call action is one choice`() {
        composeRule.setContent {
            CallShieldTheme { CategoryCallActionsSheet(mapOf(CallCategory.Robocall to CategoryCallAction.SILENCE), { _, _ -> }, {}) }
        }

        assertOneChoice(CategoryCallAction.entries.map { hasTestTag("${CATEGORY_CALL_ACTION_TAG_PREFIX}robocall:${it.storageKey}") })
    }

    /** Each chip reads as a radio button inside a selectable group, and only one of them is on. */
    private fun assertOneChoice(chips: List<SemanticsMatcher>) {
        val radioInGroup =
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and
                hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
        val selected = chips.map { composeRule.onNode(it and isSelectable() and radioInGroup).fetchSemanticsNode().config[SemanticsProperties.Selected] }
        assertEquals(1, selected.count { it })
    }

    private fun viewModel(): MainViewModel {
        val adapter = SpamRepositoryAdapter(fixture.repository)
        return MainViewModel(
            appContext = context,
            repo = fixture.repository,
            syncDatabase = SyncDatabaseUseCase(adapter),
            manageBlocklist = ManageBlocklistUseCase(adapter),
            exportLogs = ExportLogsUseCase(context),
        )
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(TIMEOUT_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val CALLER = "+12125550146"
        const val OTHER_CALLER = "+12125550147"
        const val TIMEOUT_MS = 5_000L
    }
}
