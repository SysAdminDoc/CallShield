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

/**
 * One piece of carrier identity evidence behind a risk adjustment. The
 * domain layer names it; whoever shows it words it in the app language.
 */
sealed interface IdentityEvidence {
    /** A PASSporT attestation level, and whether the carrier's own check passed. */
    data class Attestation(
        val level: String,
        val carrierPassed: Boolean,
    ) : IdentityEvidence

    data object DnoListed : IdentityEvidence

    data object UnassignedOrigin : IdentityEvidence

    data object VoipLine : IdentityEvidence

    data object PrepaidLine : IdentityEvidence

    data object PremiumRateLine : IdentityEvidence

    /** Rich call data was present; neutral, never a verdict by itself. */
    data object RichCallData : IdentityEvidence
}

/** A bounded risk adjustment and the neutral evidence behind it. */
data class IdentityRiskAssessment(
    val probabilityAdjustment: Double,
    val evidence: List<IdentityEvidence>,
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
        val evidence = mutableListOf<IdentityEvidence>()
        val attestation = identity.passport?.attestation
        if (identity.verificationStatus == VERIFICATION_STATUS_PASSED) {
            when (attestation) {
                "A" -> {
                    adjustment -= 0.10
                    evidence += IdentityEvidence.Attestation("A", carrierPassed = true)
                }

                "B" -> {
                    adjustment -= 0.03
                    evidence += IdentityEvidence.Attestation("B", carrierPassed = true)
                }

                "C" -> {
                    adjustment += 0.05
                    evidence += IdentityEvidence.Attestation("C", carrierPassed = true)
                }
            }
        } else if (attestation != null) {
            evidence += IdentityEvidence.Attestation(attestation, carrierPassed = false)
        }

        when (identity.dnoStatus) {
            DnoStatus.LISTED -> {
                adjustment += 0.22
                evidence += IdentityEvidence.DnoListed
            }

            DnoStatus.UNASSIGNED -> {
                adjustment += 0.14
                evidence += IdentityEvidence.UnassignedOrigin
            }

            else -> {
                Unit
            }
        }

        when (identity.lineType) {
            LineType.VOIP -> {
                adjustment += 0.04
                evidence += IdentityEvidence.VoipLine
            }

            LineType.PREPAID -> {
                adjustment += 0.03
                evidence += IdentityEvidence.PrepaidLine
            }

            LineType.PREMIUM_RATE -> {
                adjustment += 0.06
                evidence += IdentityEvidence.PremiumRateLine
            }

            else -> {
                Unit
            }
        }

        if (identity.passport?.richCallData != null) {
            evidence += IdentityEvidence.RichCallData
        }

        return IdentityRiskAssessment(
            probabilityAdjustment = adjustment.coerceIn(MIN_ADJUSTMENT, MAX_ADJUSTMENT),
            evidence = evidence,
        )
    }

    fun adjustProbability(
        baseProbability: Double,
        identity: CallerIdentity?,
    ): Double {
        if (baseProbability < 0.0) return baseProbability
        return (baseProbability + assess(identity).probabilityAdjustment).coerceIn(0.0, 1.0)
    }
}
