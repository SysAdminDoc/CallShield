package com.sysadmindoc.callshield.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.material3.adaptive.Posture
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.data.repository.SpamRepositoryAdapter
import com.sysadmindoc.callshield.domain.model.BlockReasonCode
import com.sysadmindoc.callshield.domain.usecase.ExportLogsUseCase
import com.sysadmindoc.callshield.domain.usecase.ManageBlocklistUseCase
import com.sysadmindoc.callshield.domain.usecase.SyncDatabaseUseCase
import com.sysadmindoc.callshield.ui.screens.stats.THIRTY_DAY_CARD_TAG
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Statistics counts the last 30 days by kind of check, TalkBack reads each
 * count with its label, and a row opens the Blocked log at just those entries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "en-rUS-w411dp-h891dp")
class StatsBreakdownTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fixture: IsolatedRepositoryFixture
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        fixture = IsolatedRepositoryFixture(context)
        SpamRepository.replaceInstanceForTests(fixture.repository)
        val now = System.currentTimeMillis()
        runBlocking {
            fixture.repository.setOnboardingDone(true)
            fixture.dao.insertBlockedCall(call(WILDCARD_CALL, now - HOUR, BlockReasonCode.WILDCARD))
            fixture.dao.insertBlockedCall(
                call(BLOCKLIST_TEXT, now - 2 * HOUR, BlockReasonCode.USER_BLOCKLIST).copy(isCall = false, smsBody = "Lunch?"),
            )
            fixture.dao.insertBlockedCall(call(DATABASE_CALL, now - 3 * HOUR, BlockReasonCode.DATABASE))
            fixture.dao.insertBlockedCall(call(OLD_PREFIX_CALL, now - 40 * DAY, BlockReasonCode.PREFIX))
        }
        val adapter = SpamRepositoryAdapter(fixture.repository)
        viewModel =
            MainViewModel(
                appContext = context,
                repo = fixture.repository,
                syncDatabase = SyncDatabaseUseCase(adapter),
                manageBlocklist = ManageBlocklistUseCase(adapter),
                exportLogs = ExportLogsUseCase(context),
            )
        // The log pages through AndroidUiDispatcher.Main, which an earlier test can leave stuck.
        reviveAndroidUiDispatcher()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        SpamRepository.replaceInstanceForTests(null)
        ViewModelStore().apply { put("viewModel", viewModel) }.clear()
        shadowOf(Looper.getMainLooper()).idle()
        fixture.close()
    }

    @Test
    fun `each group reads its 30-day count and the rules row opens just its entries`() {
        openStatistics()

        // The 40-day-old prefix block is outside the window, so only two rules count.
        assertEquals(listOf(yourRules, "2"), texts(row(yourRules)))
        assertEquals(listOf(databaseLabel, "1"), texts(row(databaseLabel)))
        assertEquals(listOf(community, "0"), texts(row(community)))
        assertEquals(listOf(ml, "0"), texts(row(ml)))
        assertEquals(listOf(campaigns, "0"), texts(row(campaigns)))
        assertEquals(0, count(hasText(string(R.string.stats_group_other)) and inCard))

        assertEquals(string(R.string.stats_group_open_log), row(yourRules).config[SemanticsActions.OnClick].label)
        // Nothing to open behind a zero.
        assertFalse(SemanticsActions.OnClick in row(community).config)

        composeRule.onNode(hasText(yourRules) and inCard).performScrollTo().performClick()
        val chip = context.getString(R.string.blocked_log_filter_group, yourRules)
        await { count(hasText(chip)) > 0 && count(hasText(label(WILDCARD_CALL))) > 0 }

        assertEquals(1, count(hasText(label(BLOCKLIST_TEXT))))
        assertEquals(0, count(hasText(label(DATABASE_CALL))))
        assertEquals(0, count(hasText(label(OLD_PREFIX_CALL))))
        // The log still holds all four, the old one included.
        assertEquals(1, count(hasText(context.getString(R.string.blocked_log_filter_all, 4))))

        // Every reason, with no time limit, brings the rest of the log back.
        composeRule.onNode(hasText(chip) and hasClickAction()).performClick()
        composeRule.onNode(hasText(string(R.string.blocked_log_filter_all_reasons)) and hasClickAction()).performClick()
        await { count(hasText(label(DATABASE_CALL))) > 0 && count(hasText(chip)) == 0 }
        composeRule
            .onNode(hasScrollToNodeAction() and hasAnyDescendant(hasText(label(DATABASE_CALL))))
            .performScrollToNode(hasText(label(OLD_PREFIX_CALL)))
        assertEquals(1, count(hasText(label(OLD_PREFIX_CALL))))
    }

    private fun openStatistics() {
        composeRule.setContent { CallShieldRoot(viewModel = viewModel, posture = Posture()) }
        await { count(hasTestTag(NAV_BAR_TAG)) > 0 }
        composeRule.onNode(hasText(string(R.string.nav_more)) and hasClickAction() and hasAnyAncestor(hasTestTag(NAV_BAR_TAG))).performClick()
        composeRule.onAllNodes(hasText(string(R.string.more_statistics)) and hasClickAction()).onFirst().performClick()
        await { count(hasText(databaseLabel) and inCard) > 0 && texts(row(databaseLabel)).last() == "1" }
    }

    private val inCard = hasAnyAncestor(hasTestTag(THIRTY_DAY_CARD_TAG))

    private fun row(label: String): SemanticsNode = composeRule.onNode(hasText(label) and inCard).fetchSemanticsNode()

    private fun texts(node: SemanticsNode): List<String> = node.config[SemanticsProperties.Text].map { it.text }

    private fun call(
        number: String,
        timestamp: Long,
        reason: BlockReasonCode,
    ) = BlockedCall(number = number, timestamp = timestamp, matchReason = reason.wireValue, reasonCode = reason)

    private fun label(number: String) = PhoneFormatter.formatIsolated(number)

    private fun string(res: Int) = context.getString(res)

    private fun count(matcher: SemanticsMatcher): Int = composeRule.onAllNodes(matcher).fetchSemanticsNodes().size

    private fun await(condition: () -> Boolean) {
        composeRule.waitUntil(TIMEOUT_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }

    private val yourRules get() = string(R.string.stats_group_your_rules)
    private val databaseLabel get() = string(R.string.stats_group_database)
    private val community get() = string(R.string.stats_group_community)
    private val ml get() = string(R.string.stats_group_ml)
    private val campaigns get() = string(R.string.stats_group_campaigns)

    private companion object {
        const val WILDCARD_CALL = "+12129460101"
        const val BLOCKLIST_TEXT = "+12129460102"
        const val DATABASE_CALL = "+12129460103"
        const val OLD_PREFIX_CALL = "+12129460104"
        const val HOUR = 60 * 60 * 1000L
        const val DAY = 24 * HOUR
        const val TIMEOUT_MS = 10_000L
    }
}
