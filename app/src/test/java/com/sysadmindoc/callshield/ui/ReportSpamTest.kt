package com.sysadmindoc.callshield.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.sysadmindoc.callshield.data.CommunityContributor
import com.sysadmindoc.callshield.data.CommunityContributor.ContributeOutcome
import com.sysadmindoc.callshield.data.CommunityContributor.ContributeResult
import com.sysadmindoc.callshield.data.CommunityReport
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.repository.SpamRepositoryAdapter
import com.sysadmindoc.callshield.domain.usecase.ExportLogsUseCase
import com.sysadmindoc.callshield.domain.usecase.ManageBlocklistUseCase
import com.sysadmindoc.callshield.domain.usecase.SyncDatabaseUseCase
import com.sysadmindoc.callshield.service.CommunityReportWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Report on a number's screen blocks the number on this phone and sends the
 * anonymous report. One report rarely blocks a number for everyone, so a
 * reporter who didn't also tap Block kept getting the calls. Only the network
 * is faked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReportSpamTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val number = "+12125550177"
    private val fixture = IsolatedRepositoryFixture(context)
    private val sent = mutableListOf<CommunityReport>()
    private val originalTransport = CommunityContributor.transport
    private val originalRepository = CommunityContributor.repositoryFor
    private lateinit var viewModel: MainViewModel

    private val fakeNetwork: suspend (CommunityReport) -> ContributeResult = { report ->
        sent += report
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
        CommunityContributor.transport = originalTransport
        CommunityContributor.repositoryFor = originalRepository
        fixture.close()
    }

    @Test
    fun `Report blocks the number on this phone and sends the report`() {
        viewModel.reportSpam(number, "robocall", blockHere = true)
        val outcome = awaitOutcome()

        assertNotNull(outcome.blockUndo)
        assertEquals(true, runBlocking { fixture.dao.findByNumber(number) }?.isUserBlocked)
        assertEquals(listOf(number), sent.map { it.number })
    }

    @Test
    fun `Undo removes the block and the report stays sent`() {
        viewModel.reportSpam(number, "spam", blockHere = true)
        val undo = requireNotNull(awaitOutcome().blockUndo)

        runBlocking { fixture.repository.undoBlock(undo) }

        assertNull(runBlocking { fixture.dao.findByNumber(number) }?.takeIf { it.isUserBlocked })
        assertEquals(1, sent.size)
    }

    @Test
    fun `a number the user blocked already is only reported`() {
        viewModel.reportSpam(number, "spam", blockHere = false)
        val outcome = awaitOutcome()

        assertNull(outcome.blockUndo)
        assertNull(runBlocking { fixture.dao.findByNumber(number) })
        assertEquals(1, sent.size)
    }

    private fun awaitOutcome(): MainViewModel.ReportOutcome {
        // The block and the report run on the view model's scope; wait for both.
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.reportOutcome.value == null && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        return requireNotNull(viewModel.reportOutcome.value) { "Report never finished" }
    }
}
