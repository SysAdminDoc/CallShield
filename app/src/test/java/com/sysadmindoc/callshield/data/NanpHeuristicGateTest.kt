package com.sysadmindoc.callshield.data

import android.content.Context
import android.telephony.TelephonyManager
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.checker.CampaignBurstChecker
import com.sysadmindoc.callshield.data.checker.CampaignRecorderChecker
import com.sysadmindoc.callshield.data.checker.CheckContext
import kotlinx.coroutines.runBlocking
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
        // Rapid fire is not a NANP-only rule: the same Berlin number three times in
        // an hour is still a repeat caller. This line used to assert false, which
        // pinned rapid fire as dead for every caller outside North America.
        assertTrue(heuristics.isRapidFire(history, berlin))
        // The suffix collision is what must not match: a +1 number sharing Berlin's
        // last ten digits is a different caller.
        assertFalse(heuristics.isRapidFire(history, "+13012340101"))

        for (number in listOf(singapore, newZealand)) {
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

    @Test
    fun `a bare ten-digit number is North American only on a North American phone`() {
        for (region in listOf("US", "CA", null)) {
            assertEquals("$region", NumberingPlan.NANP, checkContext("2025551234", region).numberingPlan)
            assertEquals("$region", NumberingPlan.NANP, checkContext("12025551234", region).numberingPlan)
        }
        for (region in listOf("AU", "GB")) {
            assertEquals(region, NumberingPlan.OTHER, checkContext("2025551234", region).numberingPlan)
            assertEquals(region, NumberingPlan.OTHER, checkContext("12025551234", region).numberingPlan)
            assertEquals("a short code stays unreadable", NumberingPlan.UNREADABLE, checkContext("72345", region).numberingPlan)
            assertEquals("a +1 number is North American anywhere", NumberingPlan.NANP, checkContext("+12025551234", region).numberingPlan)
        }
    }

    @Test
    fun `outside North America a bare number gets none of the NANP scores`() {
        setOwnNumber("2025559999")
        heuristics.updateHotRanges(listOf("202555"))
        val now = System.currentTimeMillis()
        // A VoIP range, a hot range and the phone's own exchange, all at once.
        val ranged = "2025551234"
        val tollFree = "8885551234"
        // The same line written the North American way, three times in the hour.
        val history = List(3) { "12025551234" to now - 1000L }

        for (region in listOf("US", "CA", null)) {
            assertEquals(
                "$region",
                listOf("voip_spam_range", "hot_campaign_range", "neighbor_spoof", "rapid_fire"),
                analyze(ranged, region, history).reasons,
            )
            assertEquals("$region", listOf("toll_free"), analyze(tollFree, region, emptyList()).reasons)
        }
        for (region in listOf("AU", "GB")) {
            assertEquals(region, 0, analyze(ranged, region, history).score)
            assertEquals(region, 0, analyze(tollFree, region, emptyList()).score)
            // The same digits again are still a repeat caller; rapid fire isn't a NANP rule.
            val repeats = List(3) { ranged to now - 1000L }
            assertEquals(region, listOf("rapid_fire"), analyze(ranged, region, repeats).reasons)
        }
    }

    @Test
    fun `campaign bursts group North American numbers only`() {
        assertTrue(burstAfterFiveCalls("202555", "US"))
        assertTrue(burstAfterFiveCalls("+1202555", "AU"))
        // Bare digits libphonenumber couldn't place, on a phone outside North America.
        assertFalse(burstAfterFiveCalls("202555", "AU"))
        assertFalse(burstAfterFiveCalls("202555", "GB"))
        // A ten-digit international number isn't an NPA-NXX: +65 6123 xxxx.
        assertFalse(burstAfterFiveCalls("+656123", "US"))
        // Neither side of a campaign takes the other plan's digits: bare
        // Australian calls don't build a +1 202-555 campaign, and a Singapore
        // caller doesn't join a +1 656-123 one.
        assertFalse(burstAfterFiveCalls("202555", "AU", probe = "+12025550006"))
        assertFalse(burstAfterFiveCalls("+1656123", "US", probe = "+6561230006"))
    }

    /** Five distinct callers from [prefix], then whether [probe] reads as part of a campaign. */
    private fun burstAfterFiveCalls(
        prefix: String,
        region: String?,
        probe: String = "${prefix}0006",
    ): Boolean {
        val detector = CampaignDetector()
        val recorder = CampaignRecorderChecker(detector)
        val burst = CampaignBurstChecker(detector)
        return runBlocking {
            (1..5).forEach { recorder.check(checkContext("${prefix}000$it", region)) }
            burst.check(checkContext(probe, region)) != null
        }
    }

    @Test
    fun `the phone's own number is read in its own region`() {
        // An Italian SIM stores its number without +39, and those ten digits
        // would otherwise share an exchange with a New York caller.
        setOwnNumber("3471234567")
        val newYork = "+13471234999"

        assertFalse("IT", "neighbor_spoof" in analyze(newYork, "IT", emptyList()).reasons)
        assertTrue("US", "neighbor_spoof" in analyze(newYork, "US", emptyList()).reasons)
    }

    private fun analyze(
        number: String,
        region: String?,
        history: List<Pair<String, Long>>,
    ) = heuristics.analyze(
        context,
        number,
        numberingPlan = checkContext(number, region).numberingPlan,
        recentBlockedNumbers = history,
        homeRegionIso = region,
    )

    private fun checkContext(
        number: String,
        region: String? = null,
    ) = CheckContext(appContext = context, number = number, realtimeCall = true, prefs = emptyPreferences(), homeRegionIso = region)

    private fun setOwnNumber(number: String) {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        Shadows.shadowOf(telephony).setLine1Number(number)
    }
}
