package com.sysadmindoc.callshield.data

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.domain.model.BlockReasonCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Prefix expansion blocks a caller whose number differs from listed spam only
 * in its last two digits. One bad row used to be enough to flag the whole
 * hundred; the minimum lets a user ask for more than one before it blocks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DbPrefixMinimumTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fixture: IsolatedRepositoryFixture

    @Before
    fun setUp() {
        fixture = IsolatedRepositoryFixture(context)
        runBlocking {
            fixture.settingsStore.edit {
                it[SpamRepository.KEY_DB_PREFIX_EXPANSION] = true
                // Nothing else gets a say on these made-up numbers.
                it[SpamRepository.KEY_HEURISTICS] = false
                it[SpamRepository.KEY_ML_SCORER] = false
                it[SpamRepository.KEY_NEIGHBOR_SPOOF] = false
            }
        }
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `the default still blocks on one listed neighbor`() =
        runBlocking {
            list("+12125550101")

            assertEquals(1, fixture.repository.dbPrefixMinMatches.first())
            assertBlocked(count = 1)
        }

    @Test
    fun `a minimum of two waits for the second listed neighbor`() =
        runBlocking {
            setMinimum(2)

            list("+12125550101")
            assertNotBlocked()

            list("+12125550102")
            assertBlocked(count = 2)
        }

    @Test
    fun `a minimum of three turns on at the third listed neighbor and not before`() =
        runBlocking {
            setMinimum(3)

            list("+12125550101", "+12125550102")
            assertNotBlocked()

            list("+12125550103")
            assertBlocked(count = 3)
        }

    @Test
    fun `one number listed in two spellings counts once`() =
        runBlocking {
            setMinimum(2)

            list("+12125550101", "2125550101")

            assertNotBlocked()
        }

    @Test
    fun `numbers outside the caller's hundred don't count`() =
        runBlocking {
            setMinimum(2)

            list("+12125550101", "+12125550201")

            assertNotBlocked()
        }

    @Test
    fun `the minimum stays between one and ten`() =
        runBlocking {
            fixture.repository.setDbPrefixMinMatches(0)
            assertEquals(1, fixture.repository.dbPrefixMinMatches.first())

            fixture.repository.setDbPrefixMinMatches(50)
            assertEquals(10, fixture.repository.dbPrefixMinMatches.first())
        }

    private suspend fun setMinimum(minimum: Int) {
        fixture.repository.setDbPrefixMinMatches(minimum)
        assertEquals(minimum, fixture.repository.dbPrefixMinMatches.first())
    }

    private suspend fun list(vararg numbers: String) {
        numbers.forEach { fixture.dao.insertNumber(SpamNumber(number = it, type = "robocall")) }
    }

    private suspend fun assertBlocked(count: Int) {
        val result = fixture.repository.isSpam(CALLER)
        assertEquals(BlockReasonCode.DB_PREFIX_EXPANSION, result.reasonCode)
        val noun = if (count == 1) "spam number" else "spam numbers"
        assertEquals("Shares all but its last two digits with $count $noun", result.description)
    }

    private suspend fun assertNotBlocked() {
        assertNotEquals(BlockReasonCode.DB_PREFIX_EXPANSION, fixture.repository.isSpam(CALLER).reasonCode)
    }

    private companion object {
        const val CALLER = "+12125550150"
    }
}
