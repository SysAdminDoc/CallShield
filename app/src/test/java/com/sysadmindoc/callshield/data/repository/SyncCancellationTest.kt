package com.sysadmindoc.callshield.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.model.SpamDatabase
import com.sysadmindoc.callshield.data.remote.ExternalBlocklistDataSource
import com.sysadmindoc.callshield.data.remote.SpamDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.IOException

/**
 * A sync or list download whose caller went away (the worker was stopped, the
 * screen closed) landed in the catch-all and was logged as a failure. A
 * CancellationException that isn't the caller's own, like the one Room throws
 * from a closed database, is still a failed sync, so Sync can be tapped again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncCancellationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val caller = Job()
    private val remote = StoppingRemote()
    private val feed = StoppingFeed()
    private val fixture = IsolatedRepositoryFixture(context, externalBlocklistDataSource = feed, remote = remote)

    @After
    fun tearDown() = fixture.close()

    @Test
    fun `a sync whose caller is cancelled ends cancelled, not as a failure`() {
        remote.onCheck = { stopCaller() }

        val result = runInCaller { fixture.repository.syncFromGitHub(force = true) }

        assertNull(result)
        assertTrue(caller.isCancelled)
        assertEquals(emptyList<String>(), failures("Spam database sync failed"))
    }

    @Test
    fun `a CancellationException that isn't the caller's still ends as a failed sync`() {
        remote.onCheck = { throw CancellationException("database closed") }

        val result = runInCaller { fixture.repository.syncFromGitHub(force = true) }

        assertFalse(caller.isCancelled)
        assertEquals(false, result?.success)
        assertEquals(1, failures("Spam database sync failed").size)
    }

    @Test
    fun `a list download whose caller is cancelled ends cancelled, not as a failure`() {
        feed.onFetch = { stopCaller() }

        val result = runInCaller { fixture.repository.previewExternalBlocklistSubscription(LIST_URL) }

        assertNull(result)
        assertTrue(caller.isCancelled)
        assertEquals(emptyList<String>(), failures("External blocklist operation failed"))
    }

    /** Cancels the caller the way a stopped worker does, then reaches a suspension point as a real download would. */
    private suspend fun stopCaller() {
        caller.cancel()
        yield()
    }

    private fun <T> runInCaller(block: suspend () -> T): T? =
        runBlocking {
            ShadowLog.reset()
            var result: T? = null
            launch(caller) { result = block() }.join()
            result
        }

    private fun failures(message: String) = ShadowLog.getLogsForTag("SyncRepository").filter { it.msg == message }.map { it.throwable.toString() }

    private class StoppingRemote : SpamDataSource {
        var onCheck: suspend () -> Unit = {}

        override suspend fun checkForUpdate(
            owner: String,
            repo: String,
        ): Result<String> {
            onCheck()
            return Result.failure(IOException("offline"))
        }

        override suspend fun fetchSpamDatabase(
            owner: String,
            repo: String,
        ): Result<SpamDatabase> = Result.failure(IOException("offline"))

        override fun parseSpamDatabaseJson(body: String): Result<SpamDatabase> = Result.failure(UnsupportedOperationException())
    }

    private class StoppingFeed : ExternalBlocklistDataSource {
        var onFetch: suspend () -> Unit = {}

        override suspend fun fetchText(url: String): Result<String> {
            onFetch()
            return Result.success("+12125550101")
        }
    }

    private companion object {
        const val LIST_URL = "https://lists.example.test/numbers.txt"
    }
}
