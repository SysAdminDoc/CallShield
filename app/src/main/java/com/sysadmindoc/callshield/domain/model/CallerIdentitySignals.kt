package com.sysadmindoc.callshield.domain.model

/** Do-Not-Originate list state supplied by a carrier or a trusted lookup. */
enum class DnoStatus {
    UNKNOWN,
    NOT_LISTED,
    LISTED,
    UNASSIGNED,
    ;

    companion object {
        fun fromWire(value: String?): DnoStatus =
            when (
                value
                    ?.trim()
                    ?.lowercase()
                    ?.replace('-', '_')
                    ?.replace(' ', '_')
            ) {
                "listed", "dno", "dno_listed", "do_not_originate", "blocked" -> LISTED
                "not_listed", "not_dno", "clear", "assigned" -> NOT_LISTED
                "unassigned", "unused", "reserved" -> UNASSIGNED
                else -> UNKNOWN
            }
    }
}

/** Coarse carrier line type. These are risk features, never allow decisions. */
enum class LineType {
    UNKNOWN,
    FIXED_LINE,
    MOBILE,
    VOIP,
    PREPAID,
    TOLL_FREE,
    PREMIUM_RATE,
    ;

    companion object {
        fun fromWire(value: String?): LineType =
            when (
                value
                    ?.trim()
                    ?.lowercase()
                    ?.replace('-', '_')
                    ?.replace(' ', '_')
            ) {
                "fixed", "fixed_line", "landline" -> FIXED_LINE
                "mobile", "cell", "cellular" -> MOBILE
                "voip", "voice_over_ip", "virtual" -> VOIP
                "prepaid", "pre_paid" -> PREPAID
                "toll_free", "tollfree", "freephone" -> TOLL_FREE
                "premium", "premium_rate", "premiumrate" -> PREMIUM_RATE
                else -> UNKNOWN
            }
    }
}

/** Rich Call Data carried inside PASSporT/RCD. URLs are retained only after HTTPS validation. */
data class RichCallData(
    val name: String? = null,
    val alternatePresentationNumber: String? = null,
    val iconUrl: String? = null,
    val inlineJCardPresent: Boolean = false,
    val jCardUrl: String? = null,
)

/** Bounded PASSporT claims decoded from a compact token. No cryptographic trust is implied. */
data class ParsedPassport(
    val typ: String,
    val algorithm: String,
    val certificateUrl: String,
    val issuedAtEpochSeconds: Long,
    val originTelephoneNumber: String,
    val destinationTelephoneNumbers: List<String>,
    val destinationUris: List<String>,
    val mediaKeyCount: Int = 0,
    val attestation: String? = null,
    val origid: String? = null,
    val richCallData: RichCallData? = null,
    /** True only because the compact token contained a non-empty signature segment. */
    val signaturePresent: Boolean = true,
)

/** A bounded risk adjustment and neutral labels for explainability. */
data class IdentityRiskAssessment(
    val probabilityAdjustment: Double,
    val evidenceLabels: List<String>,
)

/**
 * Calibrated identity features. Positive values increase spam probability; negative values
 * reduce it slightly. They are intentionally too weak to override explicit rules or a campaign
 * verdict, and carrier PASSporT metadata is never treated as a standalone allow.
 */
object CallerIdentitySignals {
    private const val VERIFICATION_STATUS_PASSED = 1
    private const val MIN_ADJUSTMENT = -0.15
    private const val MAX_ADJUSTMENT = 0.45

    fun assess(identity: CallerIdentity?): IdentityRiskAssessment {
        if (identity == null) return IdentityRiskAssessment(0.0, emptyList())

        var adjustment = 0.0
        val labels = mutableListOf<String>()
        val attestation = identity.passport?.attestation
        if (identity.verificationStatus == VERIFICATION_STATUS_PASSED) {
            when (attestation) {
                "A" -> {
                    adjustment -= 0.10
                    labels += "PASSporT attestation A metadata"
                }

                "B" -> {
                    adjustment -= 0.03
                    labels += "PASSporT attestation B metadata"
                }

                "C" -> {
                    adjustment += 0.05
                    labels += "PASSporT attestation C metadata"
                }
            }
        } else if (attestation != null) {
            labels += "PASSporT attestation $attestation metadata (carrier status not PASS)"
        }

        when (identity.dnoStatus) {
            DnoStatus.LISTED -> {
                adjustment += 0.22
                labels += "DNO-listed origin"
            }

            DnoStatus.UNASSIGNED -> {
                adjustment += 0.14
                labels += "unassigned origin"
            }

            else -> {
                Unit
            }
        }

        when (identity.lineType) {
            LineType.VOIP -> {
                adjustment += 0.04
                labels += "line type VoIP"
            }

            LineType.PREPAID -> {
                adjustment += 0.03
                labels += "line type prepaid"
            }

            LineType.PREMIUM_RATE -> {
                adjustment += 0.06
                labels += "line type premium-rate"
            }

            else -> {
                Unit
            }
        }

        if (identity.passport?.richCallData != null) {
            labels += "rich caller-data metadata (not a spam verdict)"
        }

        return IdentityRiskAssessment(
            probabilityAdjustment = adjustment.coerceIn(MIN_ADJUSTMENT, MAX_ADJUSTMENT),
            evidenceLabels = labels,
        )
    }

    fun adjustProbability(
        baseProbability: Double,
        identity: CallerIdentity?,
    ): Double {
        if (baseProbability < 0.0) return baseProbability
        return (baseProbability + assess(identity).probabilityAdjustment).coerceIn(0.0, 1.0)
    }

    fun describe(identity: CallerIdentity?): String =
        assess(identity)
            .evidenceLabels
            .joinToString(
                prefix = "Identity evidence: ",
                separator = "; ",
            ).takeIf { identity != null && assess(identity).evidenceLabels.isNotEmpty() }
            .orEmpty()
}
