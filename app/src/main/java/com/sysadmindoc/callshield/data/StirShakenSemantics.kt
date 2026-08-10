package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.domain.model.ParsedPassport

/**
 * Conservative display semantics for carrier caller-ID authentication.
 *
 * STIR/SHAKEN authenticates voice-routing metadata. It is useful signal, but
 * it is not a verdict that the caller is wanted, lawful, or non-spam.
 */
object StirShakenSemantics {
    const val VERIFICATION_STATUS_NOT_VERIFIED = 0
    const val VERIFICATION_STATUS_PASSED = 1
    const val VERIFICATION_STATUS_FAILED = 2

    fun forAndroidVerificationStatus(status: Int): StirShakenDisplay? =
        when (status) {
            VERIFICATION_STATUS_PASSED -> {
                StirShakenDisplay(
                    headline = "Carrier caller ID authentication passed.",
                    bullets =
                        listOf(
                            "Android exposes PASS/FAIL status, not the full A/B/C PASSporT attestation details.",
                            "This is caller ID authentication, not a verdict that the call is wanted or lawful.",
                            "Explicit user and system block rules stay ahead of this signal.",
                        ),
                )
            }

            VERIFICATION_STATUS_FAILED -> {
                StirShakenDisplay(
                    headline = "Carrier caller ID authentication failed.",
                    bullets =
                        listOf(
                            "The carrier could not authenticate this call's caller ID.",
                            "This usually means the displayed number may have been spoofed.",
                        ),
                )
            }

            VERIFICATION_STATUS_NOT_VERIFIED -> {
                StirShakenDisplay(
                    headline = "Carrier caller ID authentication was not available.",
                    bullets =
                        listOf(
                            "The carrier did not provide a PASS/FAIL authentication result for this call.",
                            "CallShield keeps evaluating the normal local rules.",
                        ),
                )
            }

            else -> {
                null
            }
        }

    fun forPassportAttestation(attestation: String?): StirShakenDisplay =
        when (attestation?.trim()?.uppercase()) {
            "A" -> {
                StirShakenDisplay(
                    headline = "Carrier attestation A: caller and number were authenticated.",
                    bullets =
                        listOf(
                            "The originating provider attested the caller is known and authorized to use this number.",
                            "This confirms caller ID metadata; it is not a verdict that the call is wanted or lawful.",
                            "Explicit user and system block rules stay ahead of this signal.",
                        ),
                )
            }

            "B" -> {
                StirShakenDisplay(
                    headline = "Carrier attestation B: caller was authenticated, number use was not fully confirmed.",
                    bullets =
                        listOf(
                            "The provider knows the caller but did not fully attest their right to use this number.",
                            "CallShield should show this as partial authentication, not as caller approval.",
                            "Explicit user and system block rules stay ahead of this signal.",
                        ),
                )
            }

            "C" -> {
                StirShakenDisplay(
                    headline = "Carrier attestation C: only the network gateway was authenticated.",
                    bullets =
                        listOf(
                            "The provider could identify the gateway that accepted the call, not the caller or number.",
                            "This is weak authentication and should not override local detection.",
                            "Explicit user and system block rules stay ahead of this signal.",
                        ),
                )
            }

            else -> {
                StirShakenDisplay(
                    headline = "Carrier attestation unavailable.",
                    bullets =
                        listOf(
                            "No A/B/C PASSporT attestation was available for display.",
                            "CallShield keeps evaluating the normal local rules.",
                        ),
                )
            }
        }

    /** Structural PASSporT/RCD copy; it deliberately does not claim signature verification. */
    fun forPassportMetadata(passport: ParsedPassport): StirShakenDisplay {
        val claims = passport.attestation?.let { "Attestation $it was present in the token." }
        val rcd = passport.richCallData?.let { "Rich caller-data metadata was supplied." }
        return StirShakenDisplay(
            headline = "PASSporT caller-ID metadata was decoded.",
            bullets =
                listOfNotNull(
                    claims,
                    rcd,
                    "CallShield checked the token structure but did not verify its ES256 signature or fetch its certificate.",
                    "Caller-ID metadata is not a verdict that the call is wanted or lawful.",
                ),
        )
    }
}

data class StirShakenDisplay(
    val headline: String,
    val bullets: List<String>,
) {
    val description: String = bullets.joinToString(" ")
}
