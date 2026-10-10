package com.sysadmindoc.callshield.ui

import com.sysadmindoc.callshield.data.CommunityReportHistory
import com.sysadmindoc.callshield.data.CommunityReportHistory.Delivery
import com.sysadmindoc.callshield.data.model.NumberSighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pending pool's range clusters, seen from one phone: a campaign that
 * calls from a new number in one exchange each day (+1 737-259 had eight
 * numbers on eight days from 2026-09-30).
 */
class SlowCampaignExchangesTest {
    private val day = 24L * 60 * 60 * 1_000
    private val hour = 60L * 60 * 1_000
    private val now = 1_791_600_000_000L
    private val austin = ExchangeBlock("737", "259", "+1737259*")

    /** One new number from (737) 259 on each of the last [days] days. */
    private fun dailyCampaign(days: Int) = (1..days).map { NumberSighting("+1737259" + it.toString().padStart(4, '0'), now - it * day) }

    private fun find(
        sightings: List<NumberSighting>,
        reports: List<CommunityReportHistory.Entry> = emptyList(),
        ownNumber: String? = null,
        contactExchanges: Set<String> = emptySet(),
        blockedPatterns: Set<String> = emptySet(),
    ) = slowCampaignExchanges(sightings, reports, now, "US", ownNumber, contactExchanges, blockedPatterns)

    private fun report(
        number: String,
        at: Long,
        type: String = "spam",
    ) = CommunityReportHistory.Entry("r-$number-$at", number, type, at, Delivery.SENT)

    @Test
    fun `eight numbers on eight days from one exchange are offered as that exchange`() {
        val offered = find(dailyCampaign(8))

        assertEquals(listOf(austin to 8), offered)
        assertEquals("(737) 259-xxxx", offered.single().first.display)
    }

    @Test
    fun `three numbers inside a day are a burst the campaign heuristics already catch`() {
        val burst = (1..3).map { NumberSighting("+1737259000$it", now - it * hour) }

        assertEquals(emptyList<Pair<ExchangeBlock, Int>>(), find(burst))
    }

    @Test
    fun `an exchange holding a contact is never offered`() {
        assertEquals(emptyList<Pair<ExchangeBlock, Int>>(), find(dailyCampaign(8), contactExchanges = setOf("737259")))
        assertEquals(listOf(austin to 8), find(dailyCampaign(8), contactExchanges = setOf("737260")))
    }

    @Test
    fun `the phone's own exchange is never offered, however the SIM spells its number`() {
        assertEquals(emptyList<Pair<ExchangeBlock, Int>>(), find(dailyCampaign(8), ownNumber = "+17372595555"))
        assertEquals(emptyList<Pair<ExchangeBlock, Int>>(), find(dailyCampaign(8), ownNumber = "7372595555"))
        assertEquals(listOf(austin to 8), find(dailyCampaign(8), ownNumber = "+17372605555"))
    }

    @Test
    fun `two numbers, or numbers older than two weeks, are not enough`() {
        assertEquals(emptyList<Pair<ExchangeBlock, Int>>(), find(dailyCampaign(2)))
        val old = (13..16).map { NumberSighting("+1737259" + it.toString().padStart(4, '0'), now - it * day) }
        assertEquals(emptyList<Pair<ExchangeBlock, Int>>(), find(old))
    }

    @Test
    fun `one number counts once whether it is written with +1 or bare`() {
        val sightings =
            listOf(
                NumberSighting("+17372590001", now - 5 * day),
                NumberSighting("7372590001", now - 4 * day),
                NumberSighting("+17372590002", now - 3 * day),
            )

        assertEquals(emptyList<Pair<ExchangeBlock, Int>>(), find(sightings))
    }

    @Test
    fun `numbers reported from this phone count, unless the newest report took it back`() {
        val blocked = dailyCampaign(2)
        val reported = report("+17372590099", now - 6 * day)

        assertEquals(listOf(austin to 3), find(blocked, reports = listOf(reported)))
        val takenBack = report("+17372590099", now - 5 * day, CommunityReportHistory.NOT_SPAM)
        assertEquals(emptyList<Pair<ExchangeBlock, Int>>(), find(blocked, reports = listOf(takenBack, reported)))
        // Taking back a blocked number drops it as well.
        val clearedBlock = report("+17372590001", now - day / 2, CommunityReportHistory.NOT_SPAM)
        assertEquals(emptyList<Pair<ExchangeBlock, Int>>(), find(dailyCampaign(3), reports = listOf(clearedBlock)))
        // A UK mobile with the same last ten digits takes back nothing.
        val ukMobile = report("+447372590001", now - day / 2, CommunityReportHistory.NOT_SPAM)
        assertEquals(listOf(austin to 3), find(dailyCampaign(3), reports = listOf(ukMobile)))
    }

    @Test
    fun `an exchange or area code already blocked is not offered again`() {
        assertEquals(emptyList<Pair<ExchangeBlock, Int>>(), find(dailyCampaign(8), blockedPatterns = setOf("+1737259*")))
        assertEquals(emptyList<Pair<ExchangeBlock, Int>>(), find(dailyCampaign(8), blockedPatterns = setOf("+1737*")))
    }

    @Test
    fun `only North American numbers have an exchange`() {
        val paris = (1..5).map { NumberSighting("+3314523" + it.toString().padStart(4, '0'), now - it * day) }

        assertEquals(emptyList<Pair<ExchangeBlock, Int>>(), find(paris))
        assertNull(exchangeBlock("+33145230001", homeRegionIso = "FR"))
        assertNull(exchangeBlock("0145230001", homeRegionIso = "FR"))
        // An exchange never starts with 0 or 1.
        assertNull(exchangeBlock("+17370590001", homeRegionIso = "US"))
    }

    @Test
    fun `the dashboard offers nothing when it can't read the phone's own number or its contacts`() {
        var contactReads = 0

        fun suggest(
            ownNumber: String?,
            sightings: List<NumberSighting> = dailyCampaign(8),
            contacts: Set<String>? = emptySet(),
        ) = suggestedExchanges(sightings, emptyList(), now, "US", ownNumber, emptySet()) {
            contactReads++
            contacts
        }

        // Neighbor spoofing rotates through the phone's own exchange, so an
        // unknown own number can't rule it out.
        assertEquals(emptyList<Pair<ExchangeBlock, Int>>(), suggest(ownNumber = null))
        assertEquals(emptyList<Pair<ExchangeBlock, Int>>(), suggest(ownNumber = "+17372605555", contacts = null))
        assertEquals(1, contactReads)
        // Contacts are read only when something would be offered.
        assertEquals(emptyList<Pair<ExchangeBlock, Int>>(), suggest(ownNumber = "+17372605555", sightings = dailyCampaign(2)))
        assertEquals(1, contactReads)
        assertEquals(emptyList<Pair<ExchangeBlock, Int>>(), suggest(ownNumber = "+17372595555"))
        assertEquals(1, contactReads)
        assertEquals(emptyList<Pair<ExchangeBlock, Int>>(), suggest(ownNumber = "+17372605555", contacts = setOf("737259")))
        assertEquals(listOf(austin to 8), suggest(ownNumber = "+17372605555"))
        // A phone with a number outside North America shares no exchange with the campaign.
        assertEquals(listOf(austin to 8), suggest(ownNumber = "+447700900123"))
        assertEquals(4, contactReads)
    }

    @Test
    fun `the busiest exchange comes first`() {
        val sightings = dailyCampaign(3) + (1..5).map { NumberSighting("+1302927" + it.toString().padStart(4, '0'), now - it * day) }

        assertEquals(listOf("302927" to 5, "737259" to 3), find(sightings).map { exchangeKey(it.first) to it.second })
    }
}
