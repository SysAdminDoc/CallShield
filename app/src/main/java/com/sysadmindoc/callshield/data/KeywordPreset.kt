package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.model.SmsKeywordRule

/**
 * Ready-made SMS keyword sets the Keywords tab offers. A preset goes in as
 * ordinary keyword rules the user can switch off or delete one at a time. Each
 * rule it adds carries [marker] as its description, which is how the tab finds
 * the set to remove it and labels its rules, so a phrase the user already had
 * stays theirs.
 */
enum class KeywordPreset(
    val marker: String,
    val phrases: List<String>,
) {
    /**
     * Campaign and PAC fundraising texts. Each phrase is a disclaimer, an
     * opt-out style or a donation platform those texts use, with no party or
     * candidate named. Election office notices (ballot tracking, polling places)
     * use none of them, and neither do the clean messages in the SMS corpus.
     * ActBlue and WinRed go together, so the set leans neither way.
     */
    POLITICAL(
        marker = "Political texts preset",
        phrases =
            listOf(
                "paid for by",
                "pd for by",
                "not authorized by any candidate",
                "political action committee",
                "fec deadline",
                "not tax deductible",
                "not tax-deductible",
                "stop2end",
                "stop2quit",
                "stop=end",
                "actblue",
                "winred",
            ),
    ),
    ;

    /** This preset's rules among [rules]: the ones it added, switched off or not. */
    fun rulesIn(rules: List<SmsKeywordRule>): List<SmsKeywordRule> = rules.filter { it.description == marker }

    /**
     * The phrases [existing] keywords don't cover yet, ignoring case and spacing.
     * Adding one the user already has would replace their rule (keywords are
     * unique) along with its description, switch and schedule.
     */
    fun missingFrom(existing: Collection<String>): List<String> {
        val have = existing.mapTo(HashSet()) { it.trim().lowercase() }
        return phrases.filterNot { it in have }
    }

    /**
     * Rules for [phrases], on and case-insensitive. They're returned in reverse
     * so the Keywords tab, newest first, lists them in the order written here.
     */
    fun newRules(phrases: List<String>): List<SmsKeywordRule> = phrases.asReversed().map { SmsKeywordRule(keyword = it, caseSensitive = false, description = marker) }
}
