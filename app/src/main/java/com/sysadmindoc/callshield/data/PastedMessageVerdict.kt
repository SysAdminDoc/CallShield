package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.remote.UrlSafetyChecker

/**
 * What Lookup says about a text someone pasted in: the content rules an
 * incoming text meets, at the bar the user's mode sets, and its links as the
 * URL checks found them.
 */
internal data class PastedMessageVerdict(
    /** The content rules' score, 0 to 100. */
    val score: Int,
    /** The content signals that fired, as tokens the signal labels read. */
    val signals: List<String>,
    /** Links a spam domain list or a threat feed knows as dangerous. */
    val dangerousLinks: List<UrlSafetyChecker.UrlCheckResult>,
    /** The score an incoming text is flagged at in the user's mode. */
    val threshold: Int,
) {
    /** Flagged by its content, or carrying a link already known to be dangerous. */
    val looksLikeSpam: Boolean get() = score >= threshold || dangerousLinks.isNotEmpty()

    companion object {
        /** The bars an incoming text's content check uses, normal and aggressive. */
        const val DEFAULT_THRESHOLD = 50
        const val AGGRESSIVE_THRESHOLD = 25

        /**
         * Scores [body] with no sender behind it, so nothing that needs one
         * (a first contact's reply bait) fires.
         */
        fun of(
            body: String,
            aggressive: Boolean,
            dangerousLinks: List<UrlSafetyChecker.UrlCheckResult>,
        ): PastedMessageVerdict {
            val analysis = SmsContentAnalyzer.analyze(body)
            return PastedMessageVerdict(
                score = analysis.score,
                signals = analysis.reasons,
                dangerousLinks = dangerousLinks,
                threshold = if (aggressive) AGGRESSIVE_THRESHOLD else DEFAULT_THRESHOLD,
            )
        }
    }
}
