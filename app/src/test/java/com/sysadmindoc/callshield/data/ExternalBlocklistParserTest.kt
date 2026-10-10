package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.model.ListNumberPlan
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
    fun parseReadsListaHuSpanishFieldNames() {
        val paraguay = ListNumberPlan("PY", "595", "0", listOf(8, 9))
        val json =
            ExternalBlocklistParser.parse(
                rawUrl = "https://listahu.org/api/v1/lista/?added_from=2025-01-01",
                rawLabel = "Lista Hũ",
                body =
                    """
                    [
                      {"id": 22601, "numero": "595992856745", "tipo": "Estafa", "screenshot": "https://listahu.org/media/x.jpg",
                       "desc": "Se hace pasar por un banco", "check": false, "added": "2026-10-09T11:17:41-03:00", "votsi": 0, "votno": 0},
                      {"id": 22600, "numero": "0981234567", "tipo": "SPAM", "desc": ""}
                    ]
                    """.trimIndent(),
                normalizeNumber = { paraguay.toInternational(it) ?: it },
            )
        val csv =
            ExternalBlocklistParser.parse(
                rawUrl = "https://listahu.org/descargar/csv/",
                rawLabel = "Lista Hũ",
                body =
                    "\"#\",\"Numero\",\"Tipo\",\"Comentarios\",\"Captura\",\"Fecha_Denuncia\"\n" +
                        "1,\"595985843100\",\"SPAM\",\"Llamadas grabadas\",\"https://listahu.org/media/d2.jpg\",\"2015-02-16 09:37\"\n",
                normalizeNumber = { paraguay.toInternational(it) ?: it },
            )

        assertEquals(listOf("+595992856745", "+595981234567"), json.numbers.map { it.number })
        assertEquals("Estafa", json.numbers.first().type)
        assertEquals("Se hace pasar por un banco", json.numbers.first().description)
        assertEquals(listOf("+595985843100"), csv.numbers.map { it.number })
        assertEquals("Llamadas grabadas", csv.numbers.single().description)
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

    @Test
    fun anInlineCommentIsNotReadAsPartOfTheNumber() {
        val parsed =
            ExternalBlocklistParser.parse(
                rawUrl = "https://lists.example.test/block.txt",
                rawLabel = "",
                body = "# full-line comment\n+1 212 555 0101 # 3 reports\n508-555-0102 // robocall\n",
                normalizeNumber = ::canonicalizeUs,
            )

        assertEquals("txt", parsed.format)
        assertEquals(listOf("+12125550101", "+15085550102"), parsed.numbers.map { it.number })
        assertEquals(0, parsed.skippedRows)
    }

    @Test
    fun aByteOrderMarkDoesNotHideJsonOrACsvHeader() {
        val json =
            ExternalBlocklistParser.parse(
                rawUrl = "https://lists.example.test/feed",
                rawLabel = "",
                body = "\uFEFF{\"numbers\": [\"212-555-0101\"]}",
                normalizeNumber = ::canonicalizeUs,
            )
        // The BOM sat on the first header cell, so the type column went unread.
        val csv =
            ExternalBlocklistParser.parse(
                rawUrl = "https://lists.example.test/block.csv",
                rawLabel = "",
                body = "\uFEFFtype,phone\nscam,508-555-0102\n",
                normalizeNumber = ::canonicalizeUs,
            )

        assertEquals("json", json.format)
        assertEquals(listOf("+12125550101"), json.numbers.map { it.number })
        assertEquals(listOf("+15085550102"), csv.numbers.map { it.number })
        assertEquals("scam", csv.numbers.single().type)
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
