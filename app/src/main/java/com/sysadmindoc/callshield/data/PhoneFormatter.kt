package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.util.filterAsciiDigits

/**
 * Formats phone numbers for display throughout the app.
 * +12125551234 -> (212) 555-1234
 * 2125551234 -> (212) 555-1234
 * International numbers pass through with just + prefix formatting.
 */
object PhoneFormatter {
    // Unicode bidirectional isolate controls. Wrapping a phone number (an LTR
    // run of digits, spaces, parens and dashes) in FSI…PDI keeps it displayed
    // left-to-right and correctly ordered when it is embedded inside an RTL
    // sentence (Arabic/Hebrew/Farsi/Urdu). Without isolation the digits and
    // punctuation reorder and garble. The controls are invisible in LTR UIs.
    private const val FIRST_STRONG_ISOLATE = '⁦'
    private const val POP_DIRECTIONAL_ISOLATE = '⁩'

    /**
     * Wrap [text] in a bidirectional isolate so an embedded LTR run (like a
     * phone number) renders correctly inside RTL surrounding text. Safe to use
     * anywhere; a no-op visually in LTR layouts. Empty input is returned as-is.
     */
    fun isolate(text: String): String =
        if (text.isEmpty()) text else "$FIRST_STRONG_ISOLATE$text$POP_DIRECTIONAL_ISOLATE"

    /**
     * [format] the number and wrap it in a bidi isolate for safe display inside
     * localized (possibly RTL) sentences — notifications, overlays, log rows.
     */
    fun formatIsolated(number: String): String = isolate(format(number))

    fun format(number: String): String {
        val digits = filterAsciiDigits(number)

        // US/CA: 10 digits or 11 starting with 1
        val usDigits =
            when {
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
        val digits = filterAsciiDigits(number)
        val usDigits =
            when {
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
