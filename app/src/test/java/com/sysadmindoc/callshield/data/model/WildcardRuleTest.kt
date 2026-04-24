package com.sysadmindoc.callshield.data.model

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

    // ── Empty/blank handling ────────────────────────────────────────

    @Test fun `blank pattern matches nothing`() {
        assertFalse(wildcard(pattern = "", isRegex = false).matches("5551234"))
        assertFalse(wildcard(pattern = "   ", isRegex = true).matches("5551234"))
    }

    private fun wildcard(pattern: String, isRegex: Boolean): WildcardRule =
        WildcardRule(
            pattern = pattern,
            isRegex = isRegex,
            description = "",
            enabled = true,
        )
}
