package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.util.filterAsciiDigits

/**
 * Numbers that must remain reachable even when another detection rule says
 * otherwise. Keep this deliberately narrow: it protects recognized emergency
 * and public-safety short codes, not arbitrary numbers that happen to end in
 * the same digits.
 */
object EmergencyNumberFloor {
    const val MATCH_SOURCE = "emergency_floor"

    private const val NANP_COUNTRY_PREFIX = "1"

    private val protectedShortCodes =
        setOf(
            "000",
            "111",
            "112",
            "118",
            "119",
            "911",
            "999",
        )

    fun isProtected(number: String): Boolean {
        val digits = filterAsciiDigits(number)
        return digits in protectedShortCodes ||
            (digits.length == 4 && digits.startsWith(NANP_COUNTRY_PREFIX) && digits.drop(1) in protectedShortCodes)
    }
}
