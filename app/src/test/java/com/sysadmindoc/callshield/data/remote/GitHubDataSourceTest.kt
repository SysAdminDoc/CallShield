package com.sysadmindoc.callshield.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubDataSourceTest {
    private val dataSource = GitHubDataSource()

    @Test
    fun `parseHotListJson supports metadata envelope`() {
        val parsed =
            dataSource.parseHotListJson(
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
                """.trimIndent(),
            )

        assertEquals(1, parsed.size)
        assertEquals("+12125551234", parsed.single().number)
        assertEquals("robocall", parsed.single().type)
        assertEquals("Trending community report", parsed.single().description)
    }

    @Test
    fun `parseLatestReleaseJson accepts release links and checksum asset`() {
        val result =
            dataSource
                .parseLatestReleaseJson(
                    """
                    {
                      "tag_name": "v1.8.0",
                      "html_url": "https://github.com/SysAdminDoc/CallShield/releases/tag/v1.8.0",
                      "assets": [
                        {
                          "name": "CallShield.apk",
                          "browser_download_url": "https://github.com/SysAdminDoc/CallShield/releases/download/v1.8.0/CallShield.apk"
                        },
                        {
                          "name": "CallShield.apk.sha256",
                          "browser_download_url": "https://github.com/SysAdminDoc/CallShield/releases/download/v1.8.0/CallShield.apk.sha256"
                        }
                      ]
                    }
                    """.trimIndent(),
                )
        val parsed = result.getOrThrow()

        assertEquals("v1.8.0", parsed.tagName)
        assertEquals("https://github.com/SysAdminDoc/CallShield/releases/tag/v1.8.0", parsed.htmlUrl)
        assertEquals(
            "https://github.com/SysAdminDoc/CallShield/releases/download/v1.8.0/CallShield.apk.sha256",
            parsed.checksumUrl,
        )
    }

    @Test
    fun `parseLatestReleaseJson rejects non GitHub release links`() {
        assertTrue(
            dataSource
                .parseLatestReleaseJson(
                    """{"tag_name":"v1.8.0","html_url":"https://example.com/release"}""",
                ).isFailure,
        )
    }

    @Test
    fun `parseHotListJson supports legacy top-level array`() {
        val parsed =
            dataSource.parseHotListJson(
                """
                [
                  {
                    "number": "5551112222",
                    "type": "scam",
                    "description": ""
                  }
                ]
                """.trimIndent(),
            )

        assertEquals(1, parsed.size)
        assertEquals("5551112222", parsed.single().number)
        assertEquals("scam", parsed.single().type)
        assertEquals("Trending community report", parsed.single().description)
    }

    @Test
    fun `hot feed envelopes expose explicit clear without treating legacy arrays as clear`() {
        val cleared =
            dataSource.parseHotListSnapshotJson(
                """{"cleared":true,"numbers":[]}""",
            )
        val legacy = dataSource.parseHotListSnapshotJson("[]")

        assertTrue(cleared.data.isEmpty())
        assertTrue(cleared.explicitlyCleared)
        assertTrue(legacy.data.isEmpty())
        assertTrue(!legacy.explicitlyCleared)
    }

    @Test
    fun `hot range and domain envelopes expose explicit clear`() {
        val ranges = dataSource.parseHotRangesSnapshotJson("""{"cleared":true,"ranges":[]}""")
        val domains = dataSource.parseSpamDomainsSnapshotJson("""{"cleared":true,"domains":[]}""")

        assertTrue(ranges.explicitlyCleared)
        assertTrue(domains.explicitlyCleared)
    }

    @Test
    fun `parseHotRangesJson supports envelope and legacy array`() {
        val envelopeParsed =
            dataSource.parseHotRangesJson(
                """
                {
                  "generated": "2026-04-15T17:55:02Z",
                  "ranges": [
                    { "npanxx": "212555" },
                    { "npanxx": "310555" }
                  ]
                }
                """.trimIndent(),
            )
        val arrayParsed = dataSource.parseHotRangesJson("""["212555", "310555"]""")

        assertEquals(listOf("212555", "310555"), envelopeParsed)
        assertEquals(listOf("212555", "310555"), arrayParsed)
    }

    @Test
    fun `parseSpamDomainsJson supports envelope and legacy array`() {
        val envelopeParsed =
            dataSource.parseSpamDomainsJson(
                """
                {
                  "generated": "2026-04-15T08:36:51Z",
                  "domains": [" evil.com ", "second.net", " "]
                }
                """.trimIndent(),
            )
        val arrayParsed = dataSource.parseSpamDomainsJson("""["evil.com", "second.net"]""")

        assertEquals(listOf("evil.com", "second.net"), envelopeParsed)
        assertEquals(listOf("evil.com", "second.net"), arrayParsed)
    }

    @Test
    fun `parseSpamDatabaseJson accepts valid minimal schema`() {
        val parsed =
            dataSource.parseSpamDatabaseJson(
                """
                {
                  "version": 1,
                  "updated": "2026-07-02T00:00:00Z",
                  "numbers": [],
                  "prefixes": []
                }
                """.trimIndent(),
            )

        assertTrue(parsed.isSuccess)
        assertEquals(1, parsed.getOrThrow().version)
    }

    @Test
    fun `parseSpamDatabaseJson rejects missing updated timestamp`() {
        val parsed =
            dataSource.parseSpamDatabaseJson(
                """
                {
                  "version": 1,
                  "updated": "",
                  "numbers": [],
                  "prefixes": []
                }
                """.trimIndent(),
            )

        assertFeedValidationResult(GitHubFeedFailureReason.MISSING_SCHEMA_FIELD, parsed)
    }

    @Test
    fun `parseSpamShardManifestJson validates the content addressed schema`() {
        val parsed =
            dataSource.parseSpamShardManifestJson(
                """
                {
                  "format_version": 1,
                  "version": 7,
                  "updated": "2026-08-10",
                  "legacy_path": "data/spam_numbers.json",
                  "shard_directory": "data/spam_number_shards",
                  "shard_count": 256,
                  "shards": [
                    {
                      "id": "00",
                      "path": "data/spam_number_shards/00.json",
                      "sha256": "${"a".repeat(64)}",
                      "bytes": 128,
                      "numbers": 1,
                      "prefixes": 0
                    }
                  ]
                }
                """.trimIndent(),
            )

        assertTrue(parsed.isSuccess)
        val descriptor = parsed.getOrThrow().shards.single()
        assertEquals("00", descriptor.id)
    }

    @Test
    fun `parseSpamShardManifestJson rejects a path outside the shard directory`() {
        val parsed =
            dataSource.parseSpamShardManifestJson(
                """
                {
                  "format_version": 1,
                  "version": 7,
                  "updated": "2026-08-10",
                  "legacy_path": "data/spam_numbers.json",
                  "shard_directory": "data/spam_number_shards",
                  "shard_count": 256,
                  "shards": [
                    {
                      "id": "00",
                      "path": "data/other/00.json",
                      "sha256": "${"a".repeat(64)}",
                      "bytes": 128,
                      "numbers": 1,
                      "prefixes": 0
                    }
                  ]
                }
                """.trimIndent(),
            )

        assertFeedValidationResult(GitHubFeedFailureReason.INVALID_SCHEMA, parsed)
    }

    @Test
    fun `parseHotListJson rejects row count over cap`() {
        val body =
            buildJsonArray(GitHubDataSource.MAX_HOT_LIST_ROWS + 1) { index ->
                """{"number":"+1212555$index"}"""
            }

        val error =
            assertFeedValidation(GitHubFeedFailureReason.ROW_LIMIT) {
                dataSource.parseHotListJson(body)
            }

        assertTrue(error.message?.contains("hot list row count") == true)
    }

    @Test
    fun `parseSpamDomainsJson rejects row count over cap`() {
        val body =
            buildJsonArray(GitHubDataSource.MAX_SPAM_DOMAIN_ROWS + 1) { index ->
                """"domain$index.test""""
            }

        val error =
            assertFeedValidation(GitHubFeedFailureReason.ROW_LIMIT) {
                dataSource.parseSpamDomainsJson(body)
            }

        assertTrue(error.message?.contains("spam domains row count") == true)
    }

    @Test
    fun `validateRawFeedBody rejects oversized model weights`() {
        val body =
            """{"version":3,"padding":""" +
                """"${"x".repeat((GitHubDataSource.MAX_MODEL_WEIGHTS_BYTES + 1L).toInt())}"}"""

        val error =
            assertFeedValidation(GitHubFeedFailureReason.OVERSIZE) {
                GitHubDataSource.validateRawFeedBody(GitHubDataSource.MODEL_WEIGHTS_PATH, body)
            }

        assertTrue(error.message?.contains("model weights feed exceeded") == true)
    }

    @Test
    fun `validateRawFeedBody rejects model weights without version`() {
        val error =
            assertFeedValidation(GitHubFeedFailureReason.MISSING_SCHEMA_FIELD) {
                GitHubDataSource.validateRawFeedBody(
                    GitHubDataSource.MODEL_WEIGHTS_PATH,
                    """{"trees":[]}""",
                )
            }

        assertTrue(error.message?.contains("version") == true)
    }

    private fun buildJsonArray(
        count: Int,
        item: (Int) -> String,
    ): String =
        buildString {
            append("[")
            repeat(count) { index ->
                if (index > 0) append(",")
                append(item(index))
            }
            append("]")
        }

    private fun assertFeedValidationResult(
        reason: GitHubFeedFailureReason,
        result: Result<*>,
    ): GitHubFeedValidationException {
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        if (error !is GitHubFeedValidationException) {
            throw AssertionError("Expected GitHubFeedValidationException, got $error")
        }
        assertEquals(reason, error.reason)
        return error
    }

    private fun assertFeedValidation(
        reason: GitHubFeedFailureReason,
        block: () -> Unit,
    ): GitHubFeedValidationException =
        try {
            block()
            throw AssertionError("Expected GitHubFeedValidationException")
        } catch (error: GitHubFeedValidationException) {
            assertEquals(reason, error.reason)
            error
        }
}
