package com.sysadmindoc.callshield.domain.model

/**
 * Stable, persisted decision identifiers. Keep [wireValue] backward
 * compatible: it is stored in the call-log reasonCode column and used by
 * exports, while human-readable labels remain localized UI concerns.
 */
enum class BlockReasonCode(
    val wireValue: String,
) {
    UNKNOWN("unknown"),
    EMERGENCY_FLOOR("emergency_floor"),
    OTP_FLOOR("otp_floor"),
    EMERGENCY_CONTACT("emergency_contact"),
    MANUAL_WHITELIST("manual_whitelist"),
    CONTACT_WHITELIST("contact_whitelist"),
    CONTACTS_ONLY("contacts_only"),
    STIR_SHAKEN_TRUSTED("stir_shaken_trusted"),
    STIR_SHAKEN_FAILED("stir_shaken_failed"),
    TEMPORARY_ALLOW("temporary_allow"),
    TEMPORARY_BLOCK("temporary_block"),
    SYSTEM_BLOCK_LIST("system_block_list"),
    USER_BLOCKLIST("user_blocklist"),
    HIDDEN_NUMBER("hidden_number"),
    DATABASE("database"),
    DB_PREFIX_EXPANSION("db_prefix_expansion"),
    PREFIX("prefix"),
    WILDCARD("wildcard"),
    HASH_WILDCARD("hash_wildcard"),
    RECENTLY_DIALED("recently_dialed"),
    ANSWERED_CALLER("answered_caller"),
    EMERGENCY_CALLBACK("emergency_callback"),
    REPEATED_URGENT("repeated_urgent"),
    CALLER_NAME_TRUST("caller_name_trust"),
    CALLER_NAME_BLOCK("caller_name"),
    REGION_BLOCK("region_block"),
    CAMPAIGN_RECORDER("campaign_recorder"),
    TIME_BLOCK("time_block"),
    FREQUENCY("frequency"),
    HEURISTIC("heuristic"),
    CAMPAIGN_BURST("campaign_burst"),
    ML_SCORER("ml_scorer"),
    HOT_LIST("hot_list"),
    SPAM_DOMAIN("spam_domain"),
    PUSH_ALERT("push_alert"),
    SMS_CONTEXT("sms_context"),
    SMS_BURST("sms_burst"),
    KEYWORD("keyword"),
    SMS_CONTENT("sms_content"),
    RCS_FILTER("rcs_filter"),
    CATEGORY_POLICY("category_policy"),
    PIPELINE_DIAGNOSTIC("pipeline_diagnostic"),

    ;

    companion object {
        private val byWireValue = entries.associateBy { it.wireValue }

        /** Map a live checker/source identifier to its stable persisted code. */
        fun fromMatchSource(source: String?): BlockReasonCode {
            val token = source?.trim()?.lowercase().orEmpty()
            return when {
                token.startsWith("rcs_") -> RCS_FILTER
                token.startsWith("category_policy:") -> CATEGORY_POLICY
                token.startsWith("database") -> DATABASE
                token.startsWith("db_prefix") -> DB_PREFIX_EXPANSION
                token.startsWith("hash_wildcard") -> HASH_WILDCARD
                token.startsWith("wildcard") -> WILDCARD
                token.startsWith("prefix") -> PREFIX
                token.startsWith("heuristic") -> HEURISTIC
                token.startsWith("campaign_burst") || token.startsWith("hot_campaign") -> CAMPAIGN_BURST
                token.startsWith("ml_scorer") -> ML_SCORER
                token.startsWith("known_spam_domain") || token.startsWith("local_spam_domain") -> SPAM_DOMAIN
                token.startsWith("spam_keywords") || token.startsWith("keyword") -> KEYWORD
                token.startsWith("sms_content") -> SMS_CONTENT
                token.startsWith("sms_burst") -> SMS_BURST
                else -> byWireValue[token] ?: UNKNOWN
            }
        }

        /** Read both current codes and legacy free-text rows without throwing. */
        fun fromStored(value: String?): BlockReasonCode = fromMatchSource(value)
    }
}
