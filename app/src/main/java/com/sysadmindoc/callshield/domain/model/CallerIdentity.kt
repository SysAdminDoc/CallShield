package com.sysadmindoc.callshield.domain.model

/** Carrier-supplied identity signals available for a live screened call. */
data class CallerIdentity(
    val verificationStatus: Int? = null,
    val presentedName: String? = null,
    /** Structurally parsed PASSporT metadata; the local parser does not verify its signature. */
    val passport: ParsedPassport? = null,
    val dnoStatus: DnoStatus = DnoStatus.UNKNOWN,
    val lineType: LineType = LineType.UNKNOWN,
)
