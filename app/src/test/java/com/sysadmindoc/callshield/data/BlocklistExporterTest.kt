package com.sysadmindoc.callshield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlocklistExporterTest {

    @Test
    fun `parseImport supports export envelope`() {
        val parsed = BlocklistExporter.parseImport(
            """
            {
              "version": 1,
              "app": "CallShield",
              "numbers": [
                {"number": "(212) 555-1234", "type": "spam", "description": "Test entry"}
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, parsed.size)
        assertEquals("2125551234", parsed.single().number)
        assertEquals("spam", parsed.single().type)
        assertEquals("Test entry", parsed.single().description)
    }

    @Test
    fun `parseImport supports simple string arrays`() {
        val parsed = BlocklistExporter.parseImport(
            """
            [
              "(212) 555-1234",
              "+1 (508) 555-0000"
            ]
            """.trimIndent()
        )

        assertEquals(
            listOf("2125551234", "+15085550000"),
            parsed.map { it.number }
        )
        assertTrue(parsed.all { it.type == "unknown" })
    }

    @Test
    fun `parseImport supports plain text number lists`() {
        val parsed = BlocklistExporter.parseImport(
            """
            (212) 555-1234
            # comment line
            +1 (508) 555-0000,

            invalid-entry
            """.trimIndent()
        )

        assertEquals(
            listOf("2125551234", "+15085550000"),
            parsed.map { it.number }
        )
    }

    @Test
    fun `parseImport skips invalid values and de duplicates normalized numbers`() {
        val parsed = BlocklistExporter.parseImport(
            """
            [
              {"number": "   ", "type": "spam", "description": "ignored"},
              {"number": "(212) 555-1234", "type": "  ", "description": " First "},
              {"number": "2125551234", "type": "scam", "description": "Duplicate"},
              {"number": "abcd", "type": "spam", "description": "ignored"}
            ]
            """.trimIndent()
        )

        assertEquals(1, parsed.size)
        assertEquals("2125551234", parsed.single().number)
        assertEquals("unknown", parsed.single().type)
        assertEquals("First", parsed.single().description)
    }
}
