package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.model.SmsKeywordRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Inputs and what the rules should read. Built from code points, so no invisible character sits in source. */
internal object SmsNormalizerCases {
    fun ch(vararg codePoints: Int): String = codePoints.joinToString("") { String(Character.toChars(it)) }

    val ZWSP = ch(0x200B)
    val ZWNJ = ch(0x200C)
    val ZWJ = ch(0x200D)
    val WORD_JOINER = ch(0x2060)
    val SOFT_HYPHEN = ch(0x00AD)
    val RIGHT_TO_LEFT_OVERRIDE = ch(0x202E)
    val POP_DIRECTIONAL = ch(0x202C)
    val TAG_A = ch(0xE0041)
    val NO_BREAK_SPACE = ch(0x00A0)
    val VARIATION_16 = ch(0xFE0F)

    data class Case(
        val name: String,
        val input: String,
        val folded: String,
        val disguised: Boolean,
    )

    val cases =
        listOf(
            Case("fullwidth letters", ch(0xFF26, 0xFF32, 0xFF25, 0xFF25) + " " + ch(0xFF47, 0xFF49, 0xFF46, 0xFF54), "FREE gift", false),
            Case("math bold letters", ch(0x1D405, 0x1D42B, 0x1D41E, 0x1D41E), "Free", false),
            Case("zero-width splits", "fr${ZWSP}ee g${WORD_JOINER}if${SOFT_HYPHEN}t", "free gift", false),
            Case("bidi override", "${RIGHT_TO_LEFT_OVERRIDE}claim$POP_DIRECTIONAL now", "claim now", false),
            Case("tag characters", "wi${TAG_A}n", "win", false),
            Case("no-break space", "free${NO_BREAK_SPACE}gift", "free gift", false),
            Case(
                "look-alikes in English",
                ch(0x0423, 0x043E) + "ur " + ch(0x0440, 0x0430, 0x0441) + "k" + ch(0x0430) + "g" + ch(0x0435) + " is held",
                "Your package is held",
                false,
            ),
            Case("brand with a hidden character", "Pay${ZWSP}Pal: verify now", "PayPal: verify now", true),
            Case("brand in look-alikes", ch(0x0410) + "mazon order", "Amazon order", true),
            Case("host with a hidden character", "go to usps$ZWSP-help.com", "go to usps-help.com", true),
            Case("look-alike host", "visit " + ch(0x0430) + "pple.com/id", "visit apple.com/id", true),
            Case("Russian stays Russian", "Ваш код подтверждения 4821", "Ваш код подтверждения 4821", false),
            Case("Greek stays Greek", "Καλημέρα σας", "Καλημέρα σας", false),
            Case("Persian joiner is not a disguise", "می${ZWNJ}خواهم", "میخواهم", false),
            Case(
                "emoji sequences are not a disguise",
                "Family " + ch(0x1F468) + ZWJ + ch(0x1F469) + " ok " + ch(0x2714) + VARIATION_16,
                "Family " + ch(0x1F468, 0x1F469) + " ok " + ch(0x2714),
                false,
            ),
            Case("plain text is unchanged", "Your code is 482913", "Your code is 482913", false),
        )

    fun assertAll() {
        cases.forEach { case ->
            val result = SmsTextNormalizer.normalize(case.input)
            assertEquals(case.name, case.folded, result.folded)
            assertEquals("${case.name} disguised", case.disguised, result.disguised)
        }
    }
}

class SmsTextNormalizerTest {
    @Test
    fun `every case folds as expected on the JVM`() {
        SmsNormalizerCases.assertAll()
    }

    @Test
    fun `hosts keep their own letters so a look-alike never reads as the real domain`() {
        val result = SmsTextNormalizer.normalize("visit " + ch(0x0430) + "pple.com/id")

        assertTrue(result.visible.contains(ch(0x0430)))
        assertEquals("visit apple.com/id", result.folded)
        assertFalse(
            SmsContentAnalyzer()
                .extractReportableIndicators("visit " + ch(0x0430) + "pple.com/id")
                .domains
                .contains("apple.com"),
        )
    }

    @Test
    fun `a host split by an invisible character is still found and reported whole`() {
        val indicators = SmsContentAnalyzer().extractReportableIndicators("Track it at bit${SmsNormalizerCases.ZWSP}.ly/x7Qp")

        assertEquals(listOf("bit.ly"), indicators.domains)
        assertTrue("shortener" in indicators.urlIndicators)
    }

    @Test
    fun `a disguised brand adds its own signal`() {
        val result = SmsContentAnalyzer().analyze("Your US${SmsNormalizerCases.ZWSP}PS parcel is waiting")

        assertTrue(result.reasons.toString(), "disguised_text" in result.reasons)
    }

    @Test
    fun `invisible padding doesn't stretch a short message with a link`() {
        val padding = SmsNormalizerCases.ZWSP.repeat(60)
        val result = SmsContentAnalyzer().analyze("Pay now$padding bit.ly/x7Qp")

        assertTrue(result.reasons.toString(), "short_msg_with_url" in result.reasons)
    }

    @Test
    fun `keyword rules see through fullwidth invisible and look-alike text`() {
        val rule = SmsKeywordRule(keyword = "gift card")

        assertTrue(rule.matches("Claim your g${SmsNormalizerCases.ZWSP}ift " + ch(0xFF43, 0xFF41, 0xFF52, 0xFF44)))
        assertTrue(rule.matches("Your " + ch(0x0261) + "ift c" + ch(0x0430) + "rd is ready"))
        assertFalse(rule.matches("Thanks for the birthday card"))
    }

    @Test
    fun `a Cyrillic keyword still matches a Russian message`() {
        assertTrue(SmsKeywordRule(keyword = "скидка").matches("Ваша скидка 20% уже ждёт"))
        assertTrue(SmsKeywordRule(keyword = "Скидка", caseSensitive = true).matches("Скидка только сегодня"))
    }

    @Test
    fun `a case-sensitive keyword stays case-sensitive after folding`() {
        val rule = SmsKeywordRule(keyword = "WIN", caseSensitive = true)

        assertTrue(rule.matches("W${SmsNormalizerCases.ZWSP}IN a cruise"))
        assertFalse(rule.matches("win a cruise"))
    }

    private fun ch(vararg codePoints: Int): String = SmsNormalizerCases.ch(*codePoints)
}
