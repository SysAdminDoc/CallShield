package com.sysadmindoc.callshield.data

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.sysadmindoc.callshield.data.CommunityContributor.ContributeOutcome
import com.sysadmindoc.callshield.data.CommunityContributor.ContributeResult
import com.sysadmindoc.callshield.data.SmsContentAnalyzer.SmsReportIndicators
import com.sysadmindoc.callshield.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Issue #10's report arrived three times minutes apart, and one number
 * arrived 12 times in a day. The day's ledger lives in DataStore, so a
 * repeat is caught across a process death as well as across a double tap.
 *
 * Robolectric supplies a real SDK level: on a bare JVM DataStore sees SDK 0
 * and falls back to File.renameTo, which can't replace a file on Windows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CommunityReportSubmitterTest {
    private val directory: File = Files.createTempDirectory("callshield-report-ledger-").toFile()
    private val stores = mutableListOf<Store>()
    private val sent = mutableListOf<String>()
    private val queued = mutableListOf<String>()
    private var outcome = ContributeOutcome.REPORTED_SPAM
    private var now = 1_790_000_000_000L

    /** One process's view of the settings files. Closing it is a process death. */
    private inner class Store : AutoCloseable {
        private val job = SupervisorJob()
        private val scope = CoroutineScope(job + Dispatchers.IO)
        val settings =
            SettingsRepository(
                dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(directory, "settings.preferences_pb") },
                privateDataStore = PreferenceDataStoreFactory.create(scope = scope) { File(directory, "private.preferences_pb") },
            )

        override fun close() = runBlocking { job.cancelAndJoin() }
    }

    private fun store() = Store().also(stores::add)

    private fun submitter(store: Store) =
        CommunityReportSubmitter(
            claim = store.settings::claimCommunityReport,
            transport = { number, type, _: SmsReportIndicators? ->
                sent += "$number:$type"
                ContributeResult(!outcome.isTransient, outcome.name, outcome)
            },
            enqueue = { number, type, _ -> queued += "$number:$type" },
            clock = { now },
        )

    private fun Store.submit(
        number: String,
        type: String = "spam",
    ) = runBlocking { submitter(this@submit).submit(number, type, null) }

    @After
    fun tearDown() {
        stores.forEach(Store::close)
        directory.deleteRecursively()
    }

    @Test
    fun `a second report of the same number and vote never reaches the network`() {
        val store = store()

        assertEquals(ContributeOutcome.REPORTED_SPAM, store.submit("+1 212 234 0101").outcome)
        val second = store.submit("+12122340101")

        assertEquals(ContributeOutcome.ALREADY_SUBMITTED, second.outcome)
        assertTrue(second.success)
        assertEquals(listOf("+12122340101:spam"), sent)
    }

    @Test
    fun `the day's record survives a process death`() {
        val before = store()
        before.submit("+12122340101")
        before.close()
        stores.remove(before)

        val after = store()

        assertEquals(ContributeOutcome.ALREADY_SUBMITTED, after.submit("+12122340101").outcome)
        assertEquals(1, sent.size)
    }

    @Test
    fun `a correction is a different report, and a day later the same one goes out again`() {
        val store = store()
        store.submit("+12122340101", "spam")
        outcome = ContributeOutcome.REPORTED_NOT_SPAM

        assertEquals(ContributeOutcome.REPORTED_NOT_SPAM, store.submit("+12122340101", "not_spam").outcome)

        outcome = ContributeOutcome.REPORTED_SPAM
        now += TimeUnit.HOURS.toMillis(25)
        assertEquals(ContributeOutcome.REPORTED_SPAM, store.submit("+12122340101", "spam").outcome)
        assertEquals(listOf("+12122340101:spam", "+12122340101:not_spam", "+12122340101:spam"), sent)
    }

    @Test
    fun `a report that can't go out now is queued once and not dropped`() {
        val store = store()
        outcome = ContributeOutcome.NETWORK_ERROR

        val first = store.submit("+12122340101")
        val retapped = store.submit("+12122340101")

        assertEquals(ContributeOutcome.QUEUED, first.outcome)
        assertTrue(first.success)
        assertEquals(ContributeOutcome.ALREADY_SUBMITTED, retapped.outcome)
        assertEquals(listOf("+12122340101:spam"), queued)
        assertEquals(1, sent.size)
    }

    @Test
    fun `a refused number is not queued, and an unreadable one is not even recorded`() {
        val store = store()
        outcome = ContributeOutcome.INVALID_NUMBER

        assertEquals(ContributeOutcome.INVALID_NUMBER, store.submit("+12122340101").outcome)
        assertEquals(ContributeOutcome.INVALID_NUMBER, store.submit("12").outcome)
        assertEquals(emptyList<String>(), queued)
        assertEquals(listOf("+12122340101:spam"), sent)
    }
}
