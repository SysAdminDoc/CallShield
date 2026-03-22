package com.sysadmindoc.callshield.data

/**
 * Formats phone numbers for display throughout the app.
 * +12125551234 -> (212) 555-1234
 * 2125551234 -> (212) 555-1234
 * International numbers pass through with just + prefix formatting.
 */
object PhoneFormatter {

    fun format(number: String): String {
        val digits = number.filter { it.isDigit() }

        // US/CA: 10 digits or 11 starting with 1
        val usDigits = when {
            digits.length == 11 && digits.startsWith("1") -> digits.substring(1)
            digits.length == 10 -> digits
            else -> null
        }

        if (usDigits != null) {
            val area = usDigits.substring(0, 3)
            val exchange = usDigits.substring(3, 6)
            val subscriber = usDigits.substring(6, 10)
            return "($area) $exchange-$subscriber"
        }

        // Short codes (5-6 digits)
        if (digits.length in 5..6) return digits

        // International: just add + and group
        if (number.startsWith("+") && digits.length > 6) {
            return "+$digits"
        }

        return number
    }

    fun formatWithCountryCode(number: String): String {
        val digits = number.filter { it.isDigit() }
        val usDigits = when {
            digits.length == 11 && digits.startsWith("1") -> digits.substring(1)
            digits.length == 10 -> digits
            else -> null
        }
        if (usDigits != null) {
            return "+1 (${usDigits.substring(0, 3)}) ${usDigits.substring(3, 6)}-${usDigits.substring(6)}"
        }
        return if (number.startsWith("+")) number else "+$number"
    }
}
