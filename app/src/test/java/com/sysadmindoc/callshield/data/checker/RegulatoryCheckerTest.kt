package com.sysadmindoc.callshield.data.checker

import android.content.Context
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.SpamRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RegulatoryCheckerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun ctx(
        number: String,
        prefs: androidx.datastore.preferences.core.Preferences = emptyPreferences(),
    ) = CheckContext(appContext = context, number = number, realtimeCall = true, prefs = prefs)

    @Test
    fun `Spain 400 blocks when enabled`() =
        runBlocking {
            val result =
                RegulatoryPrefixChecker().check(
                    ctx("+34400123456", preferencesOf(SpamRepository.KEY_REG_SPAIN_400 to true)),
                )
            assertNotNull(result)
            assertEquals("regulatory_prefix", result!!.matchSource)
        }

    @Test
    fun `Spain 400 passes when disabled`() =
        runBlocking {
            assertNull(
                RegulatoryPrefixChecker().check(
                    ctx("+34400123456", preferencesOf(SpamRepository.KEY_REG_SPAIN_400 to false)),
                ),
            )
        }

    @Test
    fun `Spain non-400 number passes`() =
        runBlocking {
            assertNull(
                RegulatoryPrefixChecker().check(
                    ctx("+34612345678", preferencesOf(SpamRepository.KEY_REG_SPAIN_400 to true)),
                ),
            )
        }

    @Test
    fun `India 140 blocks when enabled`() =
        runBlocking {
            val result =
                RegulatoryPrefixChecker().check(
                    ctx("+911401234567", preferencesOf(SpamRepository.KEY_REG_INDIA_140 to true)),
                )
            assertNotNull(result)
            assertEquals("regulatory_prefix", result!!.matchSource)
        }

    @Test
    fun `India 140 passes when disabled`() =
        runBlocking {
            assertNull(
                RegulatoryPrefixChecker().check(
                    ctx("+911401234567", preferencesOf(SpamRepository.KEY_REG_INDIA_140 to false)),
                ),
            )
        }

    @Test
    fun `Brazil 0303 blocks when enabled`() =
        runBlocking {
            val result =
                RegulatoryPrefixChecker().check(
                    ctx("+5503031234567", preferencesOf(SpamRepository.KEY_REG_BRAZIL_0303 to true)),
                )
            assertNotNull(result)
            assertEquals("regulatory_prefix", result!!.matchSource)
        }

    @Test
    fun `Brazil 0303 passes when disabled`() =
        runBlocking {
            assertNull(
                RegulatoryPrefixChecker().check(
                    ctx("+5503031234567", preferencesOf(SpamRepository.KEY_REG_BRAZIL_0303 to false)),
                ),
            )
        }

    @Test
    fun `India 1600 allows when enabled`() =
        runBlocking {
            val result =
                RegulatoryAllowChecker().check(
                    ctx("+9116001234567", preferencesOf(SpamRepository.KEY_REG_INDIA_1600_ALLOW to true)),
                )
            assertNotNull(result)
            assertEquals("india_1600_protected", result!!.matchSource)
            assertTrue(!result.shouldBlock)
        }

    @Test
    fun `India 1600 passes when disabled`() =
        runBlocking {
            assertNull(
                RegulatoryAllowChecker().check(
                    ctx("+9116001234567", preferencesOf(SpamRepository.KEY_REG_INDIA_1600_ALLOW to false)),
                ),
            )
        }

    @Test
    fun `India non-1600 number is not allowed`() =
        runBlocking {
            assertNull(
                RegulatoryAllowChecker().check(
                    ctx("+911234567890", preferencesOf(SpamRepository.KEY_REG_INDIA_1600_ALLOW to true)),
                ),
            )
        }

    @Test
    fun `regulatory allow sits above database and heuristic blocks`() {
        val allow = RegulatoryAllowChecker()
        assertTrue(allow.priority > CheckerPriority.GITHUB_DATABASE)
        assertTrue(allow.priority > CheckerPriority.HEURISTIC)
    }

    @Test
    fun `regulatory prefix sits below the downloaded prefix matcher`() {
        val prefix = RegulatoryPrefixChecker()
        assertTrue(prefix.priority < CheckerPriority.PREFIX_MATCH)
        assertTrue(prefix.priority > CheckerPriority.STIR_SHAKEN_TRUSTED)
    }
}
