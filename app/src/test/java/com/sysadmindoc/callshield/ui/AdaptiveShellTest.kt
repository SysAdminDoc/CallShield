package com.sysadmindoc.callshield.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.adaptive.HingeInfo
import androidx.compose.material3.adaptive.Posture
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.repository.SpamRepositoryAdapter
import com.sysadmindoc.callshield.domain.usecase.ExportLogsUseCase
import com.sysadmindoc.callshield.domain.usecase.ManageBlocklistUseCase
import com.sysadmindoc.callshield.domain.usecase.SyncDatabaseUseCase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * The real root at phone, landscape, tablet and half-open fold sizes: where
 * the tabs go, where a number's details open, that nothing with a label sits
 * on a fold, and what back does at each step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "en-rUS")
class AdaptiveShellTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fixture: IsolatedRepositoryFixture
    private lateinit var viewModel: MainViewModel
    private lateinit var back: OnBackPressedDispatcher

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        fixture = IsolatedRepositoryFixture(context)
        SpamRepository.replaceInstanceForTests(fixture.repository)
        runBlocking { fixture.repository.setOnboardingDone(true) }
        val adapter = SpamRepositoryAdapter(fixture.repository)
        viewModel =
            MainViewModel(
                appContext = context,
                repo = fixture.repository,
                syncDatabase = SyncDatabaseUseCase(adapter),
                manageBlocklist = ManageBlocklistUseCase(adapter),
                exportLogs = ExportLogsUseCase(context),
            )
        // Search and the log page through AndroidUiDispatcher.Main, which an earlier test can leave stuck.
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
    @Config(qualifiers = "+w411dp-h891dp")
    fun `a phone keeps the bottom bar and a number's details replace the tabs`() {
        // The window's real posture, as the app reads it.
        show(posture = null)
        assertShown(NAV_BAR_TAG)
        assertGone(NAV_RAIL_TAG)
        assertGone(SHELL_PANE_TAG)

        openNumber()
        assertGone(NAV_BAR_TAG)
        assertGone(DETAIL_PANE_TAG)

        back.onBackPressed()
        idle()
        assertNull(viewModel.selectedNumber.value)
        assertShown(NAV_BAR_TAG)
        assertEquals(0, count(hasContentDescription(GO_BACK)))
    }

    @Test
    @Config(qualifiers = "+w891dp-h411dp")
    fun `a phone on its side keeps one pane and moves the tabs to a rail`() {
        show()
        assertShown(NAV_RAIL_TAG)
        assertGone(NAV_BAR_TAG)
        assertGone(SHELL_PANE_TAG)

        openNumber()
        assertGone(NAV_RAIL_TAG)
    }

    @Test
    @Config(qualifiers = "+w1280dp-h800dp")
    fun `a landscape tablet opens a number's details beside the tabs`() {
        show()
        val shell = bounds(SHELL_PANE_TAG)
        val detail = bounds(DETAIL_PANE_TAG)
        assertTrue("panes side by side: $shell, $detail", shell.right <= detail.left && abs(shell.top - detail.top) < 1f)
        assertTrue("about half each: $shell, $detail", abs(shell.width - detail.width) <= px(1.5f))
        // Half of this window is still wide enough for the rail.
        assertShown(NAV_RAIL_TAG)
        assertEquals(1, count(hasText(PLACEHOLDER) and inPane(DETAIL_PANE_TAG)))

        openNumber()
        assertEquals(1, count(hasContentDescription(GO_BACK) and inPane(DETAIL_PANE_TAG)))
        assertEquals(0, count(hasText(PLACEHOLDER)))
        assertShown(NAV_RAIL_TAG)

        back.onBackPressed()
        idle()
        assertNull(viewModel.selectedNumber.value)
        assertEquals(1, count(hasText(PLACEHOLDER) and inPane(DETAIL_PANE_TAG)))
        assertShown(NAV_RAIL_TAG)
    }

    @Test
    @Config(qualifiers = "+w800dp-h1280dp")
    fun `a portrait tablet keeps one pane with a rail`() {
        show()
        assertGone(SHELL_PANE_TAG)
        assertShown(NAV_RAIL_TAG)
    }

    @Test
    @Config(qualifiers = "+w760dp-h900dp")
    fun `a half-open book fold splits at the hinge and keeps labels off it`() {
        val hinge = Rect(px(380f), 0f, px(400f), px(900f))
        show(fold(hinge, vertical = true))
        val shell = bounds(SHELL_PANE_TAG)
        val detail = bounds(DETAIL_PANE_TAG)
        assertEquals(hinge.left, shell.right, 1f)
        assertEquals(hinge.right, detail.left, 1f)
        // 380 dp is too narrow for the rail.
        assertShown(NAV_BAR_TAG)
        assertNothingLabelledOn(hinge)

        openNumber()
        assertEquals(1, count(hasContentDescription(GO_BACK) and inPane(DETAIL_PANE_TAG)))
        assertNothingLabelledOn(hinge)
    }

    @Test
    @Config(qualifiers = "+w760dp-h900dp")
    fun `right to left, the tabs take the right side of a book fold`() {
        val hinge = Rect(px(380f), 0f, px(400f), px(900f))
        show(fold(hinge, vertical = true), LayoutDirection.Rtl)
        val shell = bounds(SHELL_PANE_TAG)
        val detail = bounds(DETAIL_PANE_TAG)
        assertEquals(hinge.right, shell.left, 1f)
        assertEquals(hinge.left, detail.right, 1f)
        assertNothingLabelledOn(hinge)
    }

    @Test
    @Config(qualifiers = "+w700dp-h840dp")
    fun `tabletop puts the tabs above the fold and the details below it`() {
        val hinge = Rect(0f, px(450f), px(700f), px(470f))
        show(fold(hinge, vertical = false))
        val shell = bounds(SHELL_PANE_TAG)
        val detail = bounds(DETAIL_PANE_TAG)
        assertEquals(hinge.top, shell.bottom, 1f)
        assertEquals(hinge.bottom, detail.top, 1f)
        assertShown(NAV_RAIL_TAG)
        assertNothingLabelledOn(hinge)

        openNumber()
        assertNothingLabelledOn(hinge)
    }

    @Test
    @Config(qualifiers = "+w411dp-h891dp")
    fun `a back swipe shrinks the details, a cancelled one leaves them open, and a finished one closes them`() {
        show()
        openNumber()
        val atRest = goBackWidth()

        back.dispatchOnBackStarted(BackEventCompat(touchX = 0f, touchY = 400f, progress = 0f, swipeEdge = BackEventCompat.EDGE_LEFT))
        back.dispatchOnBackProgressed(BackEventCompat(touchX = 120f, touchY = 400f, progress = 0.5f, swipeEdge = BackEventCompat.EDGE_LEFT))
        idle()
        assertEquals(atRest * 0.95f, goBackWidth(), 0.5f)

        back.dispatchOnBackCancelled()
        idle()
        assertNotNull(viewModel.selectedNumber.value)
        assertEquals(atRest, goBackWidth(), 0.01f)

        back.dispatchOnBackStarted(BackEventCompat(touchX = 0f, touchY = 400f, progress = 0f, swipeEdge = BackEventCompat.EDGE_LEFT))
        back.dispatchOnBackProgressed(BackEventCompat(touchX = 200f, touchY = 400f, progress = 0.8f, swipeEdge = BackEventCompat.EDGE_LEFT))
        back.onBackPressed()
        idle()
        assertNull(viewModel.selectedNumber.value)
        assertShown(NAV_BAR_TAG)
    }

    @Test
    @Config(qualifiers = "+w411dp-h891dp")
    fun `back closes search instead of leaving the app`() {
        show()
        composeRule.onAllNodes(hasContentDescription(SEARCH) and hasClickAction()).onFirst().performClick()
        idle()
        assertEquals(1, count(hasContentDescription(CLOSE_SEARCH)))

        assertTrue(back.hasEnabledCallbacks())
        back.onBackPressed()
        idle()
        assertEquals(0, count(hasContentDescription(CLOSE_SEARCH)))
        assertShown(NAV_BAR_TAG)
    }

    /** Shows the root, with [posture] standing in for the window's, or the window's own when null. */
    private fun show(
        posture: Posture? = Posture(),
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) {
        composeRule.setContent {
            back = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                if (posture == null) CallShieldRoot(viewModel = viewModel) else CallShieldRoot(viewModel = viewModel, posture = posture)
            }
        }
        await { count(hasTestTag(NAV_BAR_TAG) or hasTestTag(NAV_RAIL_TAG)) > 0 }
    }

    private fun openNumber() {
        composeRule.runOnIdle { viewModel.openNumberDetail(NUMBER) }
        await { count(hasContentDescription(GO_BACK)) > 0 }
    }

    private fun fold(
        hinge: Rect,
        vertical: Boolean,
    ) = Posture(
        isTabletop = !vertical,
        hingeList = listOf(HingeInfo(hinge, isFlat = false, isVertical = vertical, isSeparating = true, isOccluding = false)),
    )

    private fun assertNothingLabelledOn(hinge: Rect) {
        val labelled =
            composeRule
                .onAllNodes(
                    SemanticsMatcher("has a label") {
                        SemanticsProperties.Text in it.config || SemanticsProperties.ContentDescription in it.config
                    },
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
        assertTrue(labelled.isNotEmpty())
        assertEquals(emptyList<String>(), labelled.filter { it.boundsInWindow.overlaps(hinge) }.map(::describe))
    }

    private fun describe(node: SemanticsNode): String {
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        return "${text ?: description} at ${node.boundsInWindow}"
    }

    private fun goBackWidth(): Float =
        composeRule
            .onAllNodes(hasContentDescription(GO_BACK))
            .fetchSemanticsNodes()
            .single()
            .boundsInWindow.width

    private fun bounds(tag: String): Rect =
        composeRule
            .onAllNodes(hasTestTag(tag))
            .fetchSemanticsNodes()
            .single()
            .boundsInWindow

    private fun inPane(tag: String) = hasAnyAncestor(hasTestTag(tag))

    private fun count(matcher: SemanticsMatcher): Int = composeRule.onAllNodes(matcher).fetchSemanticsNodes().size

    private fun assertShown(tag: String) = assertEquals("$tag shown", 1, count(hasTestTag(tag)))

    private fun assertGone(tag: String) = assertEquals("$tag gone", 0, count(hasTestTag(tag)))

    private fun px(dp: Float): Float = dp * composeRule.density.density

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
    }

    private fun await(condition: () -> Boolean) {
        composeRule.waitUntil(TIMEOUT_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }

    private companion object {
        const val NUMBER = "+12129460192"
        const val GO_BACK = "Go back"
        const val SEARCH = "Search"
        const val CLOSE_SEARCH = "Close search"
        const val PLACEHOLDER = "Tap a number to see its details here."
        const val TIMEOUT_MS = 10_000L
    }
}
