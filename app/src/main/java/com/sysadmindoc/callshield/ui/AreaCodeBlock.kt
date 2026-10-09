package com.sysadmindoc.callshield.ui

import com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup
import com.sysadmindoc.callshield.data.model.LogAggregate

/** An area code CallShield can block in one go, and the wildcard that does it. */
data class AreaCodeBlock(
    val areaCode: String,
    val wildcard: String,
)

/**
 * The area code block for [number], written from the number's own calling
 * code, or null when CallShield can't tell where its area code ends. Only
 * North America gives every area code one length, three digits after +1.
 * Elsewhere the length changes even inside a country (Berlin is +49 30,
 * Brandenburg an der Havel +49 3381) and the app has no table of them, so
 * offering nothing beats blocking the wrong numbers. A number without a `+`
 * reads by [homeRegionIso], as in AreaCodeLookup.getAreaCode.
 */
fun areaCodeBlock(
    number: String,
    homeRegionIso: String?,
): AreaCodeBlock? = AreaCodeLookup.getAreaCode(number, homeRegionIso)?.let { areaCode -> AreaCodeBlock(areaCode, "+1$areaCode*") }

/**
 * The log's count for each area code it can block, most first. Each
 * aggregate is one number prefix keyed by one of its numbers
 * (SpamDao.observeLogAreaCodeCounts), so a number without a `+` counts only
 * where it reads as North American: on a phone from India, 98450 12345 is a
 * mobile number, not Raleigh's 984.
 */
fun countsByAreaCode(
    aggregates: List<LogAggregate>,
    homeRegionIso: String?,
): List<Pair<AreaCodeBlock, Int>> =
    aggregates
        .mapNotNull { aggregate -> areaCodeBlock(aggregate.key, homeRegionIso)?.let { block -> block to aggregate.count } }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        .map { (block, counts) -> block to counts.sum() }
        .sortedWith(compareByDescending<Pair<AreaCodeBlock, Int>> { it.second }.thenBy { it.first.areaCode })
