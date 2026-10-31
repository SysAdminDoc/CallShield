package com.sysadmindoc.callshield.domain.model

data class SyncResult(
    val success: Boolean,
    val message: String,
    val warning: Boolean = false,
    val shouldRetry: Boolean = false,
    /** Strict offline mode is why nothing was fetched. Not a failure and not stale data. */
    val offline: Boolean = false,
)
