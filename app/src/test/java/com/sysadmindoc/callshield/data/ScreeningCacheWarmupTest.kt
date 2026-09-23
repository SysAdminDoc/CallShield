package com.sysadmindoc.callshield.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.model.SpamPrefix
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The first screened call after a process start used to load the prefix and
 * rule caches inside its five-second budget. Startup now warms them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ScreeningCacheWarmupTest {
    private lateinit var fixture: IsolatedRepositoryFixture
    private val number = "+12125550199"

    @Before
    fun setUp() {
        fixture = IsolatedRepositoryFixture(ApplicationProvider.getApplicationContext())
        runBlocking { fixture.dao.insertPrefixes(listOf(SpamPrefix(prefix = "+1212555", type = "robocall"))) }
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `warming loads the prefix cache before the first call`() =
        runBlocking {
            fixture.repository.warmScreeningCaches()
            // Removed behind the cache's back: only a warmed cache still knows it.
            fixture.dao.deleteAllPrefixes()

            assertEquals("prefix", fixture.repository.isSpam(number).matchSource)
        }

    @Test
    fun `without warming the first call reads the table itself`() =
        runBlocking {
            fixture.dao.deleteAllPrefixes()

            assertFalse(fixture.repository.isSpam(number).matchSource == "prefix")
        }
}
