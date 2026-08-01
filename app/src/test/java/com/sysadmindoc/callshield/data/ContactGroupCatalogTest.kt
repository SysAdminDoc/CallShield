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
    fun `local group key survives rename and distinguishes provider rows`() {
        val beforeRename = ContactGroupCatalog.stableKey("local", "type", null, "Friends", 17L)
        val afterRename = ContactGroupCatalog.stableKey("local", "type", null, "Family", 17L)
        val differentRow = ContactGroupCatalog.stableKey("local", "type", null, "Family", 18L)

        assertEquals(beforeRename, afterRename)
        assertNotEquals(beforeRename, differentRow)
    }

    @Test
    fun `legacy local title key migrates to rename stable row key`() {
        val legacy = ContactGroupCatalog.stableKey("local", "type", null, "Friends")
        val current = ContactGroupCatalog.stableKey("local", "type", null, "Friends", 17L)
        val groups = listOf(ContactGroup(current, "Friends", "local", 3, legacy))

        assertEquals(setOf(current), ContactGroupCatalog.migrateSelectedKeys(groups, setOf(legacy)))
    }

    @Test
    fun `ambiguous legacy local title key remains fail closed`() {
        val legacy = ContactGroupCatalog.stableKey("local", "type", null, "Friends")
        val first = ContactGroupCatalog.stableKey("local", "type", null, "Friends", 17L)
        val second = ContactGroupCatalog.stableKey("local", "type", null, "Friends", 18L)
        val groups =
            listOf(
                ContactGroup(first, "Friends", "local", 3, legacy),
                ContactGroup(second, "Friends", "local", 2, legacy),
            )

        assertEquals(setOf(legacy), ContactGroupCatalog.migrateSelectedKeys(groups, setOf(legacy)))
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
