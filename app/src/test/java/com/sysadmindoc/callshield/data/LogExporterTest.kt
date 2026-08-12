package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.model.BlockedCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

/**
 * Unit tests for LogExporter — CSV export logic.
 * Tests the csvEscape private method via reflection.
 */
class LogExporterTest {
    private val csvEscape: Method =
        LogExporter::class.java.getDeclaredMethod("csvEscape", String::class.java).apply {
            isAccessible = true
        }

    private fun escape(value: String): String = csvEscape.invoke(LogExporter, value) as String

    // ── csvEscape: plain text ────────────────────────────────────────────

    @Test
    fun `csvEscape wraps plain text in quotes`() {
        assertEquals("\"hello\"", escape("hello"))
    }

    @Test
    fun `csvEscape wraps empty string in quotes`() {
        assertEquals("\"\"", escape(""))
    }

    @Test
    fun `csvEscape wraps single word in quotes`() {
        assertEquals("\"test\"", escape("test"))
    }

    // ── csvEscape: text with commas ──────────────────────────────────────

    @Test
    fun `csvEscape handles text with comma`() {
        assertEquals("\"hello, world\"", escape("hello, world"))
    }

    @Test
    fun `csvEscape handles multiple commas`() {
        assertEquals("\"a,b,c,d\"", escape("a,b,c,d"))
    }

    // ── csvEscape: text with double quotes ───────────────────────────────

    @Test
    fun `csvEscape doubles internal quotes`() {
        assertEquals("\"say \"\"hello\"\"\"", escape("say \"hello\""))
    }

    @Test
    fun `csvEscape handles single double quote`() {
        assertEquals("\"\"\"\"", escape("\""))
    }

    @Test
    fun `csvEscape handles multiple double quotes`() {
        assertEquals("\"\"\"a\"\" and \"\"b\"\"\"", escape("\"a\" and \"b\""))
    }

    // ── csvEscape: text with newlines ────────────────────────────────────

    @Test
    fun `csvEscape replaces newline with space`() {
        assertEquals("\"line1 line2\"", escape("line1\nline2"))
    }

    @Test
    fun `csvEscape removes carriage return`() {
        assertEquals("\"line1 line2\"", escape("line1\r\nline2"))
    }

    @Test
    fun `csvEscape handles multiple newlines`() {
        assertEquals("\"a b c\"", escape("a\nb\nc"))
    }

    @Test
    fun `csvEscape handles carriage return only`() {
        assertEquals("\"ab\"", escape("a\rb"))
    }

    // ── csvEscape: combined special characters ───────────────────────────

    @Test
    fun `csvEscape handles quotes commas and newlines together`() {
        assertEquals("\"He said \"\"hi,\"\" then left\"", escape("He said \"hi,\" then\nleft"))
    }

    @Test
    fun `csvEscape handles phone number format`() {
        assertEquals("\"(212) 555-1234\"", escape("(212) 555-1234"))
    }

    @Test
    fun `csvEscape handles date format`() {
        assertEquals("\"2024-01-15 14:30:00\"", escape("2024-01-15 14:30:00"))
    }

    @Test
    fun `csvEscape handles SMS body with spam content`() {
        val body = "You've won \$1000! Click here: https://spam.xyz"
        assertEquals("\"$body\"", escape(body))
    }

    @Test
    fun `csvEscape handles long text with special chars`() {
        val input = "This is a \"test\" message,\nwith multiple lines\r\nand various, special characters"
        val expected = "\"This is a \"\"test\"\" message, with multiple lines and various, special characters\""
        assertEquals(expected, escape(input))
    }

    @Test
    fun `exportToCsv redacts SMS bodies by default`() {
        val rawBody = "Your reset code is 987654. Tap https://phish.example/reset?token=secret"
        val csv =
            LogExporter.exportToCsv(
                listOf(
                    BlockedCall(
                        number = "+15551234567",
                        timestamp = 0L,
                        type = "sms_spam",
                        isCall = false,
                        smsBody = rawBody,
                        matchReason = "sms_content",
                        confidence = 92,
                    ),
                ),
            )

        assertTrue(csv.contains("SMS body redacted"))
        assertTrue(csv.contains("phish.example"))
        assertTrue(csv.contains("code-like tokens hidden"))
        assertFalse(csv.contains("Your reset code"))
        assertFalse(csv.contains("987654"))
        assertFalse(csv.contains("token=secret"))
        assertTrue(csv.startsWith("Number,Date,Type,IsCall,ReasonCode,RuleId,Confidence,PipelineDiagnostic,SMSBody\n"))
        assertTrue(csv.contains("sms_content"))
    }

    @Test
    fun `exportToCsv includes privacy safe pipeline diagnostic`() {
        val csv =
            LogExporter.exportToCsv(
                listOf(
                    BlockedCall(
                        number = "+15551234567",
                        timestamp = 0L,
                        matchReason = "pipeline_diagnostic",
                        wasBlocked = false,
                        pipelineDiagnostic = "budget_exhausted|cutoff=ml_scorer|unevaluated=ml_scorer",
                    ),
                ),
            )

        assertTrue(csv.contains("PipelineDiagnostic"))
        assertTrue(csv.contains("budget_exhausted|cutoff=ml_scorer"))
    }

    // ── csvEscape: spreadsheet formula-injection neutralization ──────────

    @Test
    fun `csvEscape neutralizes leading equals formula`() {
        assertEquals("\"'=HYPERLINK(\"\"http://evil\"\")\"", escape("=HYPERLINK(\"http://evil\")"))
    }

    @Test
    fun `csvEscape neutralizes leading plus minus at`() {
        assertEquals("\"'+1+2\"", escape("+1+2"))
        assertEquals("\"'-2+3\"", escape("-2+3"))
        assertEquals("\"'@SUM(A1)\"", escape("@SUM(A1)"))
    }

    @Test
    fun `csvEscape leaves safe leading characters untouched`() {
        assertEquals("\"1+2\"", escape("1+2"))
        assertEquals("\"spam\"", escape("spam"))
        assertEquals("\"(212) 555-1234\"", escape("(212) 555-1234"))
    }

    @Test
    fun `exportToCsv neutralizes formula in an attacker-influenced SMS body`() {
        val csv =
            LogExporter.exportToCsv(
                calls =
                    listOf(
                        BlockedCall(
                            number = "+15551234567",
                            timestamp = 0L,
                            type = "sms_spam",
                            isCall = false,
                            smsBody = "=cmd|'/c calc'!A1",
                            matchReason = "sms_content",
                            confidence = 92,
                        ),
                    ),
                includeRawSmsBodies = true,
            )
        assertTrue(csv.contains("\"'=cmd"))
        assertFalse(csv.contains(",\"=cmd"))
    }

    @Test
    fun `exportToCsv can include raw SMS bodies explicitly`() {
        val rawBody = "Your reset code is 987654. Tap https://phish.example/reset?token=secret"
        val csv =
            LogExporter.exportToCsv(
                calls =
                    listOf(
                        BlockedCall(
                            number = "+15551234567",
                            timestamp = 0L,
                            type = "sms_spam",
                            isCall = false,
                            smsBody = rawBody,
                            matchReason = "sms_content",
                            confidence = 92,
                        ),
                    ),
                includeRawSmsBodies = true,
            )

        assertTrue(csv.contains(rawBody))
    }

    @Test
    fun `exportRedressToCsv contains only required blocked call fields`() {
        val timestamp = 1_735_689_600_000L
        val csv =
            LogExporter.exportRedressToCsv(
                listOf(
                    BlockedCall(
                        number = "+12125550100",
                        timestamp = timestamp,
                        isCall = true,
                        wasBlocked = true,
                        matchReason = "user_blocklist",
                        smsBody = "secret code 1234",
                        ruleId = 17L,
                    ),
                    BlockedCall(
                        number = "+12125550101",
                        timestamp = timestamp,
                        isCall = false,
                        wasBlocked = true,
                        matchReason = "sms_content",
                        smsBody = "not a call",
                    ),
                    BlockedCall(
                        number = "+12125550102",
                        timestamp = timestamp,
                        isCall = true,
                        wasBlocked = false,
                        matchReason = "emergency_floor",
                    ),
                ),
            )

        assertTrue(csv.startsWith("Date,Time,CallingNumber,ReasonCode,RuleId\n"))
        assertTrue(csv.contains("+12125550100"))
        assertTrue(csv.contains("user_blocklist"))
        assertTrue(csv.contains("\"user_blocklist\",17"))
        assertFalse(csv.contains("+12125550101"))
        assertFalse(csv.contains("+12125550102"))
        assertFalse(csv.contains("secret code"))
    }

    @Test
    fun `batched CSV writer follows the stable cursor across pages`() =
        runBlocking {
            val calls =
                (0 until 513).map { index ->
                    BlockedCall(
                        id = index + 1L,
                        number = "+1212555${index.toString().padStart(4, '0')}",
                        timestamp = 1_000_000L - index,
                        matchReason = "heuristic",
                    )
                }
            var reads = 0
            val reader: BlockedCallBatchReader = { beforeTimestamp, beforeId, limit ->
                reads++
                calls
                    .filter { call ->
                        call.timestamp < beforeTimestamp ||
                            (call.timestamp == beforeTimestamp && call.id < beforeId)
                    }.take(limit)
            }

            val output = StringBuilder()
            assertTrue(LogExporter.writeCsvBatches(output, reader))

            assertEquals(3, reads)
            assertEquals(514, output.count { it == '\n' })
            assertTrue(output.indexOf("+12125550000") < output.indexOf("+12125550512"))
        }

    @Test
    fun `batched redress writer filters non-call and allowed rows`() =
        runBlocking {
            val calls =
                (0 until 300).map { index ->
                    BlockedCall(
                        id = index + 1L,
                        number = "+1212555${index.toString().padStart(4, '0')}",
                        timestamp = 2_000_000L - index,
                        isCall = index % 3 != 0,
                        wasBlocked = index % 3 == 1,
                        matchReason = "heuristic",
                    )
                }
            val reader: BlockedCallBatchReader = { beforeTimestamp, beforeId, limit ->
                calls
                    .filter { call ->
                        call.timestamp < beforeTimestamp ||
                            (call.timestamp == beforeTimestamp && call.id < beforeId)
                    }.take(limit)
            }

            val output = StringBuilder()
            assertTrue(LogExporter.writeRedressBatches(output, reader))

            assertEquals(101, output.count { it == '\n' })
            assertTrue(output.contains("+12125550001"))
            assertFalse(output.contains("+12125550000"))
            assertFalse(output.contains("+12125550002"))
        }
}
