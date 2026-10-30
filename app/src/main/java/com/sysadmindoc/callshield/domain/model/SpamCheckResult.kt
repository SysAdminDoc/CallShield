package com.sysadmindoc.callshield.domain.model

data class SpamCheckResult(
    val isSpam: Boolean,
    val matchSource: String = "",
    val type: String = "",
    val description: String = "",
    val confidence: Int = 100,
    val reasonCode: BlockReasonCode = BlockReasonCode.fromMatchSource(matchSource),
    val ruleId: Long? = null,
    val screeningDiagnostics: ScreeningDiagnostics? = null,
    /** Raw signal tokens behind a scored block; [description] is display text. */
    val signals: List<String> = emptyList(),
    /** A live call's below-threshold heuristic score, shown only if the call goes on to ring. */
    val suspicion: CallSuspicion? = null,
)

/** A heuristic [score] too low to block, and the first [reason] behind it. */
data class CallSuspicion(
    val score: Int,
    val reason: String,
)
