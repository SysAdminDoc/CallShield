package com.sysadmindoc.callshield.data

import android.content.Context
import android.telephony.TelephonyManager
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.checker.CheckContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class NanpHeuristicGateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val heuristics = SpamHeuristics()

    @After
    fun resetHotRanges() = heuristics.updateHotRanges(emptyList())

    @Test
    fun `context classifies international national and unreadable numbers once`() {
        for (number in listOf("+493012340101", "+658005551234", "+642025551234")) {
            assertEquals(NumberingPlan.OTHER, checkContext(number).numberingPlan)
        }
        assertEquals(NumberingPlan.NANP, checkContext("+13012340101").numberingPlan)
        assertEquals(NumberingPlan.NANP, checkContext("(301) 234-0101").numberingPlan)
        assertEquals(NumberingPlan.UNREADABLE, checkContext("++13012340101").numberingPlan)
        assertEquals(NumberingPlan.UNREADABLE, checkContext("+1301234").numberingPlan)
    }

    @Test
    fun `all five NANP rules ignore international suffix collisions`() {
        setOwnNumber("3012349999")
        heuristics.updateHotRanges(listOf("301234"))
        val now = System.currentTimeMillis()
        val berlin = "+493012340101"
        val singapore = "+658005551234"
        val newZealand = "+642025551234"
        val history = List(3) { berlin to now - 1000L }

        assertFalse(heuristics.isNeighborSpoof(context, berlin))
        assertFalse(heuristics.isHotCampaignRange(berlin))
        assertFalse(heuristics.isTollFree(singapore))
        assertFalse(heuristics.isHighSpamVoipRange(newZealand))
        assertFalse(heuristics.isRapidFire(history, berlin))
        assertFalse(heuristics.isRapidFire(history, "+13012340101"))

        for (number in listOf(berlin, singapore, newZealand)) {
            val result =
                heuristics.analyze(
                    context,
                    number,
                    numberingPlan = checkContext(number).numberingPlan,
                    recentBlockedNumbers = history,
                )
            assertEquals(number, 0, result.score)
            assertTrue(number, result.reasons.isEmpty())
        }
    }

    @Test
    fun `NANP fixture retains neighbor hot range and rapid fire signals`() {
        setOwnNumber("3012349999")
        heuristics.updateHotRanges(listOf("301234"))
        val number = "+13012340101"
        val now = System.currentTimeMillis()
        val history = List(3) { number to now - 1000L }
        val result =
            heuristics.analyze(
                context,
                number,
                numberingPlan = checkContext(number).numberingPlan,
                recentBlockedNumbers = history,
            )

        assertEquals(100, result.score)
        assertEquals(listOf("hot_campaign_range", "neighbor_spoof", "rapid_fire"), result.reasons)
        assertTrue(heuristics.isTollFree("+18005551234"))
        assertTrue(heuristics.isHighSpamVoipRange("+12025551234"))
    }

    private fun checkContext(number: String) = CheckContext(appContext = context, number = number, realtimeCall = true, prefs = emptyPreferences())

    private fun setOwnNumber(number: String) {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        Shadows.shadowOf(telephony).setLine1Number(number)
    }
}
