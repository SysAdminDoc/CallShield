package com.sysadmindoc.callshield.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.sysadmindoc.callshield.domain.model.SpamCheckResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryCallPolicyTest {
    @Test
    fun `every concrete category supports every action with inherit stored sparsely`() {
        for (category in CategoryCallPolicy.configurableCategories) {
            for (action in CategoryCallAction.entries) {
                val serialized = CategoryCallPolicy.update(emptySet(), category, action)
                val decoded = CategoryCallPolicy.decode(serialized)
                if (action == CategoryCallAction.INHERIT) {
                    assertFalse(decoded.containsKey(category))
                } else {
                    assertEquals(action, decoded[category])
                }
            }
        }
        assertEquals(CallCategory.entries.size - 1, CategoryCallPolicy.configurableCategories.size)
    }

    @Test
    fun `invalid and unknown serialized actions are discarded`() {
        assertEquals(
            setOf("scam=allow"),
            CategoryCallPolicy.sanitize(
                listOf(
                    "scam=allow",
                    "scam=allow",
                    "unknown=block",
                    "robocall=invalid",
                    "garbage",
                ),
            ),
        )
    }

    @Test
    fun `configured action decorates a categorized reputation verdict`() {
        val result = SpamCheckResult(true, matchSource = "database", type = "scam")
        val prefs = mutablePreferencesOf(SpamRepository.KEY_CATEGORY_CALL_ACTIONS to setOf("scam=silence"))

        val applied = CategoryCallPolicy.apply(result, prefs)
        val policy = requireNotNull(CategoryCallPolicy.parseMatchSource(applied.matchSource))

        assertTrue(applied.isSpam)
        assertEquals(CallCategory.Scam, policy.category)
        assertEquals(CategoryCallAction.SILENCE, policy.action)
        assertEquals("database", policy.originalMatchSource)
        assertEquals(CallCategory.Scam, CallCategoryResolver.resolve(applied))
    }

    @Test
    fun `explicit personal blocks are authoritative over category allow`() {
        val result = SpamCheckResult(true, matchSource = "user_blocklist", type = "scam")
        val prefs = mutablePreferencesOf(SpamRepository.KEY_CATEGORY_CALL_ACTIONS to setOf("scam=allow"))

        assertSame(result, CategoryCallPolicy.apply(result, prefs))
    }

    @Test
    fun `every explicit block guard matches the checker's real matchSource string`() {
        // Pins the guard set against the strings the checkers actually emit —
        // a typo here (e.g. system_blocklist vs system_block_list) silently
        // turns the guard into dead code.
        val prefs = mutablePreferencesOf(SpamRepository.KEY_CATEGORY_CALL_ACTIONS to setOf("scam=allow"))
        val emittedExplicitSources =
            listOf(
                "user_blocklist",
                "temporary_block",
                "system_block_list",
                "wildcard",
                "hash_wildcard",
                "prefix",
            )
        for (source in emittedExplicitSources) {
            val result = SpamCheckResult(true, matchSource = source, type = "scam")
            assertSame("category allow must never rewrite $source", result, CategoryCallPolicy.apply(result, prefs))
        }
    }

    @Test
    fun `emergency and manual whitelist allows are never changed by category rules`() {
        val prefs = mutablePreferencesOf(SpamRepository.KEY_CATEGORY_CALL_ACTIONS to setOf("scam=block"))
        for (source in listOf("emergency_contact", "manual_whitelist")) {
            val result = SpamCheckResult(false, matchSource = source, type = "scam")
            assertSame(result, CategoryCallPolicy.apply(result, prefs))
        }
    }

    @Test
    fun `unknown categories inherit regardless of stored values`() {
        val result = SpamCheckResult(true, matchSource = "time_block", type = "unknown")
        val prefs = mutablePreferencesOf(SpamRepository.KEY_CATEGORY_CALL_ACTIONS to setOf("unknown=allow"))

        assertSame(result, CategoryCallPolicy.apply(result, prefs))
        assertNull(CategoryCallPolicy.parseMatchSource(result.matchSource))
    }
}
