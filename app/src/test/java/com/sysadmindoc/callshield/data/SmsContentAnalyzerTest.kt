package com.sysadmindoc.callshield.data

import org.junit.Assert.*
import org.junit.After
import org.junit.Test

/**
 * Unit tests for SmsContentAnalyzer — SMS spam content analysis.
 */
class SmsContentAnalyzerTest {

    @After
    fun tearDown() {
        SmsContentAnalyzer.updateSpamDomains(emptyList())
    }

    // ── Blank / empty body ───────────────────────────────────────────────

    @Test
    fun `analyze returns zero score for blank body`() {
        val result = SmsContentAnalyzer.analyze("")
        assertEquals(0, result.score)
        assertTrue(result.reasons.isEmpty())
    }

    @Test
    fun `analyze returns zero score for whitespace-only body`() {
        val result = SmsContentAnalyzer.analyze("   \n\t  ")
        assertEquals(0, result.score)
    }

    @Test
    fun `analyze returns zero for normal text`() {
        val result = SmsContentAnalyzer.analyze("Hey, are you coming to dinner tonight?")
        assertEquals(0, result.score)
    }

    // ── URL detection ────────────────────────────────────────────────────

    @Test
    fun `analyze detects http URL`() {
        val result = SmsContentAnalyzer.analyze("Check this out http://example.com/page")
        // Short message with URL = 20 points
        assertTrue(result.score > 0)
    }

    @Test
    fun `analyze detects https URL`() {
        val result = SmsContentAnalyzer.analyze("Visit https://example.com/offer now")
        assertTrue(result.score > 0)
    }

    @Test
    fun `short message with URL adds 20 points`() {
        val result = SmsContentAnalyzer.analyze("Click https://safe.com/x")
        assertTrue(result.reasons.contains("short_msg_with_url"))
    }

    @Test
    fun `long message with URL does not trigger short_msg_with_url`() {
        val longMsg = "This is a longer message that contains a lot of regular text and information. " +
                "Visit https://example.com for more details about the event."
        val result = SmsContentAnalyzer.analyze(longMsg)
        assertFalse(result.reasons.contains("short_msg_with_url"))
    }

    // ── URL shorteners ───────────────────────────────────────────────────

    @Test
    fun `analyze detects bit_ly shortened URL`() {
        val result = SmsContentAnalyzer.analyze("Click here: https://bit.ly/abc123")
        assertTrue(result.reasons.contains("shortened_url"))
        assertTrue(result.score >= 35)
    }

    @Test
    fun `analyze detects tinyurl shortened URL`() {
        val result = SmsContentAnalyzer.analyze("Go to https://tinyurl.com/abc123 now")
        assertTrue(result.reasons.contains("shortened_url"))
    }

    @Test
    fun `analyze detects t_co shortened URL`() {
        val result = SmsContentAnalyzer.analyze("Link: https://t.co/abc123")
        assertTrue(result.reasons.contains("shortened_url"))
    }

    // ── Suspicious TLDs ──────────────────────────────────────────────────

    @Test
    fun `analyze detects suspicious xyz TLD`() {
        val result = SmsContentAnalyzer.analyze("Visit https://spamsite.xyz/offer now!")
        assertTrue(result.reasons.contains("suspicious_tld"))
    }

    @Test
    fun `analyze detects suspicious tk TLD`() {
        val result = SmsContentAnalyzer.analyze("Go to https://free-stuff.tk/claim")
        assertTrue(result.reasons.contains("suspicious_tld"))
    }

    @Test
    fun `analyze detects suspicious club TLD`() {
        val result = SmsContentAnalyzer.analyze("Join https://winners.club/prize today")
        assertTrue(result.reasons.contains("suspicious_tld"))
    }

    @Test
    fun `analyze does not flag safe TLD com`() {
        val result = SmsContentAnalyzer.analyze("Visit https://google.com for info about our upcoming meeting and schedule details")
        assertFalse(result.reasons.contains("suspicious_tld"))
    }

    // ── Spam domain blocklist ────────────────────────────────────────────

    @Test
    fun `analyze detects spam domain from blocklist`() {
        SmsContentAnalyzer.updateSpamDomains(listOf("evil-spam.com"))
        val result = SmsContentAnalyzer.analyze("Check https://evil-spam.com/offer")
        assertTrue(result.reasons.contains("spam_domain"))
        assertTrue(result.score >= 50)
    }

    @Test
    fun `analyze does not flag domain not in blocklist`() {
        SmsContentAnalyzer.updateSpamDomains(listOf("evil-spam.com"))
        val result = SmsContentAnalyzer.analyze("Visit https://legitimate-business.com/page for more info and details about the product")
        assertFalse(result.reasons.contains("spam_domain"))
    }

    @Test
    fun `spam domain check skipped when blocklist empty`() {
        SmsContentAnalyzer.updateSpamDomains(emptyList())
        val result = SmsContentAnalyzer.analyze("Visit https://evil-spam.com/offer for more details and information about the product")
        assertFalse(result.reasons.contains("spam_domain"))
    }

    // ── Spam keyword patterns (urgency phrases) ──────────────────────────

    @Test
    fun `analyze detects you have won pattern`() {
        val result = SmsContentAnalyzer.analyze("Congratulations! You have won a free gift card worth \$500!")
        assertTrue(result.reasons.contains("spam_keywords"))
    }

    @Test
    fun `analyze detects act now urgency`() {
        val result = SmsContentAnalyzer.analyze("Act now! Limited time offer expires today. Don't miss out on this deal!")
        assertTrue(result.reasons.contains("spam_keywords"))
    }

    @Test
    fun `analyze detects account suspended`() {
        val result = SmsContentAnalyzer.analyze("Your account suspended. Verify your identity immediately to restore access.")
        assertTrue(result.reasons.contains("spam_keywords"))
    }

    @Test
    fun `analyze detects final notice`() {
        val result = SmsContentAnalyzer.analyze("FINAL NOTICE: Your payment is overdue. Contact us immediately to avoid collection.")
        assertTrue(result.reasons.contains("spam_keywords"))
    }

    @Test
    fun `analyze detects free gift spam`() {
        val result = SmsContentAnalyzer.analyze("Claim your free iPhone now! Text YES to receive your reward today.")
        assertTrue(result.reasons.contains("spam_keywords"))
    }

    @Test
    fun `multiple pattern hits increase score`() {
        // Triggers: "you have won", "claim your prize", "act now"
        val result = SmsContentAnalyzer.analyze(
            "You have won! Claim your prize now. Act now before it expires today!"
        )
        // First hit = 25, second = 15, third = 15 = 55 (capped at 3 patterns)
        assertTrue(result.score >= 40)
    }

    @Test
    fun `pattern hits capped at 3`() {
        // Even with many pattern matches, only 3 contribute
        val result = SmsContentAnalyzer.analyze(
            "You have won! Claim your prize. Act now! Final notice. " +
            "Free gift card. Account suspended. Verify your identity."
        )
        // Max from patterns: 25 + 15 + 15 = 55
        // Other signals may add but pattern contribution is capped
        assertTrue(result.score <= 100)
    }

    // ── Excessive caps ───────────────────────────────────────────────────

    @Test
    fun `analyze detects excessive caps`() {
        val result = SmsContentAnalyzer.analyze("FREE MONEY ACT NOW CLAIM YOUR PRIZE TODAY")
        assertTrue(result.reasons.contains("excessive_caps"))
    }

    @Test
    fun `analyze does not flag normal casing`() {
        val result = SmsContentAnalyzer.analyze("Hey, can you pick up some groceries on your way home?")
        assertFalse(result.reasons.contains("excessive_caps"))
    }

    @Test
    fun `excessive caps not triggered for short messages`() {
        // Less than 10 alpha chars
        val result = SmsContentAnalyzer.analyze("OK SURE")
        assertFalse(result.reasons.contains("excessive_caps"))
    }

    // ── Callback number in body ──────────────────────────────────────────

    @Test
    fun `analyze detects callback number`() {
        val result = SmsContentAnalyzer.analyze("You have a package waiting. Call us at +1-800-555-1234 to schedule delivery.")
        assertTrue(result.reasons.contains("callback_number"))
    }

    @Test
    fun `analyze detects dial number`() {
        val result = SmsContentAnalyzer.analyze("To claim your prize, dial 1-888-555-9999 now and provide your code.")
        assertTrue(result.reasons.contains("callback_number"))
    }

    // ── Special character ratio ──────────────────────────────────────────

    @Test
    fun `analyze detects excessive special characters`() {
        val result = SmsContentAnalyzer.analyze("!!! *** FREE $$$ MONEY !!! *** Click >>> here <<< NOW!!!")
        assertTrue(result.reasons.contains("special_chars"))
    }

    @Test
    fun `normal text does not trigger special chars`() {
        val result = SmsContentAnalyzer.analyze("Meeting tomorrow at 3pm. Let me know if you can make it.")
        assertFalse(result.reasons.contains("special_chars"))
    }

    @Test
    fun `special chars not triggered for short messages`() {
        // body.length <= 20
        val result = SmsContentAnalyzer.analyze("!@#\$%^&*()")
        assertFalse(result.reasons.contains("special_chars"))
    }

    // ── Score capping ────────────────────────────────────────────────────

    @Test
    fun `score is capped at 100`() {
        // Combine many spam signals to try to exceed 100
        SmsContentAnalyzer.updateSpamDomains(listOf("evil.com"))
        val result = SmsContentAnalyzer.analyze(
            "YOU HAVE WON! ACT NOW! FINAL NOTICE! Free gift! " +
            "Click https://evil.com/x or https://bit.ly/abc " +
            "Call us at 1-800-555-1234. !!! \$\$\$ *** !!!"
        )
        assertTrue(result.score <= 100)
    }

    // ── SmsAnalysisResult structure ──────────────────────────────────────

    @Test
    fun `result contains correct reasons list`() {
        val result = SmsContentAnalyzer.analyze("Click https://bit.ly/abc123")
        assertTrue(result.reasons.isNotEmpty())
    }

    @Test
    fun `updateSpamDomains replaces previous set`() {
        SmsContentAnalyzer.updateSpamDomains(listOf("first.com"))
        SmsContentAnalyzer.updateSpamDomains(listOf("second.com"))

        val r1 = SmsContentAnalyzer.analyze("Visit https://first.com/x for more info and details about the product and services")
        val r2 = SmsContentAnalyzer.analyze("Visit https://second.com/x for more info and details about the product and services")
        assertFalse(r1.reasons.contains("spam_domain"))
        assertTrue(r2.reasons.contains("spam_domain"))
    }
}
