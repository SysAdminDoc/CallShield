package com.sysadmindoc.callshield.data

/**
 * A call-forwarding code with a number in it, the way account-takeover scams
 * get a victim to send their calls, and with them the voice codes banks and
 * apps place, to the scammer's phone: "dial *21*+15551234567#", or in India
 * "dial *401*9876543210".
 *
 * Counted: the GSM codes of 3GPP TS 22.030 that turn forwarding on with a
 * number (21 for every call, 67 when busy, 61 when unanswered, 62 when
 * unreachable, 002 and 004 for all of those, after one star or two), Verizon's
 * *72 and *71 before a number, and Jio's *401*.
 *
 * Not counted: the codes that check or cancel forwarding (*#21#, #21#,
 * ##002#, Verizon's *73), which carriers and banks tell people to dial; US
 * *67 before a number, which hides caller ID for one call; and a code with no
 * number after it, such as "*21* days". Digits are spelled [0-9] because the
 * JVM's \d is ASCII and Android's ICU \d isn't.
 */
internal object CallForwardingCode {
    const val SIGNAL = "call_forwarding_code"

    /** Clears the default bar (50) by itself: nobody legitimate texts a stranger a code that hands over their calls. */
    const val SCORE = 60

    /** 7 to 15 digits, the E.164 limit, with the gaps people type. */
    private const val NUMBER = "\\+?[0-9](?:[ \\t().-]{0,2}[0-9]){6,14}(?![0-9])"
    private const val GAP = "[ \\t.-]{0,3}"
    private const val NOT_AFTER = "(?<![*#0-9])"

    private val gsm = Regex("$NOT_AFTER\\*\\*?(?:21|61|62|67|002|004|401)\\*[ \\t]{0,2}$NUMBER")

    // Verizon's codes take a North American number straight after them.
    private val verizon = Regex("$NOT_AFTER\\*7[12]$GAP(?:\\+?1$GAP)?\\(?[2-9][0-9]{2}\\)?$GAP[0-9]{3}$GAP[0-9]{4}(?![0-9])")

    /** The first forwarding code in [text] as it's written there, or null. */
    fun find(text: String): String? {
        if ('*' !in text) return null
        return (gsm.find(text) ?: verizon.find(text))?.value?.trim()
    }

    /**
     * [find] on a message read the way the content rules read it, so a
     * fullwidth star or digit and an invisible character in between don't
     * hide the code.
     */
    fun inMessage(body: String): String? {
        if (body.isBlank()) return null
        return find(SmsTextNormalizer.normalize(body.take(SmsContentAnalyzer.MAX_ANALYSIS_LENGTH)).folded)
    }
}
