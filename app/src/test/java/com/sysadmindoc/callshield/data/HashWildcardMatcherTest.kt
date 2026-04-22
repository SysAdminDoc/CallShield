package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.HashWildcardMatcher.Overlap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HashWildcardMatcherTest {

    // ── matches ─────────────────────────────────────────────────────

    @Test fun `exact match with no wildcards`() {
        assertTrue(HashWildcardMatcher.matches("+15551234567", "+15551234567"))
    }

    @Test fun `hash matches any digit`() {
        assertTrue(HashWildcardMatcher.matches("+1555123####", "+15551231234"))
        assertTrue(HashWildcardMatcher.matches("+1555123####", "+15551239876"))
    }

    @Test fun `hash does not match non-digits`() {
        // Pattern expects 4 digits at the end, number has letters — malformed input
        assertFalse(HashWildcardMatcher.matches("+1555123####", "+1555123abcd"))
    }

    @Test fun `length mismatch returns false`() {
        assertFalse(HashWildcardMatcher.matches("+1555123####", "+155512312345"))
        assertFalse(HashWildcardMatcher.matches("+1555123####", "+155512312"))
    }

    @Test fun `plus sign must match literally`() {
        assertFalse(HashWildcardMatcher.matches("+155512#####", "0155512abcde"))
        assertFalse(HashWildcardMatcher.matches("+155512#####", "1155512#####"))
    }

    @Test fun `empty pattern or number returns false`() {
        assertFalse(HashWildcardMatcher.matches("", "+15551234567"))
        assertFalse(HashWildcardMatcher.matches("+15551234567", ""))
        assertFalse(HashWildcardMatcher.matches("", ""))
    }

    @Test fun `french telemarketer range matches 11-digit number`() {
        // ARCEP telemarketer block: +33162######
        assertTrue(HashWildcardMatcher.matches("+33162######", "+33162123456"))
        assertTrue(HashWildcardMatcher.matches("+33162######", "+33162000000"))
        assertFalse(HashWildcardMatcher.matches("+33162######", "+33163123456"))
    }

    // ── matchesWithVariants ─────────────────────────────────────────

    @Test fun `variant match when pattern is international and number is national`() {
        // User wrote +33612######, number arrives as 0612345678
        assertTrue(
            HashWildcardMatcher.matchesWithVariants(
                pattern = "+33612######",
                number = "0612345678",
            )
        )
    }

    @Test fun `variant match when pattern is national and number is international`() {
        assertTrue(
            HashWildcardMatcher.matchesWithVariants(
                pattern = "0612######",
                number = "+33612345678",
            )
        )
    }

    @Test fun `NANP 10-digit number matches plus-one variant`() {
        assertTrue(
            HashWildcardMatcher.matchesWithVariants(
                pattern = "+15551######",
                number = "5551234567",
            )
        )
    }

    // ── coveredNumberCount ──────────────────────────────────────────

    @Test fun `pattern with no hashes covers one number`() {
        assertEquals(1L, HashWildcardMatcher.coveredNumberCount("+15551234567"))
    }

    @Test fun `pattern with 4 hashes covers 10000 numbers`() {
        assertEquals(10_000L, HashWildcardMatcher.coveredNumberCount("+1555123####"))
    }

    @Test fun `pattern with 7 hashes covers 10 million numbers`() {
        assertEquals(10_000_000L, HashWildcardMatcher.coveredNumberCount("+1555#######"))
    }

    @Test fun `extreme hash count returns MAX_VALUE instead of overflowing`() {
        val p = "+" + "#".repeat(25)
        assertEquals(Long.MAX_VALUE, HashWildcardMatcher.coveredNumberCount(p))
    }

    // ── coversOrCoveredBy ───────────────────────────────────────────

    @Test fun `identical patterns are equal`() {
        assertEquals(
            Overlap.EQUAL,
            HashWildcardMatcher.coversOrCoveredBy("+1555123####", "+1555123####")
        )
    }

    @Test fun `broader pattern covers narrower`() {
        // +1555####### covers +1555123#### (one has wider # range)
        assertEquals(
            Overlap.A_COVERS_B,
            HashWildcardMatcher.coversOrCoveredBy("+1555#######", "+1555123####")
        )
    }

    @Test fun `narrower pattern is covered by broader`() {
        assertEquals(
            Overlap.B_COVERS_A,
            HashWildcardMatcher.coversOrCoveredBy("+1555123####", "+1555#######")
        )
    }

    @Test fun `different prefix means no overlap`() {
        assertEquals(
            Overlap.NONE,
            HashWildcardMatcher.coversOrCoveredBy("+1555123####", "+1212123####")
        )
    }

    @Test fun `different length means no overlap`() {
        assertEquals(
            Overlap.NONE,
            HashWildcardMatcher.coversOrCoveredBy("+1555123####", "+15551239####")
        )
    }

    @Test fun `partial wildcard overlap with no coverage`() {
        // +1555#23#### vs +1555#34#### — both have digits in position 6
        // that disagree. Neither covers the other.
        assertEquals(
            Overlap.NONE,
            HashWildcardMatcher.coversOrCoveredBy("+1555#23####", "+1555#34####")
        )
    }

    // ── HashWildcardRule entity delegates correctly ─────────────────

    @Test fun `HashWildcardRule matches delegates to matcher with variants`() {
        val rule = com.sysadmindoc.callshield.data.model.HashWildcardRule(
            pattern = "+33612######",
            description = "Test",
        )
        assertTrue(rule.matches("+33612345678"))
        assertTrue(rule.matches("0612345678"))       // national-format variant
        assertFalse(rule.matches("+33712345678"))    // different exchange
        assertFalse(rule.matches("+336123456789"))   // different length
    }
}
