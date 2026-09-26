package com.sysadmindoc.callshield.data

/** Numbering plan for the NANP-only detection rules. */
enum class NumberingPlan {
    NANP,
    OTHER,
    UNREADABLE,
    ;

    companion object {
        fun from(number: String): NumberingPlan {
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
            return if (digits.length == 10 || (digits.length == 11 && digits.startsWith('1'))) {
                NANP
            } else {
                UNREADABLE
            }
        }
    }
}
