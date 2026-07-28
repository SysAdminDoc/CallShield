package com.sysadmindoc.callshield.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [WildcardRule.matches]. Focus on the v1.6.3 fix: the
 * regex path now runs over [WildcardRule.numberVariants] for parity
 * with the glob path. Before v1.6.3, a user-written regex anchored to
 * the `+1` form would quietly miss SMS senders arriving as bare digits.
 */
class WildcardRuleTest {
    // ── Glob path (unchanged) ───────────────────────────────────────

    @Test fun `glob matches exact E164 number`() {
        val rule = wildcard(pattern = "+1832555*", isRegex = false)
        assertTrue(rule.matches("+18325551234"))
    }

    @Test fun `glob also matches 10-digit form via numberVariants`() {
        val rule = wildcard(pattern = "+1832555*", isRegex = false)
        // Without numberVariants, "8325551234" would miss "+1832555*".
        assertTrue(rule.matches("8325551234"))
    }

    @Test fun `glob question mark matches a single digit only`() {
        val rule = wildcard(pattern = "+1832555?234", isRegex = false)
        assertTrue(rule.matches("+18325551234"))
        assertFalse(rule.matches("+183255512345"))
    }

    @Test fun `glob escapes literal dot`() {
        // Pre-escape, "." would be regex any-char; the rule should
        // ONLY match "212.555..." not "2120555..."
        val rule = wildcard(pattern = "212.555*", isRegex = false)
        assertTrue(rule.matches("212.5551234"))
        assertFalse(rule.matches("2125551234"))
    }

    // ── Regex path (v1.6.3: numberVariants now applied) ─────────────

    @Test fun `regex anchored to E164 also matches 10-digit form`() {
        // Pattern expects the `+1` prefix. Before v1.6.3 this matched
        // only the E.164 form; the glob path already handled variants
        // and users reasonably expected the same from regex.
        val rule = wildcard(pattern = "^\\+1832555\\d{4}$", isRegex = true)
        assertTrue(rule.matches("+18325551234"))
        // v1.6.3: bare digits now match via numberVariants.
        assertTrue(rule.matches("8325551234"))
        assertTrue(rule.matches("18325551234"))
    }

    @Test fun `regex with substring match still works on any variant`() {
        // Contains-match semantics: pattern searches for a substring
        // inside any variant. This was the baseline behavior pre-v1.6.3
        // for the raw-input form, now extended to all variants.
        val rule = wildcard(pattern = "832555", isRegex = true)
        assertTrue(rule.matches("+18325551234"))
        assertTrue(rule.matches("8325551234"))
    }

    @Test fun `regex rejects overly long patterns`() {
        val bigPattern = "(" + "a".repeat(250) + ")"
        val rule = wildcard(pattern = bigPattern, isRegex = true)
        // ReDoS guard: patterns over 200 chars are short-circuited.
        assertFalse(rule.matches("whatever"))
    }

    @Test fun `invalid regex fails closed`() {
        // Unclosed paren — pattern compile throws; matcher returns false
        // rather than crashing the screening pipeline.
        val rule = wildcard(pattern = "(unclosed", isRegex = true)
        assertFalse(rule.matches("anything"))
    }

    // ── ReDoS guard: catastrophic-backtracking shapes are rejected ─────

    @Test fun `nested quantifier pattern is rejected before compile`() {
        // Classic ReDoS shape — without the guard, evaluation on a
        // mismatching input can take exponential time. isSafeRegexPattern
        // rejects it outright so the hot path never even compiles.
        assertFalse(WildcardRule.isSafeRegexPattern("(a+)+"))
        assertFalse(WildcardRule.isSafeRegexPattern("(a*)+"))
        assertFalse(WildcardRule.isSafeRegexPattern("(a+)*"))
        assertFalse(WildcardRule.isSafeRegexPattern("(\\d{1,3})+"))
    }

    @Test fun `ambiguous alternation in repeated group is rejected`() {
        assertFalse(WildcardRule.isSafeRegexPattern("(a|aa)+"))
        assertFalse(WildcardRule.isSafeRegexPattern("(foo|foob)*"))
    }

    @Test fun `legitimate phone-shaped regex still passes the guard`() {
        // The patterns users actually write must continue to work.
        assertTrue(WildcardRule.isSafeRegexPattern("^\\+?1?\\d{10}$"))
        assertTrue(WildcardRule.isSafeRegexPattern("^\\+1832555\\d{4}$"))
        assertTrue(WildcardRule.isSafeRegexPattern("832555"))
        assertTrue(WildcardRule.isSafeRegexPattern("^(212|310|415)\\d{7}$"))
    }

    @Test fun `nested-quantifier rule rejected end to end`() {
        // End-to-end: a hostile rule cannot even reach the regex engine,
        // regardless of the input.
        val hostile = wildcard(pattern = "(a+)+", isRegex = true)
        assertFalse(hostile.matches("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaab"))
    }

    // ── ASCII-only normalization (anti-spoof) ────────────────────────

    @Test fun `numberVariants strips Arabic-Indic digits as non-ASCII`() {
        // Arabic-Indic digits (U+0660..U+0669) are visually similar to
        // 0-9 but Char.isDigit() accepts them while filterAsciiDigits
        // correctly drops them. A spoofed number like "٨٣٢٥٥٥١٢٣٤"
        // must NOT produce +1-prefixed variants that could match US rules.
        val arabicIndic = "٨٣٢٥٥٥١٢٣٤"
        val variants = WildcardRule.numberVariants(arabicIndic)
        assertTrue(variants.none { it.startsWith("+1") })
        assertTrue(variants.none { it.all { c -> c in '0'..'9' } && it.length == 10 })
    }

    @Test fun `glob does not match Arabic-Indic spoofed number`() {
        val rule = wildcard(pattern = "+1832555*", isRegex = false)
        val arabicIndic = "٨٣٢٥٥٥١٢٣٤"
        assertFalse(rule.matches(arabicIndic))
    }

    // ── ReDoS resistance ────────────────────────────────────────────

    @Test(timeout = 1_000)
    fun `glob with many consecutive stars returns fast`() {
        // ~20 sequential glob stars compile to \d*\d*…\d* which backtracks for
        // 60+ seconds against a non-matching input. Collapsing consecutive stars
        // must make this return effectively instantly.
        val rule = wildcard(pattern = "*".repeat(20) + "5", isRegex = false)
        assertFalse(rule.matches("+12125551234"))
    }

    @Test fun `isSafeRegexPattern rejects a long chain of unbounded quantifiers`() {
        assertFalse(WildcardRule.isSafeRegexPattern("\\d*".repeat(20) + "a"))
    }

    @Test(timeout = 1_000)
    fun `regex with a long unbounded-quantifier chain does not hang`() {
        val rule = wildcard(pattern = "\\d*".repeat(20) + "a", isRegex = true)
        assertFalse(rule.matches("123456789012345"))
    }

    // ── Empty/blank handling ────────────────────────────────────────

    @Test fun `blank pattern matches nothing`() {
        assertFalse(wildcard(pattern = "", isRegex = false).matches("5551234"))
        assertFalse(wildcard(pattern = "   ", isRegex = true).matches("5551234"))
    }

    // ── Compiled-pattern memoization (hot-path perf) ──────────────────

    @Test fun `globToRegex escapes metacharacters and collapses stars`() {
        // Consecutive stars collapse to one \d* (ReDoS avoidance); '.' is
        // escaped; '?' becomes a single \d.
        assertEquals("\\d*5", WildcardRule.globToRegex("***5"))
        assertEquals("212\\.555\\d*", WildcardRule.globToRegex("212.555*"))
        assertEquals("\\d", WildcardRule.globToRegex("?"))
    }

    @Test fun `repeated matches on the same rule stay correct after caching`() {
        // The compiled Regex is memoized per pattern; a second call must
        // reuse it without changing the result.
        val rule = wildcard(pattern = "+1832555*", isRegex = false)
        repeat(3) { assertTrue(rule.matches("+18325551234")) }
        repeat(3) { assertFalse(rule.matches("+14155551234")) }
    }

    @Test fun `two rule instances with the same pattern share a compiled regex`() {
        // Rule entities are recreated on every rule-cache reload; the shared
        // companion cache means a given pattern compiles exactly once.
        val a = wildcard(pattern = "^\\+1832555\\d{4}$", isRegex = true)
        val b = wildcard(pattern = "^\\+1832555\\d{4}$", isRegex = true)
        assertTrue(a.matches("+18325551234"))
        assertTrue(b.matches("+18325551234"))
    }

    private fun wildcard(
        pattern: String,
        isRegex: Boolean,
    ): WildcardRule =
        WildcardRule(
            pattern = pattern,
            isRegex = isRegex,
            description = "",
            enabled = true,
        )
}
