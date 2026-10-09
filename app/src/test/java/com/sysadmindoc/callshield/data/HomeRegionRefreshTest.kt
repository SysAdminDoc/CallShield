package com.sysadmindoc.callshield.data

import android.content.Context
import android.telephony.TelephonyManager
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A SIM still locked at boot reports no country, so the phone's region comes
 * from the locale until the SIM unlocks. The repository used to keep that
 * first reading for the life of the process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeRegionRefreshTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val telephony = shadowOf(context.getSystemService(TelephonyManager::class.java))

    @After
    fun tearDown() {
        SpamHeuristics.updateHotRanges(emptyList())
        telephony.setSimCountryIso("")
        PhoneIdentityCanonicalizer.resetCacheForTests()
    }

    @Test
    fun `a SIM that unlocks after startup sets the region without a restart`() {
        telephony.setSimCountryIso("")
        telephony.setNetworkCountryIso("")
        // A VoIP range plus a live campaign range clears the heuristic bar for a North American number.
        SpamHeuristics.updateHotRanges(listOf("202555"))

        IsolatedRepositoryFixture(context).use { fixture ->
            runBlocking { fixture.settingsStore.edit { it[SpamRepository.KEY_ML_SCORER] = false } }
            assertEquals("+12025551234", fixture.repository.normalizeNumber("2025551234"))
            assertEquals("heuristic", runBlocking { fixture.repository.isSpam("2025551234") }.matchSource)

            telephony.setSimCountryIso("gb")
            // Stands in for the region cache's minute running out.
            PhoneIdentityCanonicalizer.resetCacheForTests()

            assertEquals("+442079460123", fixture.repository.normalizeNumber("02079460123"))
            // Another number in the same range, so no earlier verdict can answer.
            assertFalse(runBlocking { fixture.repository.isSpam("2025551299") }.isSpam)
        }
    }
}
