package com.sysadmindoc.callshield.domain.model

/** Carrier-supplied identity signals available for a live screened call. */
data class CallerIdentity(
    val verificationStatus: Int? = null,
    val presentedName: String? = null,
)
