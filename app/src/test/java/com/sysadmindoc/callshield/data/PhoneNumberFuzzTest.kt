package com.sysadmindoc.callshield.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Fuzz tests for phone number parsing across PhoneFormatter, SpamMLScorer,
 * SpamHeuristics, and CampaignDetector.
 *
 * Every test feeds an adversarial input through all public/private parsing
 * methods and asserts that NO exception is thrown — the code must degrade
 * gracefully regardless of input.
 */
class PhoneNumberFuzzTest {

    // ── Helper: exercise every parsing path for one input ──────────────

    private fun fuzzAllParsers(input: String) {
        // PhoneFormatter
        PhoneFormatter.format(input)
        PhoneFormatter.formatWithCountryCode(input)

        // SpamMLScorer — public API
        SpamMLScorer.score(input)

        // SpamMLScorer.extractFeatures via reflection
        try {
            val method = SpamMLScorer::class.java.getDeclaredMethod("extractFeatures", String::class.java)
            method.isAccessible = true
            method.invoke(SpamMLScorer, input)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            fail("extractFeatures threw ${e.targetException::class.simpleName} for input: ${input.take(80)}")
        }

        // SpamHeuristics — public methods that take a phone number
        SpamHeuristics.isTollFree(input)
        SpamHeuristics.isInternationalPremium(input)
        SpamHeuristics.isHighSpamVoipRange(input)
        SpamHeuristics.isInvalidFormat(input)
        SpamHeuristics.isHotCampaignRange(input)

        // SpamHeuristics.isWangiriCountryCode (public)
        SpamHeuristics.isWangiriCountryCode(input)

        // CampaignDetector — public API
        CampaignDetector.recordCall(input)
        CampaignDetector.isActiveCampaign(input)

        // CampaignDetector.extractNpaNxx via reflection
        try {
            val method = CampaignDetector::class.java.getDeclaredMethod("extractNpaNxx", String::class.java)
            method.isAccessible = true
            method.invoke(CampaignDetector, input)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            fail("extractNpaNxx threw ${e.targetException::class.simpleName} for input: ${input.take(80)}")
        }
    }

    // ── Null-like inputs ──────────────────────────────────────────────

    @Test
    fun `fuzz empty string`() = fuzzAllParsers("")

    @Test
    fun `fuzz single space`() = fuzzAllParsers(" ")

    @Test
    fun `fuzz multiple spaces`() = fuzzAllParsers("     ")

    @Test
    fun `fuzz tab character`() = fuzzAllParsers("\t")

    @Test
    fun `fuzz newline`() = fuzzAllParsers("\n")

    @Test
    fun `fuzz carriage return newline`() = fuzzAllParsers("\r\n")

    @Test
    fun `fuzz single digit`() = fuzzAllParsers("5")

    @Test
    fun `fuzz single letter`() = fuzzAllParsers("a")

    @Test
    fun `fuzz single plus sign`() = fuzzAllParsers("+")

    @Test
    fun `fuzz plus only no digits`() = fuzzAllParsers("+++")

    // ── Unicode adversarial ──────────────────────────────────────────

    @Test
    fun `fuzz emoji phone`() = fuzzAllParsers("\uD83D\uDCDE2125551234")

    @Test
    fun `fuzz emoji only`() = fuzzAllParsers("\uD83D\uDCDE\uD83D\uDE00\uD83D\uDC4D")

    @Test
    fun `fuzz Arabic-Indic digits`() = fuzzAllParsers("\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668\u0669\u0660")

    @Test
    fun `fuzz fullwidth digits`() = fuzzAllParsers("\uFF12\uFF11\uFF12\uFF15\uFF15\uFF15\uFF11\uFF12\uFF13\uFF14")

    @Test
    fun `fuzz zero-width joiner in number`() = fuzzAllParsers("212\u200D555\u200D1234")

    @Test
    fun `fuzz zero-width space in number`() = fuzzAllParsers("212\u200B555\u200B1234")

    @Test
    fun `fuzz right-to-left override`() = fuzzAllParsers("\u202E2125551234")

    @Test
    fun `fuzz combining diacritical marks`() = fuzzAllParsers("2\u0301125551234")

    // ── Overflow and length extremes ─────────────────────────────────

    @Test
    fun `fuzz 100-digit all nines`() = fuzzAllParsers("9".repeat(100))

    @Test
    fun `fuzz 1000-char digit string`() = fuzzAllParsers("1".repeat(1000))

    @Test
    fun `fuzz 10000-char mixed string`() = fuzzAllParsers("abc123".repeat(1667))

    @Test
    fun `fuzz 5000-char all zeros`() = fuzzAllParsers("0".repeat(5000))

    @Test
    fun `fuzz extremely long plus-prefixed`() = fuzzAllParsers("+" + "1".repeat(500))

    // ── Special characters and injection ─────────────────────────────

    @Test
    fun `fuzz SQL injection attempt`() = fuzzAllParsers("'; DROP TABLE calls;--")

    @Test
    fun `fuzz HTML script tag`() = fuzzAllParsers("<script>alert('xss')</script>")

    @Test
    fun `fuzz HTML img tag`() = fuzzAllParsers("<img src=x onerror=alert(1)>")

    @Test
    fun `fuzz backslashes`() = fuzzAllParsers("\\\\212\\\\555\\\\1234")

    @Test
    fun `fuzz null bytes`() = fuzzAllParsers("212\u00005551234")

    @Test
    fun `fuzz null byte only`() = fuzzAllParsers("\u0000")

    @Test
    fun `fuzz multiple null bytes`() = fuzzAllParsers("\u0000\u0000\u0000")

    @Test
    fun `fuzz path traversal attempt`() = fuzzAllParsers("../../etc/passwd")

    @Test
    fun `fuzz percent encoding`() = fuzzAllParsers("%2B12125551234")

    @Test
    fun `fuzz angle brackets and ampersand`() = fuzzAllParsers("<>&\"'")

    @Test
    fun `fuzz regex special chars`() = fuzzAllParsers(".*+?^" + "\${}()|[]" + "\\\\")

    // ── Malformed phone number patterns ──────────────────────────────

    @Test
    fun `fuzz double plus prefix`() = fuzzAllParsers("++12125551234")

    @Test
    fun `fuzz letters in number vanity`() = fuzzAllParsers("1-800-FLOWERS")

    @Test
    fun `fuzz negative number`() = fuzzAllParsers("-12345678901")

    @Test
    fun `fuzz decimal point in number`() = fuzzAllParsers("212.555.1234")

    @Test
    fun `fuzz leading zeros`() = fuzzAllParsers("0000000000")

    @Test
    fun `fuzz all same digit`() = fuzzAllParsers("1111111111")

    @Test
    fun `fuzz hash and star codes`() = fuzzAllParsers("*67+12125551234")

    @Test
    fun `fuzz USSD code`() = fuzzAllParsers("*#06#")

    @Test
    fun `fuzz tel URI scheme`() = fuzzAllParsers("tel:+12125551234")

    @Test
    fun `fuzz sip URI`() = fuzzAllParsers("sip:user@example.com")

    // ── Boundary lengths ─────────────────────────────────────────────

    @Test
    fun `fuzz exactly 7 digits`() = fuzzAllParsers("5551234")

    @Test
    fun `fuzz exactly 10 digits`() = fuzzAllParsers("2125551234")

    @Test
    fun `fuzz exactly 11 digits with leading 1`() = fuzzAllParsers("12125551234")

    @Test
    fun `fuzz exactly 11 digits without leading 1`() = fuzzAllParsers("22125551234")

    @Test
    fun `fuzz exactly 15 digits max E164`() = fuzzAllParsers("123456789012345")

    @Test
    fun `fuzz exactly 2 digits`() = fuzzAllParsers("42")

    @Test
    fun `fuzz exactly 3 digits`() = fuzzAllParsers("911")

    @Test
    fun `fuzz exactly 4 digits`() = fuzzAllParsers("1234")

    @Test
    fun `fuzz exactly 5 digits short code`() = fuzzAllParsers("12345")

    @Test
    fun `fuzz exactly 6 digits short code`() = fuzzAllParsers("123456")

    @Test
    fun `fuzz exactly 8 digits`() = fuzzAllParsers("12345678")

    @Test
    fun `fuzz exactly 9 digits`() = fuzzAllParsers("123456789")

    @Test
    fun `fuzz exactly 12 digits`() = fuzzAllParsers("123456789012")

    // ── International prefixed numbers ───────────────────────────────

    @Test
    fun `fuzz UK number +44`() = fuzzAllParsers("+442012345678")

    @Test
    fun `fuzz China number +86`() = fuzzAllParsers("+8613812345678")

    @Test
    fun `fuzz India number +91`() = fuzzAllParsers("+919876543210")

    @Test
    fun `fuzz Germany number +49`() = fuzzAllParsers("+4915112345678")

    @Test
    fun `fuzz Japan number +81`() = fuzzAllParsers("+819012345678")

    @Test
    fun `fuzz wangiri Sierra Leone +232`() = fuzzAllParsers("+23222123456")

    @Test
    fun `fuzz wangiri Belarus +375`() = fuzzAllParsers("+375291234567")

    @Test
    fun `fuzz wangiri Jamaica +1876`() = fuzzAllParsers("+18765551234")

    // ── Format variations ────────────────────────────────────────────

    @Test
    fun `fuzz parenthesized area code`() = fuzzAllParsers("(555) 123-4567")

    @Test
    fun `fuzz dot separated`() = fuzzAllParsers("555.123.4567")

    @Test
    fun `fuzz space separated`() = fuzzAllParsers("555 123 4567")

    @Test
    fun `fuzz full US format with country code`() = fuzzAllParsers("+1 (555) 123-4567")

    @Test
    fun `fuzz dash separated`() = fuzzAllParsers("555-123-4567")

    @Test
    fun `fuzz mixed delimiters`() = fuzzAllParsers("(555)-123.4567")

    @Test
    fun `fuzz leading and trailing whitespace`() = fuzzAllParsers("  +12125551234  ")

    @Test
    fun `fuzz number with extension`() = fuzzAllParsers("2125551234 ext. 5678")

    @Test
    fun `fuzz number with x extension`() = fuzzAllParsers("2125551234x5678")

    // ── Toll-free variants ───────────────────────────────────────────

    @Test
    fun `fuzz toll-free 800`() = fuzzAllParsers("8005551234")

    @Test
    fun `fuzz toll-free 888`() = fuzzAllParsers("8885551234")

    @Test
    fun `fuzz toll-free 877`() = fuzzAllParsers("8775551234")

    @Test
    fun `fuzz toll-free 833`() = fuzzAllParsers("8335551234")

    // ── Score return value sanity checks ─────────────────────────────

    @Test
    fun `score returns valid range for normal number`() {
        val score = SpamMLScorer.score("2125551234")
        assertTrue("Score $score should be in [-1.0, 1.0]", score in -1.0..1.0)
    }

    @Test
    fun `score returns negative one for too-short number`() {
        val score = SpamMLScorer.score("123")
        assertEquals(-1.0, score, 0.001)
    }

    @Test
    fun `score returns negative one for empty string`() {
        val score = SpamMLScorer.score("")
        assertEquals(-1.0, score, 0.001)
    }

    @Test
    fun `extractFeatures returns empty for non-10-digit input`() {
        val method = SpamMLScorer::class.java.getDeclaredMethod("extractFeatures", String::class.java)
        method.isAccessible = true
        val result = method.invoke(SpamMLScorer, "123") as DoubleArray
        assertEquals("Should return empty array for short input", 0, result.size)
    }

    @Test
    fun `extractFeatures returns 20 features for valid number`() {
        val method = SpamMLScorer::class.java.getDeclaredMethod("extractFeatures", String::class.java)
        method.isAccessible = true
        val result = method.invoke(SpamMLScorer, "2125551234") as DoubleArray
        assertEquals("Should return 20 features", 20, result.size)
    }

    @Test
    fun `extractNpaNxx returns null for short number`() {
        val method = CampaignDetector::class.java.getDeclaredMethod("extractNpaNxx", String::class.java)
        method.isAccessible = true
        val result = method.invoke(CampaignDetector, "12345")
        assertNull(result)
    }

    @Test
    fun `extractNpaNxx returns 6-char string for valid number`() {
        val method = CampaignDetector::class.java.getDeclaredMethod("extractNpaNxx", String::class.java)
        method.isAccessible = true
        val result = method.invoke(CampaignDetector, "2125551234") as String
        assertEquals(6, result.length)
        assertEquals("212555", result)
    }

    // ── Bulk parametric fuzz: random-ish patterns ────────────────────

    @Test
    fun `fuzz batch of adversarial strings`() {
        val inputs = listOf(
            "\t\t\t",
            "   \n   ",
            "+",
            "-",
            ".",
            ",,,,,",
            "()()()(",
            "//////////",
            "0",
            "00",
            "000",
            "0000000000",
            "99999999999",
            "+" + "0".repeat(20),
            "1" + "0".repeat(9),
            "abcdefghij",
            "ABCDEFGHIJ",
            "+-*/=@#\$%",
            "NaN",
            "Infinity",
            "-Infinity",
            "null",
            "undefined",
            "true",
            "false",
            "0x1234ABCD",
            "0b10101010",
            "1e10",
            "1.23e-4",
            "2147483647",     // Int.MAX_VALUE
            "2147483648",     // Int.MAX_VALUE + 1
            "-2147483648",    // Int.MIN_VALUE
            "9223372036854775807", // Long.MAX_VALUE
        )
        for (input in inputs) {
            try {
                fuzzAllParsers(input)
            } catch (e: Exception) {
                fail("Exception for input '${input.take(40)}': ${e::class.simpleName}: ${e.message}")
            }
        }
    }
}
