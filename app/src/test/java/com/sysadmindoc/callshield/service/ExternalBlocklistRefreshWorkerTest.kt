package com.sysadmindoc.callshield.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.ExternalBlocklistParser
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.model.ExternalBlocklistSubscription
import com.sysadmindoc.callshield.data.remote.ExternalBlocklistDataSource
import com.sysadmindoc.callshield.data.remote.ExternalBlocklistHttpException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A subscription was fetched only when it was added or switched back on, so a
 * list that changes daily went stale on the device indefinitely. These drive
 * the real worker over a real repository with the list server faked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalBlocklistRefreshWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val feed = ScriptedFeed()
    private val fixture = IsolatedRepositoryFixture(context, externalBlocklistDataSource = feed)
    private val url = "https://lists.example.test/daily.txt"
    private val source = ExternalBlocklistSubscription.sourceFor(ExternalBlocklistParser.idForUrl(url))
    private val hour = TimeUnit.HOURS.toMillis(1)

    @After
    fun tearDown() = fixture.close()

    @Test
    fun `a stale subscription is refreshed and a failed refresh keeps the last good rows`() {
        feed.body = "+12125550101\n+12125550102\n+12125550103"
        val addedAt = subscribe()

        runWorkerAt(addedAt + 23 * hour)
        assertEquals("not due within a day of the last sync", 1, feed.fetches)

        feed.body = "+12125550101\n+12125550102\n+12125550104"
        runWorkerAt(addedAt + 25 * hour)
        assertEquals(2, feed.fetches)
        assertEquals(setOf("+12125550101", "+12125550102", "+12125550104"), rows())
        assertEquals(addedAt + 25 * hour, subscription().lastSyncedAt)
        assertEquals("", subscription().lastError)

        feed.failure = IOException("unreachable")
        runWorkerAt(addedAt + 50 * hour)
        assertEquals(3, feed.fetches)
        assertEquals(setOf("+12125550101", "+12125550102", "+12125550104"), rows())
        val failed = subscription()
        assertEquals("the last good sync still stands", addedAt + 25 * hour, failed.lastSyncedAt)
        assertEquals(addedAt + 50 * hour, failed.lastAttemptAt)
        assertEquals(context.getString(R.string.external_blocklist_refresh_failed), failed.lastError)

        runWorkerAt(addedAt + 52 * hour)
        assertEquals("a failing list is left alone for six hours", 3, feed.fetches)

        feed.failure = null
        runWorkerAt(addedAt + 56 * hour)
        assertEquals(4, feed.fetches)
        assertEquals(addedAt + 56 * hour, subscription().lastSyncedAt)
        assertEquals("", subscription().lastError)
    }

    @Test
    fun `a list that moved says so on its row instead of a generic failure`() {
        feed.body = "+12125550101"
        val addedAt = subscribe()

        feed.failure = ExternalBlocklistHttpException(404)
        runWorkerAt(addedAt + 25 * hour)

        assertEquals(context.getString(R.string.external_blocklist_refresh_http, 404), subscription().lastError)
        assertEquals(setOf("+12125550101"), rows())
    }

    @Test
    fun `a refresh that loses more than half the list is held back`() {
        feed.body = "+12125550101\n+12125550102\n+12125550103\n+12125550104"
        val addedAt = subscribe()
        val original = rows()

        feed.body = "+12125550101"
        runWorkerAt(addedAt + 25 * hour)

        assertEquals(original, rows())
        val held = subscription()
        assertEquals(addedAt, held.lastSyncedAt)
        assertEquals(context.getString(R.string.external_blocklist_refresh_held, 1, 4), held.lastError)
    }

    @Test
    fun `an empty download is held back rather than wiping the list`() {
        feed.body = "+12125550101\n+12125550102"
        val addedAt = subscribe()

        feed.body = "# nothing listed today"
        runWorkerAt(addedAt + 25 * hour)

        assertEquals(setOf("+12125550101", "+12125550102"), rows())
        assertEquals(context.getString(R.string.external_blocklist_refresh_held, 0, 2), subscription().lastError)
    }

    @Test
    fun `a list's own Expires line sets how often it is fetched`() {
        feed.body = "# Expires: 12 hours\n+12125550101"
        val addedAt = subscribe()
        assertEquals(12, subscription().declaredRefreshHours)

        runWorkerAt(addedAt + 11 * hour)
        assertEquals(1, feed.fetches)
        runWorkerAt(addedAt + 13 * hour)
        assertEquals(2, feed.fetches)
    }

    @Test
    fun `a disabled subscription is never fetched`() {
        feed.body = "+12125550101"
        val addedAt = subscribe()
        runBlocking { fixture.repository.setExternalBlocklistSubscriptionEnabled(subscription().id, enabled = false) }

        runWorkerAt(addedAt + 90 * hour)

        assertEquals(1, feed.fetches)
        assertEquals(emptySet<String>(), rows())
    }

    private fun subscribe(): Long {
        val applied = runBlocking { fixture.repository.applyExternalBlocklistSubscription(url, "Daily list") }
        assertTrue(applied.message, applied.success)
        return subscription().lastSyncedAt
    }

    private fun runWorkerAt(now: Long) {
        val worker =
            TestListenableWorkerBuilder<ExternalBlocklistRefreshWorker>(context)
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ) = ExternalBlocklistRefreshWorker(appContext, workerParameters, fixture.repository)
                    },
                ).build()
        worker.clock = { now }
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
    }

    private fun subscription(): ExternalBlocklistSubscription =
        runBlocking {
            fixture.repository.externalBlocklistSubscriptions
                .first()
                .single()
        }

    private fun rows(): Set<String> =
        runBlocking {
            fixture.dao
                .getNumbersBySource(source)
                .map { it.number }
                .toSet()
        }

    private class ScriptedFeed : ExternalBlocklistDataSource {
        var body = ""
        var failure: Exception? = null
        var fetches = 0

        override suspend fun fetchText(url: String): Result<String> {
            fetches++
            return failure?.let { Result.failure(it) } ?: Result.success(body)
        }
    }
}
