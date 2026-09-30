package com.sysadmindoc.callshield.data.repository

import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.remote.GitHubDataSource
import com.sysadmindoc.callshield.data.remote.SpamDataSource
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
import java.io.File
import java.io.IOException

/**
 * A feed mirror can serve any catalog that was ever signed. A list a later
 * revision took out must stay out when an older one is served again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListCatalogRefreshTest {
    private val bundled = File("../data/list_catalog.json").readText()
    private var served: Result<String> = Result.failure(IOException("offline"))
    private val remote =
        object : SpamDataSource by GitHubDataSource() {
            override suspend fun fetchListCatalogJson(
                owner: String,
                repo: String,
            ) = served
        }
    private val fixture = IsolatedRepositoryFixture(ApplicationProvider.getApplicationContext(), remote = remote)

    @After
    fun tearDown() = fixture.close()

    @Test
    fun `a newer catalog is kept and an older one served later is ignored`() =
        runBlocking {
            served = Result.success(revision(3))
            assertTrue(fixture.repository.refreshListCatalog())
            assertEquals("SpamChile 3", chile())

            served = Result.success(revision(2))
            assertFalse(fixture.repository.refreshListCatalog())
            assertEquals("SpamChile 3", chile())
        }

    @Test
    fun `a failed fetch keeps the last good catalog`() =
        runBlocking {
            served = Result.success(revision(2))
            fixture.repository.refreshListCatalog()
            served = Result.failure(IOException("offline"))

            assertFalse(fixture.repository.refreshListCatalog())
            assertEquals("SpamChile 2", chile())
        }

    private suspend fun chile(): String =
        fixture.repository.listCatalog
            .first()
            .single { it.id == "spamchile-cl" }
            .name

    private fun revision(number: Int): String =
        bundled
            .replace("\"revision\": 1,", "\"revision\": $number,")
            .replace("\"name\": \"SpamChile\"", "\"name\": \"SpamChile $number\"")
}
