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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PrefixCacheExpiryTest {
    private var now = 1_000_000L
    private lateinit var fixture: IsolatedRepositoryFixture

    @Before
    fun setUp() {
        fixture = IsolatedRepositoryFixture(ApplicationProvider.getApplicationContext(), wallClock = { now })
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `cached prefixes stop blocking at each expiry without a sync`() =
        runBlocking {
            fixture.dao.insertPrefixes(
                listOf(
                    SpamPrefix(prefix = "+1212555", type = "robocall", evidenceExpiresAt = now + 1_000L),
                    SpamPrefix(prefix = "+1212666", type = "robocall", evidenceExpiresAt = now + 2_000L),
                    SpamPrefix(prefix = "+1312555", type = "robocall"),
                ),
            )
            fixture.repository.warmScreeningCaches()

            assertEquals("prefix", fixture.repository.isSpam("+12125550199").matchSource)
            assertEquals("prefix", fixture.repository.isSpam("+12126660199").matchSource)

            now += 1_000L
            assertFalse(fixture.repository.isSpam("+12125550199").matchSource == "prefix")
            assertEquals("prefix", fixture.repository.isSpam("+12126660199").matchSource)

            now += 1_000L
            assertFalse(fixture.repository.isSpam("+12126660199").matchSource == "prefix")
            assertEquals("prefix", fixture.repository.isSpam("+13125550199").matchSource)
        }
}
