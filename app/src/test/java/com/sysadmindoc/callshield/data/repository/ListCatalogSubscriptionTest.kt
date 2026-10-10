package com.sysadmindoc.callshield.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.ExternalBlocklistParser
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.model.ExternalBlocklistRefreshOutcome
import com.sysadmindoc.callshield.data.model.ExternalBlocklistSubscription
import com.sysadmindoc.callshield.data.model.ListCatalogEntry
import com.sysadmindoc.callshield.data.remote.ExternalBlocklistDataSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * On a phone outside Colombia, OpenCallShield's "3131918305" read as a US
 * number and its "03395051735" as nothing at all. Added from the catalog, the
 * list's declared number plan makes them Colombian first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListCatalogSubscriptionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val feed = FixedFeed()
    private val fixture = IsolatedRepositoryFixture(context, externalBlocklistDataSource = feed)
    private val repository = fixture.repository

    @After
    fun tearDown() = fixture.close()

    @Test
    fun `the bundled catalog shows before any sync`() {
        val catalog = runBlocking { repository.listCatalog.first() }

        assertEquals(listOf("opencallshield-co", "spamchile-cl", "turkish-spam-numbers-tr", "listahu-py"), catalog.map { it.id })
    }

    @Test
    fun `a list added from the catalog stores its national rows as that country's numbers`() {
        val result = runBlocking { repository.applyCatalogListSubscription(openCallShield()) }

        assertTrue(result.message, result.success)
        assertEquals(COLOMBIAN, rows())
        val subscription = subscriptions().single()
        assertEquals("OpenCallShield", subscription.label)
        assertEquals("opencallshield-co", subscription.catalogId)
        assertEquals("57", subscription.numberPlan?.callingCode)
    }

    @Test
    fun `a refresh reads the list with the same plan`() {
        runBlocking { repository.applyCatalogListSubscription(openCallShield()) }
        feed.body = OPEN_CALL_SHIELD_BODY.replace("03395051735", "03395051736")

        val outcomes = runBlocking { repository.refreshDueExternalBlocklists(System.currentTimeMillis() + MONTH) }

        assertEquals(listOf(ExternalBlocklistRefreshOutcome.REFRESHED), outcomes)
        assertEquals(COLOMBIAN - "+573395051735" + "+573395051736", rows())
    }

    @Test
    fun `the catalog list's link typed by hand reads the same way`() {
        val result = runBlocking { repository.applyExternalBlocklistSubscription(openCallShield().url, "") }

        assertTrue(result.message, result.success)
        assertEquals(COLOMBIAN, rows())
        assertEquals("OpenCallShield", subscriptions().single().label)
    }

    @Test
    fun `a list typed in before the catalog existed gets its plan on the next refresh`() {
        val url = openCallShield().url
        val id = ExternalBlocklistParser.idForUrl(url)
        runBlocking {
            fixture.settingsStore.edit {
                it[SpamRepository.KEY_EXTERNAL_BLOCKLIST_SUBSCRIPTIONS] =
                    """[{"id":"$id","label":"Colombia","url":"$url","enabled":true,"lastSyncedAt":1}]"""
            }
        }

        runBlocking { repository.refreshDueExternalBlocklists(System.currentTimeMillis()) }

        assertEquals(COLOMBIAN, rows())
        assertEquals("opencallshield-co", subscriptions().single().catalogId)
    }

    @Test
    fun `a link that isn't in the catalog is read as this phone's numbers, as before`() {
        val result = runBlocking { repository.applyExternalBlocklistSubscription("https://lists.example.test/co.json", "") }

        assertTrue(result.message, result.success)
        // The phone reads the national rows its own way: a US phone as US
        // numbers, this test phone, with no region, as written. Only the row
        // that was already international comes out Colombian.
        assertEquals(setOf("+573398133564"), rows().filter { it.startsWith("+57") }.toSet())
    }

    @Test
    fun `a downloaded catalog replaces the bundled one only when it's newer`() {
        val bundled = File("../data/list_catalog.json").readText()
        val newer = bundled.replace("\"revision\": 1,", "\"revision\": 2,").replace("\"name\": \"SpamChile\"", "\"name\": \"SpamChile 2\"")
        val older = bundled.replace("\"revision\": 1,", "\"revision\": 0,")

        runBlocking { fixture.settingsStore.edit { it[SpamRepository.KEY_LIST_CATALOG] = newer } }
        assertEquals("SpamChile 2", runBlocking { repository.listCatalog.first() }[1].name)

        runBlocking { fixture.settingsStore.edit { it[SpamRepository.KEY_LIST_CATALOG] = older } }
        assertEquals("SpamChile", runBlocking { repository.listCatalog.first() }[1].name)
    }

    private fun openCallShield(): ListCatalogEntry = runBlocking { repository.listCatalog.first() }.first { it.id == "opencallshield-co" }

    private fun subscriptions(): List<ExternalBlocklistSubscription> = runBlocking { repository.externalBlocklistSubscriptions.first() }

    private fun rows(): Set<String> =
        runBlocking {
            subscriptions()
                .flatMap { fixture.dao.getNumbersBySource(it.source) }
                .map { it.number }
                .toSet()
        }

    private class FixedFeed : ExternalBlocklistDataSource {
        var body = OPEN_CALL_SHIELD_BODY

        override suspend fun fetchText(url: String): Result<String> = Result.success(body)
    }

    private companion object {
        const val MONTH = 30L * 24L * 60L * 60L * 1000L

        // OpenCallShield's own shape, with one row of each form it uses.
        val OPEN_CALL_SHIELD_BODY =
            """
            {"version": "1.0", "updated_at": "2026-09-29", "numbers": [
              {"number": "+12025550143", "reports": 2, "tag": "example"},
              {"number": "3131918305", "reports": 3, "tag": "spam"},
              {"number": "03395051735", "reports": 3, "tag": "spam"},
              {"number": "00573390714583", "reports": 1, "tag": "spam"},
              {"number": "+573398133564", "reports": 1, "tag": "spam"}
            ]}
            """.trimIndent()

        val COLOMBIAN = setOf("+12025550143", "+573131918305", "+573395051735", "+573390714583", "+573398133564")
    }
}
