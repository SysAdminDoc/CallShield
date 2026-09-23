package com.sysadmindoc.callshield.data.checker

import android.content.Context
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.RegulatoryPrefix
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.domain.model.BlockReasonCode
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
            // E.164 drops the national trunk 0, so 0303 123 4567 is +55 303 123 4567.
            val result =
                RegulatoryPrefixChecker().check(
                    ctx("+553031234567", preferencesOf(SpamRepository.KEY_REG_BRAZIL_0303 to true)),
                )
            assertNotNull(result)
            assertEquals("regulatory_prefix", result!!.matchSource)
            assertEquals(BlockReasonCode.REGULATORY_PREFIX, result.reasonCode)
            assertEquals("telemarketer", result.type)
        }

    @Test
    fun `Brazil 0303 passes when disabled`() =
        runBlocking {
            assertNull(
                RegulatoryPrefixChecker().check(
                    ctx("+553031234567", preferencesOf(SpamRepository.KEY_REG_BRAZIL_0303 to false)),
                ),
            )
        }

    @Test
    fun `a number Android left in national form still matches its range`() =
        runBlocking {
            val allOn =
                preferencesOf(
                    SpamRepository.KEY_REG_SPAIN_400 to true,
                    SpamRepository.KEY_REG_INDIA_140 to true,
                    SpamRepository.KEY_REG_BRAZIL_0303 to true,
                )
            assertNotNull(RegulatoryPrefixChecker(homeRegion = { "ES" }).check(ctx("400123456", allOn)))
            assertNotNull(RegulatoryPrefixChecker(homeRegion = { "IN" }).check(ctx("1401234567", allOn)))
            assertNotNull(RegulatoryPrefixChecker(homeRegion = { "BR" }).check(ctx("03031234567", allOn)))
            // Same leading digits at another length are some other number.
            assertNull(RegulatoryPrefixChecker(homeRegion = { "ES" }).check(ctx("4001234567", allOn)))
            assertNull(RegulatoryPrefixChecker(homeRegion = { "BR" }).check(ctx("030312345678", allOn)))
        }

    @Test
    fun `a national form only means that country on a phone from that country`() =
        runBlocking {
            val allOn = preferencesOf(*RegulatoryPrefix.entries.map { it.key to true }.toTypedArray())
            val usPhone = { _: Context -> "US" }
            // A malformed caller ID shaped like an Indian bank's must not ring through on a US phone.
            assertNull(RegulatoryAllowChecker(homeRegion = usPhone).check(ctx("1600123456", allOn)))
            assertNull(RegulatoryPrefixChecker(homeRegion = usPhone).check(ctx("400123456", allOn)))
            assertNull(RegulatoryPrefixChecker(homeRegion = { null }).check(ctx("03031234567", allOn)))
            // E.164 needs no home region.
            assertNotNull(RegulatoryAllowChecker(homeRegion = usPhone).check(ctx("+911600123456", allOn)))
        }

    @Test
    fun `nothing runs until a range is turned on`() =
        runBlocking {
            val none = emptyPreferences()
            assertTrue(!RegulatoryPrefixChecker().isEnabled(ctx("+34400123456", none)))
            assertTrue(!RegulatoryAllowChecker().isEnabled(ctx("+911600123456", none)))
            assertTrue(
                RegulatoryPrefixChecker().isEnabled(ctx("+34400123456", preferencesOf(SpamRepository.KEY_REG_SPAIN_400 to true))),
            )
            // The allow's switch doesn't enable the block checker, and the reverse.
            assertTrue(
                !RegulatoryPrefixChecker().isEnabled(
                    ctx("+34400123456", preferencesOf(SpamRepository.KEY_REG_INDIA_1600_ALLOW to true)),
                ),
            )
        }

    @Test
    fun `a range only matches its own country`() =
        runBlocking {
            val allOn =
                preferencesOf(
                    SpamRepository.KEY_REG_SPAIN_400 to true,
                    SpamRepository.KEY_REG_INDIA_140 to true,
                    SpamRepository.KEY_REG_BRAZIL_0303 to true,
                )
            // Hyderabad (+91 40) and a Sao Paulo mobile (+55 11) share digits with the ranges.
            assertNull(RegulatoryPrefixChecker().check(ctx("+914012345678", allOn)))
            assertNull(RegulatoryPrefixChecker().check(ctx("+5511930312345", allOn)))
            assertNull(RegulatoryPrefixChecker().check(ctx("+14001234567", allOn)))
        }

    @Test
    fun `India 1600 allows when enabled`() =
        runBlocking {
            val result =
                RegulatoryAllowChecker().check(
                    ctx("+911600123456", preferencesOf(SpamRepository.KEY_REG_INDIA_1600_ALLOW to true)),
                )
            assertNotNull(result)
            assertEquals("regulatory_allow", result!!.matchSource)
            assertEquals(BlockReasonCode.REGULATORY_ALLOW, result.reasonCode)
            assertTrue(!result.shouldBlock)
            assertNotNull(
                RegulatoryAllowChecker(homeRegion = { "IN" }).check(
                    ctx("1600123456", preferencesOf(SpamRepository.KEY_REG_INDIA_1600_ALLOW to true)),
                ),
            )
        }

    @Test
    fun `India 1600 passes when disabled`() =
        runBlocking {
            assertNull(
                RegulatoryAllowChecker().check(
                    ctx("+911600123456", preferencesOf(SpamRepository.KEY_REG_INDIA_1600_ALLOW to false)),
                ),
            )
        }

    @Test
    fun `the allow never fires on a blocking range and the block never fires on the allow`() =
        runBlocking {
            val everything = preferencesOf(*RegulatoryPrefix.entries.map { it.key to true }.toTypedArray())
            assertNull(RegulatoryAllowChecker().check(ctx("+34400123456", everything)))
            assertNull(RegulatoryPrefixChecker().check(ctx("+911600123456", everything)))
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
    fun `the protected series outranks quiet hours and region rules but not explicit blocks`() {
        assertTrue(CheckerPriority.REGULATORY_ALLOW > CheckerPriority.TIME_BLOCK)
        assertTrue(CheckerPriority.REGULATORY_ALLOW > CheckerPriority.REGION_BLOCK)
        assertTrue(CheckerPriority.REGULATORY_ALLOW < CheckerPriority.USER_BLOCKLIST)
        assertTrue(CheckerPriority.REGULATORY_ALLOW < CheckerPriority.SYSTEM_BLOCK_LIST)
        assertTrue(CheckerPriority.REGULATORY_ALLOW < CheckerPriority.WILDCARD_RULE)
        assertTrue(CheckerPriority.REGULATORY_ALLOW < CheckerPriority.HASH_WILDCARD_RULE)
    }

    @Test
    fun `regulatory prefix sits below the downloaded prefix matcher`() {
        val prefix = RegulatoryPrefixChecker()
        assertTrue(prefix.priority < CheckerPriority.PREFIX_MATCH)
        assertTrue(prefix.priority > CheckerPriority.STIR_SHAKEN_TRUSTED)
    }
}
