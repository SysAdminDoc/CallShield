package com.sysadmindoc.callshield.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.ExternalBlocklistParser
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.model.ExternalBlocklistSubscription
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.remote.ExternalBlocklistDataSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Removing a list deleted its numbers straight away with no way back short of
 * adding it again, which needs the list's server to still be up. Undo puts the
 * list and its numbers back from what the removal took out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalBlocklistUndoTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val feed = CountingFeed()
    private val fixture = IsolatedRepositoryFixture(context, externalBlocklistDataSource = feed)
    private val repository = fixture.repository

    @After
    fun tearDown() = fixture.close()

    @Test
    fun `undo puts the list back in its place with its numbers, without downloading it again`() {
        val first = subscribe(FIRST_URL, "+12125550101\n+12125550102\n+12125550103")
        subscribe(SECOND_URL, "+13125550101")
        val before = subscriptions()
        val fetches = feed.fetches

        assertTrue(runBlocking { repository.removeExternalBlocklistSubscription(first.id) }.success)
        assertEquals(emptySet<String>(), rows(first))

        val undone = runBlocking { repository.undoRemoveExternalBlocklistSubscription(first.id) }

        assertTrue(undone.message, undone.success)
        assertEquals(before, subscriptions())
        assertEquals(setOf("+12125550101", "+12125550102", "+12125550103"), rows(first))
        assertEquals("no download", fetches, feed.fetches)
    }

    @Test
    fun `a number claimed since the removal keeps its new owner`() {
        val list = subscribe(FIRST_URL, "+12125550101\n+12125550102")
        runBlocking { repository.removeExternalBlocklistSubscription(list.id) }
        runBlocking { fixture.dao.insertNumber(SpamNumber(number = "+12125550101", type = "robocall", source = "github")) }

        runBlocking { repository.undoRemoveExternalBlocklistSubscription(list.id) }

        assertEquals("github", runBlocking { fixture.dao.getNumbersByNumbers(listOf("+12125550101")) }.single().source)
        assertEquals(setOf("+12125550102"), rows(list))
    }

    @Test
    fun `a removal can be undone once, and not after the list was added again`() {
        val list = subscribe(FIRST_URL, "+12125550101")
        runBlocking { repository.removeExternalBlocklistSubscription(list.id) }

        assertTrue(runBlocking { repository.undoRemoveExternalBlocklistSubscription(list.id) }.success)
        assertFalse(runBlocking { repository.undoRemoveExternalBlocklistSubscription(list.id) }.success)

        runBlocking { repository.removeExternalBlocklistSubscription(list.id) }
        subscribe(FIRST_URL, "+12125550109")

        assertFalse(runBlocking { repository.undoRemoveExternalBlocklistSubscription(list.id) }.success)
        assertEquals(setOf("+12125550109"), rows(list))
    }

    private fun subscribe(
        url: String,
        body: String,
    ): ExternalBlocklistSubscription {
        feed.body = body
        val applied = runBlocking { repository.applyExternalBlocklistSubscription(url, "") }
        assertTrue(applied.message, applied.success)
        return subscriptions().single { it.id == ExternalBlocklistParser.idForUrl(url) }
    }

    private fun subscriptions() = runBlocking { repository.externalBlocklistSubscriptions.first() }

    private fun rows(subscription: ExternalBlocklistSubscription): Set<String> =
        runBlocking {
            fixture.dao
                .getNumbersBySource(subscription.source)
                .map { it.number }
                .toSet()
        }

    private class CountingFeed : ExternalBlocklistDataSource {
        var body = ""
        var fetches = 0

        override suspend fun fetchText(url: String): Result<String> {
            fetches++
            return Result.success(body)
        }
    }

    private companion object {
        const val FIRST_URL = "https://lists.example.test/first.txt"
        const val SECOND_URL = "https://lists.example.test/second.txt"
    }
}
