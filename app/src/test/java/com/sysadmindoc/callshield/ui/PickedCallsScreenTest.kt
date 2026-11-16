package com.sysadmindoc.callshield.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import android.provider.CallLog
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
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
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.CommunityContributor
import com.sysadmindoc.callshield.data.CommunityContributor.ContributeOutcome
import com.sysadmindoc.callshield.data.CommunityContributor.ContributeResult
import com.sysadmindoc.callshield.data.CommunityReport
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.PhoneFormatter
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.data.remote.StrictOffline
import com.sysadmindoc.callshield.data.repository.SpamRepositoryAdapter
import com.sysadmindoc.callshield.domain.usecase.ExportLogsUseCase
import com.sysadmindoc.callshield.domain.usecase.ManageBlocklistUseCase
import com.sysadmindoc.callshield.domain.usecase.SyncDatabaseUseCase
import com.sysadmindoc.callshield.service.CommunityReportWorker
import com.sysadmindoc.callshield.ui.screens.main.BlockedLogScreen
import com.sysadmindoc.callshield.ui.screens.recent.RecentCall
import com.sysadmindoc.callshield.ui.screens.recent.RecentCallsScreen
import com.sysadmindoc.callshield.ui.theme.CallShieldTheme
import kotlinx.coroutines.Dispatchers
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
 * A long press on a call in Recent or the Blocked log picks it, and the bar
 * that comes up blocks, reports or clears every picked number at once, with
 * one Undo for all of them. Only the network is faked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "en-rUS-w411dp-h891dp")
class PickedCallsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fixture = IsolatedRepositoryFixture(context)
    private val sent = mutableListOf<CommunityReport>()
    private val originalTransport = CommunityContributor.transport
    private val originalRepository = CommunityContributor.repositoryFor
    private lateinit var back: OnBackPressedDispatcher

    private val fakeNetwork: suspend (CommunityReport) -> ContributeResult = { report ->
        synchronized(sent) { sent += report }
        ContributeResult(true, ContributeOutcome.REPORTED_SPAM.name, ContributeOutcome.REPORTED_SPAM)
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration
                .Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerCoroutineContext(Dispatchers.Unconfined)
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ) = CommunityReportWorker(appContext, workerParameters, fakeNetwork) { number, vote ->
                            fixture.repository.releaseCommunityReport(number, vote)
                        }
                    },
                ).build(),
        )
        CommunityContributor.repositoryFor = { fixture.repository }
        CommunityContributor.transport = fakeNetwork
        // The log's list pages through AndroidUiDispatcher.Main, which an earlier test can leave stuck.
        reviveAndroidUiDispatcher()
    }

    @After
    fun tearDown() {
        StrictOffline.resetForTest()
        CommunityContributor.transport = originalTransport
        CommunityContributor.repositoryFor = originalRepository
        fixture.close()
    }

    @Test
    fun `picked log calls block together, and one Undo unblocks them all`() {
        runBlocking {
            fixture.dao.insertBlockedCall(BlockedCall(number = A, timestamp = 3_000L))
            fixture.dao.insertBlockedCall(BlockedCall(number = B, timestamp = 2_000L))
            fixture.dao.insertBlockedCall(BlockedCall(number = A, timestamp = 1_000L))
        }
        show { BlockedLogScreen(it) }
        awaitText(label(B))
        assertEquals(context.getString(R.string.blocklist_select_action), card(A).fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)

        card(A).performTouchInput { longClick() }
        // Both of the number's calls show as picked, and it counts once.
        awaitText(count(1))
        assertEquals(listOf(ToggleableState.On, ToggleableState.On), pickStates(A))
        selectable(B).performClick()

        awaitText(count(2))
        assertEquals(LiveRegionMode.Polite, composeRule.onNodeWithText(count(2)).fetchSemanticsNode().config[SemanticsProperties.LiveRegion])
        assertEquals(Role.Checkbox, selectable(B).fetchSemanticsNode().config[SemanticsProperties.Role])

        barButton(R.string.detail_block).performClick()

        awaitText(context.resources.getQuantityString(R.plurals.selection_blocked, 2, 2))
        await { blocked(A) && blocked(B) }
        assertEquals(context.getString(R.string.desc_blocked_from_log), row(A)?.description)
        assertEquals(0, composeRule.onAllNodesWithTag(NUMBER_SELECTION_BAR_TAG).fetchSemanticsNodes().size)

        composeRule.onNodeWithText(context.getString(R.string.detail_undo)).performClick()
        await { !blocked(A) && !blocked(B) }
    }

    @Test
    fun `Back and Cancel leave the pick with nothing done`() {
        runBlocking {
            fixture.dao.insertBlockedCall(BlockedCall(number = A, timestamp = 2_000L))
            fixture.dao.insertBlockedCall(BlockedCall(number = B, timestamp = 1_000L))
        }
        show { BlockedLogScreen(it) }
        awaitText(label(B))

        card(A).performTouchInput { longClick() }
        awaitText(count(1))
        back.onBackPressed()
        await { composeRule.onAllNodesWithTag(NUMBER_SELECTION_BAR_TAG).fetchSemanticsNodes().isEmpty() }

        card(B).performTouchInput { longClick() }
        awaitText(count(1))
        composeRule.onNodeWithText(context.getString(R.string.dialog_cancel)).performClick()
        await { composeRule.onAllNodesWithTag(NUMBER_SELECTION_BAR_TAG).fetchSemanticsNodes().isEmpty() }

        assertTrue(!blocked(A) && !blocked(B))
        assertTrue(synchronized(sent) { sent.isEmpty() })
    }

    @Test
    fun `picked Recent calls are reported and blocked together`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CALL_LOG)
        val now = System.currentTimeMillis()
        val calls =
            listOf(
                RecentCall(number = A, type = CallLog.Calls.INCOMING_TYPE, date = now - 60_000L, duration = 4),
                RecentCall(number = B, type = CallLog.Calls.MISSED_TYPE, date = now - 120_000L, duration = 0),
                RecentCall(number = C, type = CallLog.Calls.INCOMING_TYPE, date = now - 180_000L, duration = 9),
            )
        show { RecentCallsScreen(it, loadCalls = { calls }) }
        awaitText(label(C))

        card(A).performTouchInput { longClick() }
        awaitText(count(1))
        selectable(B).performClick()
        awaitText(count(2))

        barButton(R.string.detail_report).performClick()

        awaitText(context.resources.getQuantityString(R.plurals.selection_reported, 2, 2))
        await { blocked(A) && blocked(B) && synchronized(sent) { sent.size } == 2 }
        assertEquals(listOf(A, B), synchronized(sent) { sent.map { it.number } }.sorted())
        assertTrue(!blocked(C))

        composeRule.onNodeWithText(context.getString(R.string.detail_undo)).performClick()
        await { !blocked(A) && !blocked(B) }
    }

    private fun show(screen: @Composable (MainViewModel) -> Unit) {
        val adapter = SpamRepositoryAdapter(fixture.repository)
        val viewModel =
            MainViewModel(
                appContext = context,
                repo = fixture.repository,
                syncDatabase = SyncDatabaseUseCase(adapter),
                manageBlocklist = ManageBlocklistUseCase(adapter),
                exportLogs = ExportLogsUseCase(context),
            )
        composeRule.setContent {
            back = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            CallShieldTheme { screen(viewModel) }
        }
    }

    private fun label(number: String) = PhoneFormatter.formatIsolated(number)

    private fun count(n: Int) = context.resources.getQuantityString(R.plurals.blocklist_selection_count, n, n)

    /** The call's card, before anything is picked; the first when the number called twice. */
    private fun card(number: String): SemanticsNodeInteraction = composeRule.onAllNodes(hasText(label(number)) and hasClickAction())[0]

    /** A call's tap target while calls are picked. */
    private fun selectable(number: String): SemanticsNodeInteraction = composeRule.onAllNodes(hasContentDescription(label(number)) and isToggleable())[0]

    private fun pickStates(number: String): List<ToggleableState> =
        composeRule
            .onAllNodes(hasContentDescription(label(number)) and isToggleable())
            .fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.ToggleableState] }

    private fun barButton(labelRes: Int): SemanticsNodeInteraction = composeRule.onNode(hasText(context.getString(labelRes)) and hasClickAction() and hasAnyAncestor(hasTestTag(NUMBER_SELECTION_BAR_TAG)))

    private fun row(number: String) = runBlocking { fixture.dao.findByNumber(number) }

    private fun blocked(number: String) = row(number)?.isUserBlocked == true

    private fun await(done: () -> Boolean) {
        composeRule.waitUntil(TIMEOUT_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            done()
        }
    }

    private fun awaitText(text: String) {
        await { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        const val A = "+12125550111"
        const val B = "+12125550112"
        const val C = "+12125550113"
        const val TIMEOUT_MS = 5_000L
    }
}
