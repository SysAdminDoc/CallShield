package com.sysadmindoc.callshield.ui

import com.sysadmindoc.callshield.data.CommunityReportHistory
import com.sysadmindoc.callshield.data.RegionCallingCodes
import com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup
import com.sysadmindoc.callshield.data.areacodes.GeographicAreaCodes
import com.sysadmindoc.callshield.data.model.LogAggregate
import com.sysadmindoc.callshield.data.model.NumberSighting
import com.sysadmindoc.callshield.util.filterAsciiDigits
import com.sysadmindoc.callshield.util.filterAsciiDigitsLast

/** An area code CallShield can block in one go, and the wildcard that does it. */
data class AreaCodeBlock(
    val areaCode: String,
    val wildcard: String,
    /** The calling code the area code is under, "1" for North America. */
    val callingCode: String = NANP_CALLING_CODE,
) {
    /** How the screen names it: "415" in North America, "+33 1" anywhere else. */
    val label: String get() = if (callingCode == NANP_CALLING_CODE) areaCode else "+$callingCode $areaCode"
}

private const val NANP_CALLING_CODE = "1"

/**
 * The area code block for [number], written from the number's own calling
 * code, or null when CallShield can't tell where its area code ends. North
 * America gives every area code three digits after +1. Elsewhere the length
 * changes even inside a country (Berlin is +49 30, Brandenburg an der Havel
 * +49 3381), so an international number reads by GeographicAreaCodes, and a
 * number it has no place for (a mobile, or Spain, which has no area codes)
 * gets nothing rather than a block on the wrong numbers. A number without a
 * `+` reads by [homeRegionIso], as in AreaCodeLookup.getAreaCode, and only as
 * North American.
 */
fun areaCodeBlock(
    number: String,
    homeRegionIso: String?,
): AreaCodeBlock? {
    AreaCodeLookup.getAreaCode(number, homeRegionIso)?.let { areaCode -> return AreaCodeBlock(areaCode, "+1$areaCode*") }
    if (!number.trimStart().startsWith("+")) return null
    val digits = filterAsciiDigits(number)
    val callingCode = RegionCallingCodes.callingCodeOf(digits)?.takeIf { it != NANP_CALLING_CODE } ?: return null
    val areaCode = GeographicAreaCodes.areaCode(callingCode, digits.substring(callingCode.length)) ?: return null
    return AreaCodeBlock(areaCode, "+$callingCode$areaCode*", callingCode)
}

/**
 * The log's count for each North American area code it can block, most
 * first. Each aggregate is one number prefix keyed by one of its numbers
 * (SpamDao.observeLogAreaCodeCounts, which groups only +1 and ten-digit
 * numbers), so a number without a `+` counts only where it reads as North
 * American: on a phone from India, 98450 12345 is a mobile number, not
 * Raleigh's 984.
 */
fun countsByAreaCode(
    aggregates: List<LogAggregate>,
    homeRegionIso: String?,
): List<Pair<AreaCodeBlock, Int>> =
    aggregates
        .mapNotNull { aggregate ->
            areaCodeBlock(aggregate.key, homeRegionIso)
                ?.takeIf { it.callingCode == NANP_CALLING_CODE }
                ?.let { block -> block to aggregate.count }
        }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        .map { (block, counts) -> block to counts.sum() }
        .sortedWith(compareByDescending<Pair<AreaCodeBlock, Int>> { it.second }.thenBy { it.first.areaCode })

/** A North American exchange, the area code and the three digits after it, and the wildcard that blocks it. */
data class ExchangeBlock(
    val areaCode: String,
    val exchange: String,
    val wildcard: String,
) {
    /** How the suggestion names it, "(737) 259-xxxx". */
    val display: String get() = "($areaCode) $exchange-xxxx"
}

/** The exchange block for a North American [number], read as in [areaCodeBlock], or null. */
fun exchangeBlock(
    number: String,
    homeRegionIso: String?,
): ExchangeBlock? {
    val areaCode = AreaCodeLookup.getAreaCode(number, homeRegionIso) ?: return null
    val exchange = filterAsciiDigitsLast(number, 7).take(3)
    // A North American exchange never starts with 0 or 1.
    if (exchange.length != 3 || exchange[0] < '2') return null
    return ExchangeBlock(areaCode, exchange, "+1$areaCode$exchange*")
}

const val SLOW_CAMPAIGN_WINDOW_MS = 14L * 24 * 60 * 60 * 1_000
private const val SLOW_CAMPAIGN_MIN_NUMBERS = 3
private const val BURST_MS = 24L * 60 * 60 * 1_000

/**
 * Exchanges a slow campaign keeps calling from, with how many of their
 * numbers this phone blocked or reported, most first. A campaign that rotates
 * through one exchange a number a day stays under every other detector: the
 * campaign heuristics want several numbers within a day, and the community
 * can't confirm one reporter by design. So an exchange qualifies with three or
 * more distinct numbers first seen in the last 14 days, spread over more than
 * a day. Three inside 24 hours are a burst the campaign heuristics already
 * act on, and get no suggestion of their own.
 *
 * [sightings] are blocked log numbers (SpamDao.observeLogNanpSightingsSince).
 * [reports] adds the numbers this phone reported as spam, and a number whose
 * newest report is not spam counts for nothing. The phone's own exchange
 * ([ownNumber]) and any exchange holding a contact ([contactExchanges], as
 * [exchangeKey] gives them) are never offered, and neither is one a rule in
 * [blockedPatterns] already blocks, alone or with its whole area code. A null
 * [ownNumber] skips no exchange; [suggestedExchanges] is what the dashboard
 * calls.
 */
internal fun slowCampaignExchanges(
    sightings: List<NumberSighting>,
    reports: List<CommunityReportHistory.Entry>,
    now: Long,
    homeRegionIso: String?,
    ownNumber: String?,
    contactExchanges: Set<String>,
    blockedPatterns: Set<String> = emptySet(),
): List<Pair<ExchangeBlock, Int>> {
    // Only a North American number reads as its last ten digits: +44 7372 590001 isn't +1 737-259-0001.
    val newestReports =
        reports
            .filter { exchangeBlock(it.number, homeRegionIso) != null }
            .sortedByDescending { it.reportedAt }
            .distinctBy { filterAsciiDigitsLast(it.number, 10) }
    val cleared =
        newestReports
            .filter { it.type == CommunityReportHistory.NOT_SPAM }
            .mapTo(HashSet()) { filterAsciiDigitsLast(it.number, 10) }
    val reported =
        newestReports
            .filter { it.type != CommunityReportHistory.NOT_SPAM }
            .map { NumberSighting(it.number, it.reportedAt) }
    val ownExchange = ownNumber?.let { exchangeBlock(it, homeRegionIso) }
    val since = now - SLOW_CAMPAIGN_WINDOW_MS
    return (sightings + reported)
        .asSequence()
        .filter { it.firstSeen in since..now }
        .mapNotNull { sighting ->
            val digits = filterAsciiDigitsLast(sighting.number, 10)
            exchangeBlock(sighting.number, homeRegionIso)
                ?.takeIf { digits !in cleared }
                ?.let { block -> Triple(block, digits, sighting.firstSeen) }
        }.groupBy { it.first }
        .filterKeys { block ->
            block != ownExchange &&
                exchangeKey(block) !in contactExchanges &&
                block.wildcard !in blockedPatterns &&
                "+1${block.areaCode}*" !in blockedPatterns
        }
        .mapNotNull { (block, rows) ->
            val firstSeen = rows.groupBy { it.second }.values.map { sameNumber -> sameNumber.minOf { it.third } }
            val spread = firstSeen.max() - firstSeen.min()
            (block to firstSeen.size).takeIf { firstSeen.size >= SLOW_CAMPAIGN_MIN_NUMBERS && spread > BURST_MS }
        }.sortedWith(compareByDescending<Pair<ExchangeBlock, Int>> { it.second }.thenBy { exchangeKey(it.first) })
}

/**
 * The exchanges the dashboard offers: [slowCampaignExchanges], or nothing
 * when the phone's own number ([ownNumber], null when Android won't share it)
 * or its contacts ([readContactExchanges] returning null) can't be read.
 * Neighbor-spoofing campaigns rotate through the phone's own exchange, so
 * offering without knowing it could suggest blocking the phone's neighbors.
 * Contacts are read only when something would be offered.
 */
internal fun suggestedExchanges(
    sightings: List<NumberSighting>,
    reports: List<CommunityReportHistory.Entry>,
    now: Long,
    homeRegionIso: String?,
    ownNumber: String?,
    blockedPatterns: Set<String>,
    readContactExchanges: () -> Set<String>?,
): List<Pair<ExchangeBlock, Int>> {
    if (ownNumber == null) return emptyList()

    fun find(contacts: Set<String>) = slowCampaignExchanges(sightings, reports, now, homeRegionIso, ownNumber, contacts, blockedPatterns)
    if (find(emptySet()).isEmpty()) return emptyList()
    val contacts = readContactExchanges() ?: return emptyList()
    return find(contacts)
}

/** The six digits that name an exchange, "737259". */
fun exchangeKey(block: ExchangeBlock): String = block.areaCode + block.exchange
