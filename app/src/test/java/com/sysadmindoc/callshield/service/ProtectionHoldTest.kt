package com.sysadmindoc.callshield.service

import android.app.Application
import android.content.Context
import android.os.Looper
import android.text.format.DateFormat
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.BlockingProfiles
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.UnknownCallWindow
import com.sysadmindoc.callshield.data.repository.SpamRepositoryAdapter
import com.sysadmindoc.callshield.domain.usecase.ExportLogsUseCase
import com.sysadmindoc.callshield.domain.usecase.ManageBlocklistUseCase
import com.sysadmindoc.callshield.domain.usecase.SyncDatabaseUseCase
import com.sysadmindoc.callshield.ui.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.util.Date

/**
 * Turning protection off during a call from someone the user doesn't know,
 * or in the ten minutes after it, waits until then. The same change with no
 * such call around applies at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ProtectionHoldTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fixture: IsolatedRepositoryFixture
    private lateinit var viewModel: MainViewModel
    private val window = UnknownCallWindow.shared

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        ProtectionHold.resetForTests()
        window.clear()
        fixture = IsolatedRepositoryFixture(context)
        SpamRepository.replaceInstanceForTests(fixture.repository)
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
        window.clear()
        ProtectionHold.resetForTests()
        SpamRepository.replaceInstanceForTests(null)
        ViewModelStore().apply { put("viewModel", viewModel) }.clear()
        shadowOf(Looper.getMainLooper()).idle()
        fixture.close()
    }

    @Test
    fun `call blocking turned off during a stranger's call waits until ten minutes after it ends`() {
        val call = window.callStarted(STRANGER)
        finish(viewModel.setBlockCalls(false))

        assertTrue(read(fixture.repository.blockCallsEnabled))
        assertEquals(setOf(ProtectionHold.Switch.BLOCK_CALLS), held().switchesOff)
        assertNull(held().releaseAt)
        assertEquals(string(R.string.protection_hold_after_call), ShadowToast.getTextOfLatestToast())

        // While the call goes on, it looks again a minute later and changes nothing.
        assertEquals(ProtectionHold.RECHECK_MS, release())
        assertTrue(read(fixture.repository.blockCallsEnabled))

        window.callEnded(call)
        release()
        val releaseAt = window.snapshot()!!.endedAt!! + UnknownCallWindow.AFTER_CALL_MS
        assertEquals(releaseAt, held().releaseAt)
        assertTrue(read(fixture.repository.blockCallsEnabled))

        // A change in the ten minutes after the call says the time it takes effect.
        finish(viewModel.setSmsContent(false))
        val time = DateFormat.getTimeFormat(context).format(Date(releaseAt))
        assertEquals(context.getString(R.string.protection_hold_at, time), ShadowToast.getTextOfLatestToast())
        assertTrue(read(fixture.repository.smsContentEnabled))

        window.clear()
        assertNull(release())
        assertFalse(read(fixture.repository.blockCallsEnabled))
        assertFalse(read(fixture.repository.smsContentEnabled))
        assertTrue(held().isEmpty)
    }

    @Test
    fun `the release time is set as soon as the call ends, not at the next minute's look`() {
        val call = window.callStarted(STRANGER)
        finish(viewModel.setBlockCalls(false))
        assertNull(held().releaseAt)

        window.callEnded(call)
        await { ProtectionHold.callEnded(context, window, isContact = { false }) }

        val releaseAt = window.snapshot()!!.endedAt!! + UnknownCallWindow.AFTER_CALL_MS
        assertEquals(releaseAt, held().releaseAt)
        assertTrue(read(fixture.repository.blockCallsEnabled))
        val queued =
            WorkManager
                .getInstance(context)
                .getWorkInfosForUniqueWork(ProtectionHold.WORK_NAME)
                .get()
                .single { it.state == WorkInfo.State.ENQUEUED }
        assertTrue(
            "the release waits for the ten minutes, not the minute's look",
            queued.initialDelayMillis > UnknownCallWindow.AFTER_CALL_MS - ProtectionHold.RECHECK_MS,
        )
    }

    @Test
    fun `the hold ends when the ten minutes after the call are up`() {
        var now = 1_000_000L
        val clocked = UnknownCallWindow { now }
        val call = clocked.callStarted(STRANGER)
        val outcome = await { ProtectionHold.setSwitch(context, ProtectionHold.Switch.BLOCK_SMS, false, window = clocked, isContact = { false }) }
        assertEquals(ProtectionHold.Outcome.Held(null), outcome)

        now += 30 * 60 * 1000L
        clocked.callEnded(call)
        val ended = now
        // The window still counts on its last millisecond.
        now = ended + UnknownCallWindow.AFTER_CALL_MS
        assertTrue(releaseDue(clocked) != null)
        assertEquals(ended + UnknownCallWindow.AFTER_CALL_MS, held().releaseAt)
        assertTrue(read(fixture.repository.blockSmsEnabled))

        now += 1
        assertNull(releaseDue(clocked))
        assertFalse(read(fixture.repository.blockSmsEnabled))
    }

    @Test
    fun `with no call around the change applies at once`() {
        finish(viewModel.setBlockSms(false))

        assertFalse(read(fixture.repository.blockSmsEnabled))
        assertTrue(held().isEmpty)
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `turning it back on drops the change that was waiting`() {
        window.callStarted(STRANGER)
        finish(viewModel.setBlockCalls(false))
        finish(viewModel.setBlockCalls(true))

        assertTrue(held().isEmpty)
        window.clear()
        assertNull(release())
        assertTrue(read(fixture.repository.blockCallsEnabled))
    }

    @Test
    fun `a call from a contact holds nothing but a hidden number does`() {
        window.callStarted(FRIEND)
        val friend = await { ProtectionHold.setSwitch(context, ProtectionHold.Switch.BLOCK_CALLS, false, isContact = { it == FRIEND }) }
        assertEquals(ProtectionHold.Outcome.Applied, friend)
        assertFalse(read(fixture.repository.blockCallsEnabled))

        window.clear()
        window.callStarted("")
        val hidden = await { ProtectionHold.setSwitch(context, ProtectionHold.Switch.BLOCK_SMS, false, isContact = { true }) }
        assertEquals(ProtectionHold.Outcome.Held(null), hidden)
        assertTrue(read(fixture.repository.blockSmsEnabled))
    }

    @Test
    fun `trusting more contacts waits and trusting fewer applies at once`() {
        await { fixture.repository.setSelectedContactGroups(setOf(FAMILY, WORK)) }
        assertEquals(setOf(FAMILY, WORK), read(fixture.repository.selectedContactGroups))
        window.callStarted(STRANGER)

        // Every contact.
        finish(viewModel.setSelectedContactGroups(emptySet()))
        assertEquals(emptySet<String>(), held().contactGroups)
        assertEquals(setOf(FAMILY, WORK), read(fixture.repository.selectedContactGroups))
        assertEquals(string(R.string.protection_hold_after_call), ShadowToast.getTextOfLatestToast())

        // A group that wasn't trusted before.
        finish(viewModel.setSelectedContactGroups(setOf(FAMILY, GYM)))
        assertEquals(setOf(FAMILY, GYM), held().contactGroups)
        assertEquals(setOf(FAMILY, WORK), read(fixture.repository.selectedContactGroups))

        finish(viewModel.setSelectedContactGroups(setOf(FAMILY)))
        assertNull(held().contactGroups)
        assertEquals(setOf(FAMILY), read(fixture.repository.selectedContactGroups))
    }

    @Test
    fun `widening trust is any group the old limit left out, or no limit at all`() {
        assertTrue(ProtectionHold.widensTrust(setOf("family"), emptySet()))
        assertTrue(ProtectionHold.widensTrust(setOf("family"), setOf("family", "work")))
        assertFalse(ProtectionHold.widensTrust(setOf("family", "work"), setOf("work")))
        assertFalse(ProtectionHold.widensTrust(setOf("family"), setOf("family")))
        // With no limit, every contact was trusted already.
        assertFalse(ProtectionHold.widensTrust(emptySet(), setOf("family")))
    }

    @Test
    fun `the Off profile keeps screening on until the call is over and applies the rest now`() {
        await { viewModel.applyProfileHeld(BlockingProfiles.Profile.MAX) }
        window.callStarted(STRANGER)

        val applied = await { viewModel.applyProfileHeld(BlockingProfiles.Profile.OFF) }

        assertEquals(ProtectionHold.Outcome.Held(null), applied.outcome)
        assertTrue(read(fixture.repository.blockCallsEnabled))
        assertTrue(read(fixture.repository.blockSmsEnabled))
        assertFalse(read(fixture.repository.aggressiveModeEnabled))
        assertFalse(read(fixture.repository.blockUnknownEnabled))
        assertEquals(setOf(ProtectionHold.Switch.BLOCK_CALLS, ProtectionHold.Switch.BLOCK_SMS), held().switchesOff)
        assertEquals(string(R.string.protection_hold_after_call), ShadowToast.getTextOfLatestToast())

        // Undo puts Maximum back, which turns nothing off.
        await { viewModel.undoProfile(applied.previous) }
        assertTrue(held().isEmpty)
        assertTrue(read(fixture.repository.aggressiveModeEnabled))
        window.clear()
        assertNull(release())
        assertTrue(read(fixture.repository.blockCallsEnabled))
    }

    @Test
    fun `the worker looks again while the call counts and applies the change once it's over`() {
        window.callStarted(STRANGER)
        finish(viewModel.setBlockCalls(false))
        // Dropped, so what's queued after the run is the worker's own next look.
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(ProtectionHold.WORK_NAME).result.get()

        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertTrue(read(fixture.repository.blockCallsEnabled))
        val queued = workManager.getWorkInfosForUniqueWork(ProtectionHold.WORK_NAME).get()
        assertTrue(queued.any { it.state == WorkInfo.State.ENQUEUED })

        window.clear()
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertFalse(read(fixture.repository.blockCallsEnabled))
        assertTrue(held().isEmpty)
    }

    private fun runWorker(): ListenableWorker.Result = await { TestListenableWorkerBuilder<ProtectionHoldWorker>(context).build().doWork() }

    private fun releaseDue(clocked: UnknownCallWindow): Long? = await { ProtectionHold.releaseDue(context, window = clocked, isContact = { false }) }

    private fun held(): ProtectionHold.Pending = ProtectionHold.pending(context).value

    private fun release(): Long? = await { ProtectionHold.releaseDue(context) }

    private fun <T> read(flow: Flow<T>): T = await { flow.first() }

    private fun string(res: Int) = context.getString(res)

    /**
     * Runs [block] on IO while the main looper keeps moving. Under Robolectric
     * the main thread is this one, and a ViewModel collector can be part way
     * through the store's first read: DataStore holds its init lock until that
     * collector resumes on Main, so a runBlocking read here waited forever.
     */
    private fun <T> await(block: suspend () -> T): T {
        val result = CoroutineScope(Dispatchers.IO).async { block() }
        finish(result)
        // Already complete, so this only hands back the value or the failure.
        return runBlocking { result.await() }
    }

    /** The ViewModel launches on the main looper and hops to IO, so both have to run. */
    private fun finish(job: Job) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!job.isCompleted) {
            if (System.currentTimeMillis() >= deadline) {
                job.cancel()
                error("The change didn't finish")
            }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private companion object {
        const val STRANGER = "+12129460199"
        const val FRIEND = "+12129460198"
        const val TIMEOUT_MS = 10_000L

        // Group keys are SHA-256 hex, and the repository drops anything else.
        val FAMILY = "a".repeat(64)
        val WORK = "b".repeat(64)
        val GYM = "c".repeat(64)
    }
}
