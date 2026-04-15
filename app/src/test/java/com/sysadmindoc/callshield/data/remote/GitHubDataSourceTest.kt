package com.sysadmindoc.callshield.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubDataSourceTest {

    private val dataSource = GitHubDataSource()

    @Test
    fun `parseHotListJson supports metadata envelope`() {
        val parsed = dataSource.parseHotListJson(
            """
            {
              "generated": "2026-04-15T17:55:02Z",
              "count": 2,
              "numbers": [
                {
                  "number": " +12125551234 ",
                  "type": " ",
                  "description": "  Trending community report  "
                },
                {
                  "number": " ",
                  "type": "robocall",
                  "description": "Ignored"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, parsed.size)
        assertEquals("+12125551234", parsed.single().number)
        assertEquals("robocall", parsed.single().type)
        assertEquals("Trending community report", parsed.single().description)
    }

    @Test
    fun `parseHotListJson supports legacy top-level array`() {
        val parsed = dataSource.parseHotListJson(
            """
            [
              {
                "number": "5551112222",
                "type": "scam",
                "description": ""
              }
            ]
            """.trimIndent()
        )

        assertEquals(1, parsed.size)
        assertEquals("5551112222", parsed.single().number)
        assertEquals("scam", parsed.single().type)
        assertEquals("Trending community report", parsed.single().description)
    }

    @Test
    fun `parseHotRangesJson supports envelope and legacy array`() {
        val envelopeParsed = dataSource.parseHotRangesJson(
            """
            {
              "generated": "2026-04-15T17:55:02Z",
              "ranges": [
                { "npanxx": "212555" },
                { "npanxx": "310555" }
              ]
            }
            """.trimIndent()
        )
        val arrayParsed = dataSource.parseHotRangesJson("""["212555", "310555"]""")

        assertEquals(listOf("212555", "310555"), envelopeParsed)
        assertEquals(listOf("212555", "310555"), arrayParsed)
    }

    @Test
    fun `parseSpamDomainsJson supports envelope and legacy array`() {
        val envelopeParsed = dataSource.parseSpamDomainsJson(
            """
            {
              "generated": "2026-04-15T08:36:51Z",
              "domains": [" evil.com ", "second.net", " "]
            }
            """.trimIndent()
        )
        val arrayParsed = dataSource.parseSpamDomainsJson("""["evil.com", "second.net"]""")

        assertEquals(listOf("evil.com", "second.net"), envelopeParsed)
        assertEquals(listOf("evil.com", "second.net"), arrayParsed)
    }
}
