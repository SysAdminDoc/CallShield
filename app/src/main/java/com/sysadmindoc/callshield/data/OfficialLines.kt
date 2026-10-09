package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.util.filterAsciiDigits

/**
 * Published customer-service lines of organizations scammers impersonate.
 *
 * FCC complaints name these numbers because spoofed calls show them as
 * caller ID, so the shared database holds most of them as robocall rows
 * with current evidence (39 of these 50 in database v52). A call the
 * carrier verified (STIR/SHAKEN PASSED) comes from the number's real owner,
 * so [com.sysadmindoc.callshield.data.checker.StirShakenTrustChecker] lets
 * it ring whatever the row's age. An unverified call still blocks on the
 * row, and its reason names the organization.
 *
 * `scripts/probe_live_sources.py` samples the same list (`BUSINESS_LINES`);
 * `test_probe_live_sources.py` keeps the two in step.
 */
internal object OfficialLines {
    private val lines: Map<String, String> =
        mapOf(
            "+18002752273" to "Apple Support",
            "+18008291040" to "IRS",
            "+18007721213" to "Social Security",
            "+18006334227" to "Medicare",
            "+18002758777" to "USPS",
            "+18007425877" to "UPS",
            "+18004633339" to "FedEx",
            "+18002255345" to "DHL Express",
            "+18882804331" to "Amazon",
            "+18004321000" to "Bank of America",
            "+18009359935" to "Chase",
            "+18008693557" to "Wells Fargo",
            "+18009505114" to "Citi",
            "+18002274825" to "Capital One",
            "+18003472683" to "Discover",
            "+18005284800" to "American Express",
            "+18882211161" to "PayPal",
            "+18009346489" to "Xfinity",
            "+18009220204" to "Verizon",
            "+18003310500" to "AT&T",
            "+18009378997" to "T-Mobile",
            "+18002211212" to "Delta",
            "+18008648331" to "United Airlines",
            "+18004337300" to "American Airlines",
            "+18004359792" to "Southwest",
            "+18006427676" to "Microsoft",
            "+18006249897" to "Dell",
            "+18004746836" to "HP",
            "+18882378289" to "Best Buy",
            "+18009256278" to "Walmart",
            "+18005913869" to "Target",
            "+18004663337" to "Home Depot",
            "+18004456937" to "Lowe's",
            "+18007742678" to "Costco",
            "+18007467287" to "CVS",
            "+18009254733" to "Walgreens",
            "+18002077847" to "GEICO",
            "+18007828332" to "State Farm",
            "+18007764737" to "Progressive",
            "+18002557828" to "Allstate",
            "+18005315000" to "DirecTV",
            "+18003333474" to "DISH",
            "+18883973742" to "Experian",
            "+18009168800" to "TransUnion",
            "+18773824357" to "FTC",
            "+18008722657" to "U.S. Bank",
            "+18887622265" to "PNC",
            "+18887519000" to "TD Bank",
            "+18008472911" to "Visa",
            "+18006278372" to "Mastercard",
        )

    /**
     * The organization whose published line [number] is, or null. A bare
     * ten-digit number, or eleven digits starting with 1, reads as North
     * American; every listed line is.
     */
    fun organization(number: String): String? {
        val digits = filterAsciiDigits(number)
        val e164 =
            when {
                number.trimStart().startsWith("+") -> "+$digits"
                digits.length == NANP_DIGITS -> "+1$digits"
                digits.length == NANP_DIGITS + 1 && digits.startsWith("1") -> "+$digits"
                else -> return null
            }
        return lines[e164]
    }

    private const val NANP_DIGITS = 10
}
