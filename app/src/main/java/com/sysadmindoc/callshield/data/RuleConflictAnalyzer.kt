package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.checker.CheckerPriority
import com.sysadmindoc.callshield.data.model.HashWildcardRule
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.SpamPrefix
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

enum class StandingRuleConflictKind {
    WHITELIST_VS_EXACT,
    WILDCARD_VS_EXACT,
    PREFIX_VS_HASH,
}

/** A conflict found by the standing audit, with the actual ladder winner. */
data class StandingRuleConflict(
    val kind: StandingRuleConflictKind,
    val sampleNumber: String,
    val winningRule: String,
    val winnerPriority: Int,
    val overriddenRule: String,
    val overriddenPriority: Int,
    val key: String,
)

data class ExistingBlockRules(
    val exactBlocks: List<SpamNumber>,
    val wildcardRules: List<WildcardRule>,
    val hashWildcardRules: List<HashWildcardRule>,
)

/**
 * Explains deterministic allow/block overlaps before a user saves a rule and
 * audits the persisted rule set after sync or CRUD changes.
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

    /**
     * Re-check the live rule tables as a single deterministic snapshot. The
     * audit deliberately reports only overlaps with different ladder winners:
     * a whitelist over an exact block, an exact block over a wildcard, and a
     * user hash range over downloaded prefix evidence.
     */
    fun audit(
        exactBlocks: List<SpamNumber>,
        wildcardRules: List<WildcardRule>,
        hashWildcardRules: List<HashWildcardRule>,
        whitelist: List<WhitelistEntry>,
        prefixes: List<SpamPrefix>,
        now: Long = System.currentTimeMillis(),
    ): List<StandingRuleConflict> {
        val calendar =
            java.util.Calendar
                .getInstance()
                .apply { timeInMillis = now }
        val activeExactBlocks =
            exactBlocks.filter { it.isUserBlocked && it.activeDecision(now) != null }
        val activeWildcards = wildcardRules.filter { it.enabled && it.schedule.isActiveAt(calendar) }
        val activeHashWildcards = hashWildcardRules.filter { it.enabled && it.schedule.isActiveAt(calendar) }
        val activeWhitelist = whitelist.filter { !it.isExpired(now) }
        val activePrefixes = prefixes.filter { it.prefix.isNotBlank() && (it.evidenceExpiresAt == null || it.evidenceExpiresAt > now) }
        val conflicts = mutableListOf<StandingRuleConflict>()

        for (entry in activeWhitelist) {
            activeExactBlocks
                .filter { comparableKey(it.number) == comparableKey(entry.number) }
                .forEach { block ->
                    conflicts +=
                        conflict(
                            kind = StandingRuleConflictKind.WHITELIST_VS_EXACT,
                            sampleNumber = block.number,
                            winningRule = "trusted ${entry.number}",
                            winnerPriority = CheckerPriority.MANUAL_WHITELIST,
                            overriddenRule = "exact block ${block.number}",
                            overriddenPriority = CheckerPriority.USER_BLOCKLIST,
                            leftKey = entry.number,
                            rightKey = block.number,
                        )
                }
        }

        for (wildcard in activeWildcards) {
            activeExactBlocks
                .filter { wildcard.matchesNow(it.number, calendar) }
                .forEach { block ->
                    conflicts +=
                        conflict(
                            kind = StandingRuleConflictKind.WILDCARD_VS_EXACT,
                            sampleNumber = block.number,
                            winningRule = "exact block ${block.number}",
                            winnerPriority = CheckerPriority.USER_BLOCKLIST,
                            overriddenRule = "wildcard ${wildcard.pattern}",
                            overriddenPriority = CheckerPriority.WILDCARD_RULE,
                            leftKey = wildcard.pattern,
                            rightKey = block.number,
                        )
                }
        }

        for (prefix in activePrefixes) {
            for (hashWildcard in activeHashWildcards) {
                val sample = prefixHashSample(prefix.prefix, hashWildcard.pattern) ?: continue
                conflicts +=
                    conflict(
                        kind = StandingRuleConflictKind.PREFIX_VS_HASH,
                        sampleNumber = sample,
                        winningRule = "hash range ${hashWildcard.pattern}",
                        winnerPriority = CheckerPriority.HASH_WILDCARD_RULE,
                        overriddenRule = "downloaded prefix ${prefix.prefix}",
                        overriddenPriority = CheckerPriority.PREFIX_MATCH,
                        leftKey = prefix.prefix,
                        rightKey = hashWildcard.pattern,
                    )
            }
        }

        return conflicts.distinctBy { it.key }.sortedWith(compareBy({ it.kind.ordinal }, { it.key }))
    }

    @Suppress("LongParameterList")
    private fun conflict(
        kind: StandingRuleConflictKind,
        sampleNumber: String,
        winningRule: String,
        winnerPriority: Int,
        overriddenRule: String,
        overriddenPriority: Int,
        leftKey: String,
        rightKey: String,
    ): StandingRuleConflict =
        StandingRuleConflict(
            kind = kind,
            sampleNumber = sampleNumber,
            winningRule = winningRule,
            winnerPriority = winnerPriority,
            overriddenRule = overriddenRule,
            overriddenPriority = overriddenPriority,
            key = "${kind.name}|${comparableKey(leftKey)}|${comparableKey(rightKey)}",
        )

    /**
     * Return a concrete number covered by both a downloaded prefix and a
     * length-locked hash rule. A prefix shorter than the hash pattern is
     * compatible when every fixed hash character agrees; `#` positions use
     * the prefix digit where available and zero thereafter.
     */
    private fun prefixHashSample(
        prefix: String,
        hashPattern: String,
    ): String? {
        val pattern = hashPattern.trim()
        if (pattern.isBlank()) return null
        return phoneVariants(prefix).firstNotNullOfOrNull { candidatePrefix ->
            if (candidatePrefix.length > pattern.length) return@firstNotNullOfOrNull null
            for (index in candidatePrefix.indices) {
                val patternCharacter = pattern[index]
                if (patternCharacter != '#' && patternCharacter != candidatePrefix[index]) {
                    return@firstNotNullOfOrNull null
                }
            }
            buildString(pattern.length) {
                for (index in pattern.indices) {
                    val patternCharacter = pattern[index]
                    append(
                        if (index < candidatePrefix.length) {
                            candidatePrefix[index]
                        } else if (patternCharacter == '#') {
                            '0'
                        } else {
                            patternCharacter
                        },
                    )
                }
            }
        }
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
