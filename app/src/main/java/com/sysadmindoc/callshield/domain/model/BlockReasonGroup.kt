package com.sysadmindoc.callshield.domain.model

/**
 * The kinds of block Statistics counts over the last 30 days. Every reason
 * code belongs to exactly one group, so the groups add up to the log, and a
 * group opens the Blocked log filtered to its codes.
 */
enum class BlockReasonGroup(
    val key: String,
) {
    DATABASE("database"),

    /** The hot list: numbers trending in community reports right now. */
    COMMUNITY("community"),

    /** Something the user set up: their blocklist, patterns, keywords, quiet hours and modes. */
    YOUR_RULES("your_rules"),
    ML("ml"),
    CAMPAIGNS("campaigns"),
    OTHER("other"),
    ;

    val codes: Set<BlockReasonCode> get() = codesByGroup.getValue(this)

    companion object {
        fun of(code: BlockReasonCode): BlockReasonGroup =
            when (code) {
                BlockReasonCode.DATABASE, BlockReasonCode.DB_PREFIX_EXPANSION -> DATABASE

                BlockReasonCode.HOT_LIST -> COMMUNITY

                BlockReasonCode.USER_BLOCKLIST, BlockReasonCode.TEMPORARY_BLOCK, BlockReasonCode.SYSTEM_BLOCK_LIST,
                BlockReasonCode.PREFIX, BlockReasonCode.REGION_BLOCK, BlockReasonCode.WILDCARD, BlockReasonCode.HASH_WILDCARD,
                BlockReasonCode.KEYWORD, BlockReasonCode.CALLER_NAME_BLOCK, BlockReasonCode.CATEGORY_POLICY,
                BlockReasonCode.TIME_BLOCK, BlockReasonCode.HIDDEN_NUMBER, BlockReasonCode.CONTACTS_ONLY,
                BlockReasonCode.MEETING_MODE,
                -> YOUR_RULES

                BlockReasonCode.ML_SCORER -> ML

                BlockReasonCode.CAMPAIGN_BURST, BlockReasonCode.CAMPAIGN_RECORDER, BlockReasonCode.SMS_BURST -> CAMPAIGNS

                else -> OTHER
            }

        fun fromKey(key: String?): BlockReasonGroup? = entries.firstOrNull { it.key == key }

        /** Per-group totals from per-code log counts, with every group present. */
        fun totals(countsByStoredCode: Map<String, Int>): Map<BlockReasonGroup, Int> {
            val totals = entries.associateWith { 0 }.toMutableMap()
            countsByStoredCode.forEach { (stored, count) ->
                val group = of(BlockReasonCode.fromStored(stored))
                totals[group] = totals.getValue(group) + count
            }
            return totals
        }

        private val codesByGroup: Map<BlockReasonGroup, Set<BlockReasonCode>> =
            BlockReasonCode.entries.groupBy(::of).mapValues { it.value.toSet() }.let { grouped ->
                entries.associateWith { grouped[it].orEmpty() }
            }
    }
}
