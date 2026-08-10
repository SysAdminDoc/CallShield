package com.sysadmindoc.callshield.domain.model

data class SpamCheckResult(
    val isSpam: Boolean,
    val matchSource: String = "",
    val type: String = "",
    val description: String = "",
    val confidence: Int = 100,
    val reasonCode: BlockReasonCode = BlockReasonCode.fromMatchSource(matchSource),
    val ruleId: Long? = null,
)
