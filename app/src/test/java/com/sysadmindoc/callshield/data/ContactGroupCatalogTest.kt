package com.sysadmindoc.callshield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactGroupCatalogTest {
    @Test
    fun `stable key is deterministic without exposing source values`() {
        val first = ContactGroupCatalog.stableKey("personal@example.com", "com.example", "friends", "Friends")
        val second = ContactGroupCatalog.stableKey("personal@example.com", "com.example", "friends", "Renamed")

        assertEquals(first, second)
        assertEquals(64, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals("friends", first)
    }

    @Test
    fun `stable key distinguishes accounts with the same group`() {
        val personal = ContactGroupCatalog.stableKey("personal", "com.example", "friends", "Friends")
        val work = ContactGroupCatalog.stableKey("work", "com.example", "friends", "Friends")

        assertNotEquals(personal, work)
    }

    @Test
    fun `sanitize keys drops malformed values and caps stored scope`() {
        val valid = (1..105).map { value -> value.toString(16).padStart(64, '0') }

        val result = ContactGroupCatalog.sanitizeKeys(valid + listOf("raw group name", valid.first()))

        assertEquals(100, result.size)
        assertTrue(result.all { it.length == 64 })
    }

    @Test
    fun `malformed present scope remains scoped and cannot become all contacts`() {
        val scope = ContactGroupCatalog.preserveScope(listOf("raw group name"))

        assertEquals(1, scope.size)
        assertTrue(scope.single().matches(Regex("[0-9a-f]{64}")))
        assertTrue(ContactGroupCatalog.preserveScope(emptyList()).isEmpty())
    }
}
