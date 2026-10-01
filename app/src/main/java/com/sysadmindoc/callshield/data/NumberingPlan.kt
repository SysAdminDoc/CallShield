package com.sysadmindoc.callshield.data

/** Numbering plan for the NANP-only detection rules. */
enum class NumberingPlan {
    NANP,
    OTHER,
    UNREADABLE,
    ;

    companion object {
        /**
         * A bare number is one libphonenumber couldn't place in [homeRegionIso].
         * Ten digits, or 1 and ten, reads as North American only on a phone in
         * that plan (or with no known region); elsewhere it's some other plan's
         * number, and the NANP range rules say nothing about it.
         */
        fun from(
            number: String,
            homeRegionIso: String? = null,
        ): NumberingPlan {
            val compact = number.filterNot { it == ' ' || it == '-' || it == '(' || it == ')' || it == '.' }
            val international = compact.startsWith('+')
            val digits = if (international) compact.drop(1) else compact
            if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return UNREADABLE
            if (international) {
                if (digits.length !in 2..15 || digits.startsWith('0')) return UNREADABLE
                return if (digits.startsWith('1')) {
                    if (digits.length == 11) NANP else UNREADABLE
                } else {
                    OTHER
                }
            }
            return when {
                digits.length != 10 && !(digits.length == 11 && digits.startsWith('1')) -> UNREADABLE
                PhoneIdentityCanonicalizer.readsBareDigitsAsNanp(homeRegionIso) -> NANP
                else -> OTHER
            }
        }
    }
}
