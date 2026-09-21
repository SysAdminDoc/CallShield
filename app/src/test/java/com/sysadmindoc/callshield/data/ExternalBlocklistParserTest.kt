package com.sysadmindoc.callshield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalBlocklistParserTest {
    @Test
    fun parseTextFeed_capsSchemesAndDeduplicatesNumbers() {
        val parsed =
            ExternalBlocklistParser.parse(
                rawUrl = "https://lists.example.test/block.txt#ignored",
                rawLabel = "Community list",
                body =
                    """
                    # comment
                    (212) 555-0101
                    +1 212 555 0101
                    not a phone number
                    508-555-0102
                    """.trimIndent(),
                normalizeNumber = ::canonicalizeUs,
            )

        assertEquals("https://lists.example.test/block.txt", parsed.url)
        assertEquals("txt", parsed.format)
        assertEquals("subscription:c09effef4063e625", parsed.source)
        assertEquals(listOf("+12125550101", "+15085550102"), parsed.numbers.map { it.number })
        assertEquals(2, parsed.skippedRows)
    }

    @Test
    fun parseCsvFeedUsesNumberTypeAndDescriptionColumns() {
        val parsed =
            ExternalBlocklistParser.parse(
                rawUrl = "https://lists.example.test/block.csv",
                rawLabel = "",
                body =
                    """
                    phone,type,description
                    "212,555,0101",robocall,"Known campaign"
                    508-555-0102,scam,
                    """.trimIndent(),
                normalizeNumber = ::canonicalizeUs,
            )

        assertEquals("csv", parsed.format)
        assertEquals("lists.example.test", parsed.label)
        assertEquals("robocall", parsed.numbers.first().type)
        assertEquals("Known campaign", parsed.numbers.first().description)
        assertEquals("lists.example.test", parsed.numbers.last().description)
    }

    @Test
    fun parseJsonFeedAcceptsEnvelopeObjectsAndStrings() {
        val parsed =
            ExternalBlocklistParser.parse(
                rawUrl = "https://lists.example.test/block.json",
                rawLabel = "JSON Feed",
                body =
                    """
                    {
                      "numbers": [
                        {"number": "+1 212 555 0101", "category": "fraud", "comment": "IRS spoof"},
                        "508-555-0102"
                      ]
                    }
                    """.trimIndent(),
                normalizeNumber = ::canonicalizeUs,
            )

        assertEquals("json", parsed.format)
        assertEquals(listOf("+12125550101", "+15085550102"), parsed.numbers.map { it.number })
        assertEquals("fraud", parsed.numbers.first().type)
        assertEquals("IRS spoof", parsed.numbers.first().description)
    }

    @Test
    fun parseRejectsUnsupportedUrlsAndOversizedBodies() {
        val badScheme =
            runCatching {
                ExternalBlocklistParser.parse(
                    rawUrl = "file:///sdcard/block.txt",
                    rawLabel = "",
                    body = "2125550101",
                    normalizeNumber = ::canonicalizeUs,
                )
            }.exceptionOrNull()
        assertTrue(badScheme is ExternalBlocklistValidationException)
        assertEquals(
            ExternalBlocklistFailureReason.UNSUPPORTED_URL,
            (badScheme as ExternalBlocklistValidationException).reason,
        )

        val cleartext =
            runCatching { ExternalBlocklistParser.validateHttpUrl("http://lists.example.test/block.txt") }
                .exceptionOrNull()
        assertTrue(cleartext is ExternalBlocklistValidationException)
        assertEquals(
            ExternalBlocklistFailureReason.UNSUPPORTED_URL,
            (cleartext as ExternalBlocklistValidationException).reason,
        )
        assertTrue(cleartext.message.orEmpty().contains("HTTPS"))

        val oversized =
            runCatching {
                ExternalBlocklistParser.parse(
                    rawUrl = "https://lists.example.test/block.txt",
                    rawLabel = "",
                    body = "1".repeat(ExternalBlocklistParser.MAX_SUBSCRIPTION_BYTES.toInt() + 1),
                    normalizeNumber = ::canonicalizeUs,
                )
            }.exceptionOrNull()
        assertTrue(oversized is ExternalBlocklistValidationException)
        assertEquals(
            ExternalBlocklistFailureReason.OVERSIZE,
            (oversized as ExternalBlocklistValidationException).reason,
        )
    }

    @Test
    fun parseRejectsRowsPastTheCapBeforeNormalizing() {
        val body =
            buildString {
                repeat(ExternalBlocklistParser.MAX_SUBSCRIPTION_ROWS + 1) {
                    appendLine("2125550101")
                }
            }

        val error =
            runCatching {
                ExternalBlocklistParser.parse(
                    rawUrl = "https://lists.example.test/block.txt",
                    rawLabel = "",
                    body = body,
                    normalizeNumber = ::canonicalizeUs,
                )
            }.exceptionOrNull()

        assertTrue(error is ExternalBlocklistValidationException)
        assertEquals(
            ExternalBlocklistFailureReason.ROW_LIMIT,
            (error as ExternalBlocklistValidationException).reason,
        )
    }

    @Test
    fun aListDeclaresItsOwnRefreshIntervalInItsHeader() {
        assertEquals(12, declaredHours("# Expires: 12 hours\n212-555-0101"))
        assertEquals(96, declaredHours("// Title: Example\n// Expires: 4 days (update frequency)\n212-555-0101"))
        // A bare number means days, as in uBlock Origin.
        assertEquals(48, declaredHours("# expires: 2\n212-555-0101"))
        assertEquals(12, declaredHours("# Expires: 12 hours\nphone,type\n212-555-0101,scam", "block.csv"))
        assertEquals(6, declaredHours("""{"expires": "6h", "numbers": ["212-555-0101"]}""", "block.json"))
    }

    @Test
    fun anExpiresLineOutsideTheHeaderOrInAnUnknownUnitDeclaresNothing() {
        // Past the first data row it's an ordinary comment, not list metadata.
        assertEquals(0, declaredHours("212-555-0101\n# Expires: 1 hour\n508-555-0102"))
        assertEquals(0, declaredHours("# Expires: 2 weeks\n212-555-0101"))
        assertEquals(0, declaredHours("# Expires: 0 days\n212-555-0101"))
        assertEquals(0, declaredHours("# Expires: 123456 days\n212-555-0101"))
        assertEquals(0, declaredHours("212-555-0101"))
        // Not "1" with no unit, which would mean a day.
        assertEquals(0, declaredHours("# Expires: 1.5 hours\n212-555-0101"))
    }

    @Test
    fun aByteOrderMarkDoesNotHideTheHeader() {
        assertEquals(12, declaredHours("\uFEFF# Expires: 12 hours\n212-555-0101"))
    }

    private fun declaredHours(
        body: String,
        file: String = "block.txt",
    ): Int =
        ExternalBlocklistParser
            .parse(
                rawUrl = "https://lists.example.test/$file",
                rawLabel = "",
                body = body,
                normalizeNumber = ::canonicalizeUs,
            ).declaredRefreshHours

    private fun canonicalizeUs(raw: String): String {
        val digits = raw.filter { it in '0'..'9' }
        return when {
            digits.length == 10 -> "+1$digits"
            digits.length == 11 && digits.startsWith("1") -> "+$digits"
            else -> digits
        }
    }
}
