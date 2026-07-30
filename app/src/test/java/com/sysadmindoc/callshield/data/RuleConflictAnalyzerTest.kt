package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.model.HashWildcardRule
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.WhitelistEntry
import com.sysadmindoc.callshield.data.model.WildcardRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleConflictAnalyzerTest {
    @Test
    fun `emergency allow wins over an exact block`() {
        val conflict =
            RuleConflictAnalyzer.forExactBlock(
                number = "+12125550100",
                whitelist = listOf(WhitelistEntry(number = "+12125550100", isEmergency = true)),
            )

        assertEquals(RuleConflictWinner.EMERGENCY_ALLOW, conflict?.winner)
        assertEquals(RuleConflictRule.EXACT_BLOCK, conflict?.overriddenRule)
    }

    @Test
    fun `whitelist match is found through number normalization`() {
        val conflict =
            RuleConflictAnalyzer.forExactBlock(
                number = "+1 (212) 555-0100",
                whitelist = listOf(WhitelistEntry(number = "+12125550100")),
            )

        assertEquals(RuleConflictWinner.WHITELIST, conflict?.winner)
    }

    @Test
    fun `hand-typed national number matches the stored E164 whitelist row`() {
        // Whitelist rows are stored canonicalized (+1...); the editor passes
        // whatever the user typed. "2125550100" must still surface the
        // conflict — the live ladder WILL let the whitelist override.
        val conflict =
            RuleConflictAnalyzer.forExactBlock(
                number = "2125550100",
                whitelist = listOf(WhitelistEntry(number = "+12125550100")),
            )

        assertEquals(RuleConflictWinner.WHITELIST, conflict?.winner)
    }

    @Test
    fun `bare-digit wildcard pattern still flags a stored E164 whitelist entry`() {
        val conflict =
            RuleConflictAnalyzer.forWildcardBlock(
                pattern = "212555*",
                isRegex = false,
                whitelist = listOf(WhitelistEntry(number = "+12125550100")),
            )

        assertEquals(RuleConflictRule.WILDCARD_BLOCK, conflict?.overriddenRule)
    }

    @Test
    fun `wildcard block reports the whitelisted number it cannot override`() {
        val conflict =
            RuleConflictAnalyzer.forWildcardBlock(
                pattern = "+1212*",
                isRegex = false,
                whitelist = listOf(WhitelistEntry(number = "+12125550100")),
            )

        assertEquals("+12125550100", conflict?.sampleNumber)
        assertEquals(RuleConflictRule.WILDCARD_BLOCK, conflict?.overriddenRule)
    }

    @Test
    fun `range block ignores non-overlapping whitelist entries`() {
        val conflict =
            RuleConflictAnalyzer.forHashWildcardBlock(
                pattern = "+1212555####",
                whitelist = listOf(WhitelistEntry(number = "+13125550100")),
            )

        assertNull(conflict)
    }

    @Test
    fun `new whitelist names an existing wildcard it overrides`() {
        val conflict =
            RuleConflictAnalyzer.forWhitelist(
                number = "+12125550100",
                emergency = false,
                rules =
                    ExistingBlockRules(
                        exactBlocks = emptyList(),
                        wildcardRules = listOf(WildcardRule(pattern = "+1212*")),
                        hashWildcardRules = emptyList(),
                    ),
            )

        assertEquals(RuleConflictWinner.WHITELIST, conflict?.winner)
        assertEquals(RuleConflictRule.WILDCARD_BLOCK, conflict?.overriddenRule)
    }

    @Test
    fun `expired rules and non-overlapping rules do not conflict`() {
        val now = 2_000L
        assertNull(
            RuleConflictAnalyzer.forExactBlock(
                number = "+12125550100",
                whitelist = listOf(WhitelistEntry(number = "+12125550100", expiresAt = now - 1)),
                now = now,
            ),
        )
        assertNull(
            RuleConflictAnalyzer.forWhitelist(
                number = "+12125550100",
                emergency = false,
                rules =
                    ExistingBlockRules(
                        exactBlocks =
                            listOf(
                                SpamNumber(
                                    number = "+13125550100",
                                    type = "manual",
                                    source = "user",
                                    isUserBlocked = true,
                                ),
                            ),
                        wildcardRules = emptyList(),
                        hashWildcardRules = listOf(HashWildcardRule(pattern = "+1312555####")),
                    ),
                now = now,
            ),
        )
    }

    @Test
    fun `whitelisting a hand-typed number sees the stored E164 user block`() {
        // The user types "2125550100"; the block is stored as "+12125550100".
        // Without the comparableKey bridge the advisory silently disappears.
        val conflict =
            RuleConflictAnalyzer.forWhitelist(
                number = "2125550100",
                emergency = false,
                rules =
                    ExistingBlockRules(
                        exactBlocks =
                            listOf(
                                SpamNumber(
                                    number = "+12125550100",
                                    type = "manual",
                                    source = "user",
                                    isUserBlocked = true,
                                ),
                            ),
                        wildcardRules = emptyList(),
                        hashWildcardRules = emptyList(),
                    ),
            )

        assertEquals(RuleConflictRule.EXACT_BLOCK, conflict?.overriddenRule)
    }

    @Test
    fun `an expired temporary block is not reported as a whitelist conflict`() {
        val now = 10_000_000L
        val conflict =
            RuleConflictAnalyzer.forWhitelist(
                number = "+12125550100",
                emergency = false,
                rules =
                    ExistingBlockRules(
                        exactBlocks =
                            listOf(
                                SpamNumber(
                                    number = "+12125550100",
                                    type = "manual",
                                    source = "user",
                                    isUserBlocked = true,
                                    expiresAt = now - 1,
                                ),
                            ),
                        wildcardRules = emptyList(),
                        hashWildcardRules = emptyList(),
                    ),
                now = now,
            )

        assertNull(conflict)
    }
}
