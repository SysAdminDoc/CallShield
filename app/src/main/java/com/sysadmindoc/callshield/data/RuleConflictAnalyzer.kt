package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.model.HashWildcardRule
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.WhitelistEntry
import com.sysadmindoc.callshield.data.model.WildcardRule

enum class RuleConflictWinner {
    EMERGENCY_ALLOW,
    WHITELIST,
}

enum class RuleConflictRule {
    EXACT_BLOCK,
    WILDCARD_BLOCK,
    RANGE_BLOCK,
}

data class RuleConflict(
    val winner: RuleConflictWinner,
    val sampleNumber: String,
    val overriddenRule: RuleConflictRule,
)

data class ExistingBlockRules(
    val exactBlocks: List<SpamNumber>,
    val wildcardRules: List<WildcardRule>,
    val hashWildcardRules: List<HashWildcardRule>,
)

/**
 * Explains deterministic allow/block overlaps before a user saves a rule.
 *
 * Manual whitelist entries run at priority 10,000, above exact blocks and
 * both wildcard types. This helper mirrors those comparisons without
 * invoking Android or the live checker pipeline, keeping editor feedback
 * immediate and unit-testable.
 */
object RuleConflictAnalyzer {
    fun forExactBlock(
        number: String,
        whitelist: List<WhitelistEntry>,
        now: Long = System.currentTimeMillis(),
    ): RuleConflict? =
        activeWhitelistMatch(number, whitelist, now)?.toConflict(overriddenRule = RuleConflictRule.EXACT_BLOCK)

    fun forWildcardBlock(
        pattern: String,
        isRegex: Boolean,
        whitelist: List<WhitelistEntry>,
        now: Long = System.currentTimeMillis(),
    ): RuleConflict? {
        val candidate = WildcardRule(pattern = pattern.trim(), isRegex = isRegex)
        return whitelist
            .asSequence()
            .filter { !it.isExpired(now) }
            .firstOrNull { candidate.matches(it.number) }
            ?.toConflict(overriddenRule = RuleConflictRule.WILDCARD_BLOCK)
    }

    fun forHashWildcardBlock(
        pattern: String,
        whitelist: List<WhitelistEntry>,
        now: Long = System.currentTimeMillis(),
    ): RuleConflict? =
        whitelist
            .asSequence()
            .filter { !it.isExpired(now) }
            .firstOrNull { HashWildcardMatcher.matchesWithVariants(pattern.trim(), it.number) }
            ?.toConflict(overriddenRule = RuleConflictRule.RANGE_BLOCK)

    fun forWhitelist(
        number: String,
        emergency: Boolean,
        rules: ExistingBlockRules,
        now: Long = System.currentTimeMillis(),
    ): RuleConflict? {
        val normalized = normalizePhoneNumber(number)
        val overriddenRule =
            when {
                rules.exactBlocks.any {
                    it.isUserBlocked && it.activeDecision(now) != null && normalizePhoneNumber(it.number) == normalized
                } -> {
                    RuleConflictRule.EXACT_BLOCK
                }

                rules.wildcardRules.any { it.enabled && it.matches(normalized) } -> {
                    RuleConflictRule.WILDCARD_BLOCK
                }

                rules.hashWildcardRules.any { it.enabled && it.matches(normalized) } -> {
                    RuleConflictRule.RANGE_BLOCK
                }

                else -> return null
            }
        return RuleConflict(
            winner = if (emergency) RuleConflictWinner.EMERGENCY_ALLOW else RuleConflictWinner.WHITELIST,
            sampleNumber = normalized,
            overriddenRule = overriddenRule,
        )
    }

    private fun activeWhitelistMatch(
        number: String,
        whitelist: List<WhitelistEntry>,
        now: Long,
    ): WhitelistEntry? {
        val normalized = normalizePhoneNumber(number)
        return whitelist.firstOrNull {
            !it.isExpired(now) && normalizePhoneNumber(it.number) == normalized
        }
    }

    private fun WhitelistEntry.toConflict(overriddenRule: RuleConflictRule): RuleConflict =
        RuleConflict(
            winner = if (isEmergency) RuleConflictWinner.EMERGENCY_ALLOW else RuleConflictWinner.WHITELIST,
            sampleNumber = number,
            overriddenRule = overriddenRule,
        )
}
