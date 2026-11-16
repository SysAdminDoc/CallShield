package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.model.SmsKeywordRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordPresetsTest {
    private val political = KeywordPreset.POLITICAL
    private val rules = political.newRules(political.phrases)

    private fun matched(text: String) = rules.any { it.matches(text) }

    @Test
    fun `campaign and PAC fundraising texts match the political preset`() {
        assertEquals(emptyList<String>(), POLITICAL_SAMPLES.filterNot(::matched))
    }

    @Test
    fun `election office notices and everyday texts don't match the political preset`() {
        assertEquals(emptyList<String>(), CIVIC_AND_EVERYDAY.filter(::matched))
    }

    @Test
    fun `phrases the list already has are skipped whatever their case or spacing`() {
        val missing = political.missingFrom(listOf("  ActBlue ", "PAID FOR BY", "bank alert"))

        assertEquals(political.phrases.size - 2, missing.size)
        assertFalse("actblue" in missing)
        assertFalse("paid for by" in missing)
        assertEquals(emptyList<String>(), political.missingFrom(political.phrases.map { it.uppercase() }))
    }

    @Test
    fun `preset rules are on, ignore case, run all day and carry the preset marker`() {
        assertEquals(political.phrases.toSet(), rules.map { it.keyword }.toSet())
        assertTrue(rules.all { it.enabled && !it.caseSensitive && it.description == political.marker })
        assertTrue(rules.none { it.schedule.isGating })
    }

    @Test
    fun `the list shows the preset newest first in the order it was written`() {
        // The Keywords tab lists rules by id, newest first, so the last one inserted shows on top.
        assertEquals(political.phrases, rules.asReversed().map { it.keyword })
    }

    @Test
    fun `only rules the preset added count as the preset, switched off or not`() {
        val own = SmsKeywordRule(id = 1, keyword = "winred", description = "")
        val ownWithNote = SmsKeywordRule(id = 2, keyword = "paid for by", description = "campaign spam")
        val fromPreset = SmsKeywordRule(id = 3, keyword = "actblue", description = political.marker, enabled = false)

        assertEquals(listOf(fromPreset), political.rulesIn(listOf(own, ownWithNote, fromPreset)))
    }

    @Test
    fun `phrases are trimmed, lowercase and distinct`() {
        assertTrue(political.phrases.all { it == it.trim().lowercase() && it.isNotBlank() })
        assertEquals(political.phrases.size, political.phrases.toSet().size)
    }

    private companion object {
        val POLITICAL_SAMPLES =
            listOf(
                "Sam, our FEC deadline is MIDNIGHT and we're still short. Can you give \$5 before it hits? " +
                    "https://secure.actblue.com/donate/eoq Stop2End",
                "BREAKING: the polls just tightened. Rush a \$10 gift before the end-of-quarter deadline: " +
                    "https://secure.winred.com/give Txt STOP2QUIT",
                "Take the 2026 Voter Priorities Survey and tell lawmakers what matters to you. " +
                    "Paid for by Citizens for Safer Roads. Not authorized by any candidate or candidate's committee.",
                "Pd for by Lee for Congress. Your 5X match expires tonight! Give now: https://leeforcongress.com/x STOP=END",
                "This message is from the Clean Water Political Action Committee. Election Day is close and we need 200 more donors.",
                "Hi it's Alex with the Taylor campaign. Will we have your support in November? " +
                    "Contributions to Taylor for Senate are not tax-deductible. Reply STOP to opt out",
                "URGENT!! FEC DEADLINE TONIGHT. GIVE \$25: HTTPS://SECURE.WINRED.COM/X",
                "Match ends at 11:59 PM. Contributions are not tax deductible.",
            )

        val CIVIC_AND_EVERYDAY =
            listOf(
                "Your mail ballot for the November 3 general election was received and accepted by the county elections office.",
                "Reminder from the county clerk: polls are open 7 AM to 8 PM on Tuesday. Find your polling place at vote.gov",
                "Hey, the PTA fundraiser ends Friday. Can you send \$10 for the field trip?",
                "Your order has shipped. Track it at https://ups.com/track. Reply STOP to end texts",
                "Thanks for giving to the food bank! Your gift is tax deductible. Receipt: https://foodbank.org/r",
                "Your quarterly estimated tax payment is due January 15. This reminder is from your tax preparer.",
                "Jury duty reminder: report to the county courthouse Monday at 8 AM.",
            )
    }
}
