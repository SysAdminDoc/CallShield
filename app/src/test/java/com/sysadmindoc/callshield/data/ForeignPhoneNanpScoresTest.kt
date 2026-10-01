package com.sysadmindoc.callshield.data

import android.content.Context
import android.telephony.TelephonyManager
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.domain.model.SpamCheckResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The screener reads a number libphonenumber couldn't place by the phone's own region. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForeignPhoneNanpScoresTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        SpamHeuristics.updateHotRanges(emptyList())
        PhoneIdentityCanonicalizer.resetCacheForTests()
    }

    @Test
    fun `North American ranges score a bare number only on a North American phone`() {
        // A VoIP range plus a live campaign range clears the heuristic bar.
        SpamHeuristics.updateHotRanges(listOf("202555"))
        val ranged = "2025551234"

        assertEquals("heuristic", screen(ranged, sim = "us", ml = false).matchSource)
        assertFalse(screen(ranged, sim = "au", ml = false).isSpam)
        assertFalse(screen(ranged, sim = "gb", ml = false).isSpam)
    }

    @Test
    fun `the model scores a bare number only on a North American phone`() {
        val canary = SpamMLScorer.ML_SPAM_CANARY.removePrefix("+1")
        // The model reads the hour; the canary is spam at every one, so the clock doesn't matter.
        assertTrue((0..23).all { SpamMLScorer.isSpamAtHour(SpamMLScorer.ML_SPAM_CANARY, it) })

        assertEquals("ml_scorer", screen(canary, sim = "us").matchSource)
        assertFalse(screen(canary, sim = "au").isSpam)
        assertFalse(screen(canary, sim = "gb").isSpam)
    }

    private fun screen(
        number: String,
        sim: String,
        ml: Boolean = true,
    ): SpamCheckResult {
        shadowOf(context.getSystemService(TelephonyManager::class.java)).setSimCountryIso(sim)
        PhoneIdentityCanonicalizer.resetCacheForTests()
        return IsolatedRepositoryFixture(context).use { fixture ->
            runBlocking {
                fixture.settingsStore.edit { it[SpamRepository.KEY_ML_SCORER] = ml }
                fixture.repository.isSpam(number)
            }
        }
    }
}
