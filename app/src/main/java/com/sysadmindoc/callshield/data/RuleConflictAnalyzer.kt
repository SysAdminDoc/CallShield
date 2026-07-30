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
    ): RuleConflict? = activeWhitelistMatch(number, whitelist, now)?.toConflict(overriddenRule = RuleConflictRule.EXACT_BLOCK)

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
            .firstOrNull { entry -> phoneVariants(entry.number).any { candidate.matches(it) } }
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
        val variants = phoneVariants(number)
        val overriddenRule =
            when {
                rules.exactBlocks.any {
                    it.isUserBlocked && it.activeDecision(now) != null && comparableKey(it.number) == comparableKey(number)
                } -> {
                    RuleConflictRule.EXACT_BLOCK
                }

                rules.wildcardRules.any { rule -> rule.enabled && variants.any { rule.matches(it) } } -> {
                    RuleConflictRule.WILDCARD_BLOCK
                }

                rules.hashWildcardRules.any { rule -> rule.enabled && variants.any { rule.matches(it) } } -> {
                    RuleConflictRule.RANGE_BLOCK
                }

                else -> {
                    return null
                }
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
    ): WhitelistEntry? =
        whitelist.firstOrNull {
            !it.isExpired(now) && comparableKey(it.number) == comparableKey(number)
        }

    /**
     * Comparison key that bridges the stored E.164 form and hand-typed input.
     * Whitelist/block rows are persisted canonicalized (`+12125551234`), but
     * the editor hands this analyzer whatever the user typed (`2125551234`) —
     * a plain string compare between the two silently hid real conflicts, so
     * the advisory stayed quiet while the live ladder overrode the rule.
     */
    private fun comparableKey(raw: String): String {
        val digits = raw.filter { it in '0'..'9' }
        return if (digits.length == 11 && digits.startsWith("1")) digits.substring(1) else digits
    }

    /** The forms an entry might be stored or typed in, for pattern matching. */
    private fun phoneVariants(raw: String): List<String> {
        val normalized = normalizePhoneNumber(raw)
        val digits = normalized.filter { it in '0'..'9' }
        val variants = mutableListOf(normalized)
        if (digits.isNotEmpty()) {
            variants += digits
            if (digits.length == 11 && digits.startsWith("1")) variants += digits.substring(1)
            if (digits.length == 10) variants += "+1$digits"
        }
        return variants.distinct()
    }

    private fun WhitelistEntry.toConflict(overriddenRule: RuleConflictRule): RuleConflict =
        RuleConflict(
            winner = if (isEmergency) RuleConflictWinner.EMERGENCY_ALLOW else RuleConflictWinner.WHITELIST,
            sampleNumber = number,
            overriddenRule = overriddenRule,
        )
}
