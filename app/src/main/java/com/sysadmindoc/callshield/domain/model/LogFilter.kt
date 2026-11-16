package com.sysadmindoc.callshield.domain.model

/**
 * What the Blocked log lists: calls (1), texts (0) or both (null), the stored
 * reason codes to keep (null keeps every reason), and nothing older than [since].
 */
data class LogFilter(
    val isCall: Int? = null,
    val reasonCodes: List<String>? = null,
    val since: Long = 0L,
)
