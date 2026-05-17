package com.sysadmindoc.callshield.domain.model

data class SyncResult(
    val success: Boolean,
    val message: String,
    val warning: Boolean = false,
    val shouldRetry: Boolean = false,
)
