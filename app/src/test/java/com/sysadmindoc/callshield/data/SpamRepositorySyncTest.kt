package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.SpamNumberJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpamRepositorySyncTest {

    @Test
    fun `sanitizeDatabaseNumbers preserves user block flag for matching remote numbers`() {
        val sanitized = sanitizeDatabaseNumbers(
            databaseNumbers = listOf(
                SpamNumberJson(
                    number = "(212) 555-1234",
                    type = "robocall",
                    reports = 9,
                    description = "Community spam"
                ),
                SpamNumberJson(number = "   ", type = "spam")
            ),
            normalizeNumber = { raw ->
                raw.trim().filter { it.isDigit() }.let { digits ->
                    when {
                        digits.isBlank() -> ""
                        digits.length == 10 -> digits
                        digits.length == 11 && digits.startsWith("1") -> "+$digits"
                        else -> digits
                    }
                }
            },
            preservedUserBlockedNumbers = setOf("2125551234")
        )

        assertEquals(1, sanitized.size)
        assertEquals("2125551234", sanitized.single().number)
        assertTrue(sanitized.single().isUserBlocked)
        assertEquals("github", sanitized.single().source)
    }

    @Test
    fun `sanitizeDatabaseNumbers enforces minimum report count and trims fields`() {
        val sanitized = sanitizeDatabaseNumbers(
            databaseNumbers = listOf(
                SpamNumberJson(
                    number = "+1 508 555 0000",
                    type = "  ",
                    reports = 0,
                    description = "  Test  "
                )
            ),
            normalizeNumber = { raw ->
                val trimmed = raw.trim()
                val digits = trimmed.filter { it.isDigit() }
                if (trimmed.startsWith("+")) "+$digits" else digits
            },
            preservedUserBlockedNumbers = emptySet()
        )

        assertEquals(1, sanitized.size)
        assertEquals("unknown", sanitized.single().type)
        assertEquals(1, sanitized.single().reports)
        assertEquals("Test", sanitized.single().description)
    }

    @Test
    fun `mergeHotListNumbers skips stronger existing database rows`() {
        val hotNumbers = listOf(
            SpamNumber(number = "+12125551234", type = "robocall", description = "Hot", source = "hot_list"),
            SpamNumber(number = "+15085550000", type = "robocall", description = "Fresh", source = "hot_list")
        )
        val existingByNumber = mapOf(
            "+12125551234" to SpamNumber(
                id = 10,
                number = "+12125551234",
                type = "scam",
                description = "Main database",
                source = "github"
            )
        )

        val merged = mergeHotListNumbers(hotNumbers, existingByNumber)

        assertEquals(1, merged.size)
        assertEquals("+15085550000", merged.single().number)
    }

    @Test
    fun `mergeHotListNumbers preserves user block state for existing hot rows`() {
        val existing = SpamNumber(
            id = 77,
            number = "+12125551234",
            type = "robocall",
            description = "Previous hot row",
            source = "hot_list",
            isUserBlocked = true
        )

        val merged = mergeHotListNumbers(
            hotNumbers = listOf(
                SpamNumber(
                    number = "+12125551234",
                    type = "robocall",
                    description = "Updated hot row",
                    source = "hot_list"
                )
            ),
            existingByNumber = mapOf(existing.number to existing)
        )

        assertEquals(1, merged.size)
        assertEquals(existing.id, merged.single().id)
        assertTrue(merged.single().isUserBlocked)
        assertEquals("hot_list", merged.single().source)
        assertFalse(merged.single().description.isBlank())
    }

    @Test
    fun `resolveSpamNumberForWhitelist deletes user-owned block rows`() {
        val existing = SpamNumber(
            id = 5,
            number = "2125551234",
            type = "unknown",
            source = "user",
            isUserBlocked = true
        )

        val resolution = resolveSpamNumberForWhitelist(existing)

        assertTrue(resolution is SpamNumberWhitelistResolution.Delete)
        assertEquals(existing, (resolution as SpamNumberWhitelistResolution.Delete).number)
    }

    @Test
    fun `resolveSpamNumberForWhitelist clears user block flag on shared rows`() {
        val existing = SpamNumber(
            id = 8,
            number = "2125551234",
            type = "robocall",
            source = "github",
            isUserBlocked = true
        )

        val resolution = resolveSpamNumberForWhitelist(existing)

        assertTrue(resolution is SpamNumberWhitelistResolution.Update)
        val updated = (resolution as SpamNumberWhitelistResolution.Update).number
        assertEquals(existing.id, updated.id)
        assertFalse(updated.isUserBlocked)
        assertEquals("github", updated.source)
    }

    @Test
    fun `resolveSpamNumberForWhitelist ignores non blocked rows`() {
        val existing = SpamNumber(
            id = 9,
            number = "2125551234",
            type = "robocall",
            source = "github",
            isUserBlocked = false
        )

        val resolution = resolveSpamNumberForWhitelist(existing)

        assertTrue(resolution is SpamNumberWhitelistResolution.None)
    }
}
