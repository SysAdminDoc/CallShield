package com.sysadmindoc.callshield.service

import com.sysadmindoc.callshield.data.model.HotNumber
import org.junit.Assert.assertEquals
import org.junit.Test

class HotDataSyncTest {
    @Test
    fun sanitizeHotNumbers_normalizesAndDeduplicatesEntries() {
        val sanitized =
            HotDataSync.sanitizeHotNumbers(
                hotNumbers =
                    listOf(
                        HotNumber(number = " (212) 555-1234 ", type = "robocall", description = " First "),
                        HotNumber(number = "+1 212 555 1234", type = "", description = ""),
                        HotNumber(number = "   ", type = "sms_spam", description = "Ignored"),
                    ),
                normalizeNumber = { raw ->
                    raw.trim().filter { it.isDigit() }.let { digits ->
                        when {
                            digits.isBlank() -> ""
                            digits.length == 11 && digits.startsWith("1") -> "+$digits"
                            digits.length == 10 -> "+1$digits"
                            else -> digits
                        }
                    }
                },
            )

        assertEquals(1, sanitized.size)
        assertEquals("+12125551234", sanitized.first().number)
        assertEquals("robocall", sanitized.first().type)
        assertEquals("First", sanitized.first().description)
    }

    @Test
    fun sanitizeHotRanges_keepsOnlySixDigitPrefixes() {
        val sanitized =
            HotDataSync.sanitizeHotRanges(
                listOf("212555", " 212555 ", "abc", "12345", "1234567", "310555"),
            )

        assertEquals(listOf("212555", "310555"), sanitized)
    }

    @Test
    fun bundledFallback_onlyBootstrapsAnEmptyStore() {
        // A successful fetch never needs the bundled snapshot.
        assertEquals(
            false,
            HotDataSync.shouldUseBundledFallback(remoteSucceeded = true, hasExistingData = false),
        )
        assertEquals(
            false,
            HotDataSync.shouldUseBundledFallback(remoteSucceeded = true, hasExistingData = true),
        )
        // Transient fetch failure with data on device: keep what we have.
        // Resolving the bundled snapshot here would delete the fresher rows.
        assertEquals(
            false,
            HotDataSync.shouldUseBundledFallback(remoteSucceeded = false, hasExistingData = true),
        )
        // Fetch failure on a device with nothing yet: bootstrap is correct.
        assertEquals(
            true,
            HotDataSync.shouldUseBundledFallback(remoteSucceeded = false, hasExistingData = false),
        )
    }

    @Test
    fun sanitizeSpamDomains_normalizesSchemesAndDuplicates() {
        val sanitized =
            HotDataSync.sanitizeSpamDomains(
                listOf("HTTPS://WWW.Evil.com/", "evil.com", " www.second.net/path/ ", " "),
            )

        assertEquals(listOf("evil.com", "second.net"), sanitized)
    }
}
