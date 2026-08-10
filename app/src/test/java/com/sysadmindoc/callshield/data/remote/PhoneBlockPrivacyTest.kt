package com.sysadmindoc.callshield.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneBlockPrivacyTest {
    @Test
    fun `international input is preserved only for hashing`() {
        val international = phoneBlockInternationalNumber("+49 (30) 123456")

        assertEquals("+4930123456", international)
        val url = phoneBlockLookupUrl(requireNotNull(international)).toString()
        assertFalse(url.contains("4930123456"))
        assertTrue(url.contains("sha1="))
        assertTrue(url.contains("prefix10="))
        assertTrue(url.contains("prefix100="))
    }

    @Test
    fun `NANP input has an unambiguous international form`() {
        assertEquals("+12125551234", phoneBlockInternationalNumber("(212) 555-1234"))
        assertEquals("+12125551234", phoneBlockInternationalNumber("12125551234"))
    }

    @Test
    fun `other national formats are rejected instead of guessed`() {
        assertNull(phoneBlockInternationalNumber("030 123456"))
        assertNull(phoneBlockInternationalNumber("+123"))
        assertNull(phoneBlockInternationalNumber("+1234567890123456"))
    }

    @Test
    fun `SHA1 uses the uppercase PhoneBlock representation`() {
        assertEquals("A9993E364706816ABA3E25717850C26C9CD0D89D", phoneBlockSha1Hex("abc"))
        assertNotEquals(phoneBlockSha1Hex("+12125551234"), phoneBlockSha1Hex("+12125551235"))
    }

    @Test
    fun `PhoneBlock parser includes exact and range evidence`() {
        val result =
            parsePhoneBlockBody(
                """{"votes":1,"votesWildcard":3,"blackListed":false,"rating":"C_ADVERTISEMENT"}""",
            )

        assertTrue(result.isSpam)
        assertEquals(3, result.reports)
        assertTrue(result.detail.contains("range votes"))
        assertEquals(RemoteLookupStatus.FOUND, result.status)
    }

    @Test
    fun `PhoneBlock cache is bounded, LRU ordered, and expires entries`() {
        var now = 0L
        val cache = PhoneBlockLookupCache(maxEntries = 2, ttlMs = 100, clock = { now })
        val first = ExternalLookup.SourceResult("PhoneBlock", isSpam = false)
        val second = ExternalLookup.SourceResult("PhoneBlock", isSpam = true)
        val third = ExternalLookup.SourceResult("PhoneBlock", isSpam = true, reports = 3)

        cache.put("first", first)
        cache.put("second", second)
        assertSame(first, cache.get("first"))
        cache.put("third", third)
        assertNull(cache.get("second"))
        assertEquals(2, cache.sizeForTests())
        now = 100
        assertNull(cache.get("first"))
        assertNull(cache.get("third"))
    }
}
