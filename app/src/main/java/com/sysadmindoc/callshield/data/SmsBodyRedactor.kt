package com.sysadmindoc.callshield.data

/**
 * Builds privacy-preserving SMS body summaries for UI previews and exports.
 *
 * Detection still receives the original SMS body. This helper is only for
 * surfaces that users may show, share, or attach to support evidence.
 */
object SmsBodyRedactor {
    private const val MAX_DOMAINS_IN_SUMMARY = 3

    private val otpLikePattern = Regex("""\b(?:\d[\s-]?){4,8}\b""")
    private val phoneLikePattern = Regex("""(?<!\d)\+?\d[\d\s().-]{6,}\d(?!\d)""")
    private val emailLikePattern = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

    fun redactForPreview(body: String?): String? {
        val trimmed = body?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        val scanBody = trimmed.take(SmsContentAnalyzer.MAX_ANALYSIS_LENGTH)

        val indicators = SmsContentAnalyzer.extractReportableIndicators(scanBody)
        val parts =
            mutableListOf(
                "SMS body redacted",
                "${trimmed.length} chars",
            )

        if (indicators.domains.isNotEmpty()) {
            parts += buildDomainSummary(indicators.domains)
        }
        if (indicators.urlIndicators.isNotEmpty()) {
            parts += "URL signals: ${indicators.urlIndicators.joinToString(", ")}"
        }
        if (otpLikePattern.containsMatchIn(scanBody)) {
            parts += "code-like tokens hidden"
        }
        if (phoneLikePattern.containsMatchIn(scanBody)) {
            parts += "numbers hidden"
        }
        if (emailLikePattern.containsMatchIn(scanBody)) {
            parts += "email-like text hidden"
        }

        return parts.joinToString(" | ")
    }

    fun redactForCsv(body: String?): String = redactForPreview(body).orEmpty()

    private fun buildDomainSummary(domains: List<String>): String {
        val visible = domains.take(MAX_DOMAINS_IN_SUMMARY).joinToString(", ")
        val extra = domains.size - MAX_DOMAINS_IN_SUMMARY
        return if (extra > 0) {
            "domains: $visible, +$extra more"
        } else {
            "domains: $visible"
        }
    }
}
