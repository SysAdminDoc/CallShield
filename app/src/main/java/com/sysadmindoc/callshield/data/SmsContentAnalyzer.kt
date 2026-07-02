package com.sysadmindoc.callshield.data

import javax.inject.Inject

/**
 * Analyzes SMS message body content for spam indicators.
 * Pure regex/keyword-based — runs entirely on-device.
 */
class SmsContentAnalyzer @Inject constructor() {

    data class SmsAnalysisResult(
        val score: Int,
        val reasons: List<String>,
    )

    data class SmsReportIndicators(
        val domains: List<String> = emptyList(),
        val urlIndicators: List<String> = emptyList(),
    ) {
        fun isEmpty(): Boolean = domains.isEmpty() && urlIndicators.isEmpty()
    }

    // URL shorteners frequently used in SMS spam
    private val shortenerDomains =
        setOf(
            "bit.ly",
            "tinyurl.com",
            "t.co",
            "goo.gl",
            "ow.ly",
            "is.gd",
            "buff.ly",
            "rebrand.ly",
            "cutt.ly",
            "shorturl.at",
            "rb.gy",
            "t.ly",
            "v.gd",
            "tiny.cc",
            "qr.ae",
            "bl.ink",
            "lnk.to",
        )

    // Suspicious TLDs commonly used in phishing
    private val suspiciousTlds =
        setOf(
            ".xyz",
            ".top",
            ".club",
            ".work",
            ".buzz",
            ".icu",
            ".cam",
            ".life",
            ".click",
            ".link",
            ".info",
            ".loan",
            ".win",
            ".bid",
            ".stream",
            ".racing",
            ".download",
            ".gq",
            ".ml",
            ".tk",
            ".cf",
            ".ga",
            ".pw",
        )

    // Spam keyword patterns (case-insensitive)
    private val spamPatterns =
        listOf(
            // Financial scams
            Regex("(?i)(you('ve| have)? (won|been selected|been chosen))"),
            Regex("(?i)(claim (your|the) (prize|reward|gift|money))"),
            Regex("(?i)(free (gift|money|cash|iphone|samsung|card))"),
            Regex("(?i)(\\$\\d{2,}[,.]?\\d*\\s*(cash|reward|prize|gift))"),
            Regex("(?i)(wire transfer|western union|money ?gram|crypto payment)"),
            Regex("(?i)(bitcoin|btc|ethereum|eth|usdt).{0,20}(send|transfer|pay|wallet)"),
            // Urgency / pressure tactics
            Regex("(?i)(act (now|fast|immediately|today)|limited time|expires? (today|soon|now))"),
            Regex("(?i)(urgent|immediate action|account (suspended|locked|compromised|closed))"),
            Regex("(?i)(verify (your|the) (account|identity|information|ssn|social))"),
            Regex("(?i)(your (package|delivery|shipment) (has|is|was) (held|delayed|stopped))"),
            Regex("(?i)(final (notice|warning|attempt|reminder))"),
            // Loan / debt scams
            Regex("(?i)(pre-?approved|guaranteed approval|no credit check)"),
            Regex("(?i)(student loan (forgive|relief|discharge))"),
            Regex("(?i)(debt (relief|consolidation|settlement|forgive))"),
            Regex("(?i)(irs|tax).{0,20}(owe|debt|lien|levy|refund)"),
            // Impersonation
            Regex(
                "(?i)(amazon|apple|google|microsoft|paypal|netflix|fedex|ups|usps)" +
                    ".{0,30}(verify|confirm|update|suspend|locked|expire)",
            ),
            Regex("(?i)(social security).{0,20}(suspend|compromis|fraud|block)"),
            // Romance / adult scams
            Regex("(?i)(meet (singles|women|men|hot)|dating (site|app)|hookup)"),
            Regex("(?i)(adult|xxx|sexy).{0,15}(video|photo|pic|chat|call)"),
            // Health scams
            Regex("(?i)(miracle (cure|pill|drug|weight))"),
            Regex("(?i)(lose \\d+ (lbs?|pounds|kg) (in|fast|quick))"),
            Regex("(?i)(pharmacy|viagra|cialis|prescription).{0,20}(discount|cheap|free|order)"),
            // Generic spam signals
            Regex("(?i)(unsubscribe|opt.?out|stop to (end|cancel|quit|unsubscribe))"),
            Regex("(?i)(congratulations|congrats).{0,20}(won|winner|selected|chosen)"),
            Regex("(?i)text (yes|y|go|start|ok) to"),
            Regex("(?i)reply (yes|y|stop|1|2)"),
        )

    // Phone number in SMS body (common in callback scams)
    private val phoneInBody =
        Regex(
            "(?:call|dial|text|contact)\\s*(?:us\\s+(?:at|on))?\\s*\\+?\\d[\\d\\s\\-()]{7,}",
            RegexOption.IGNORE_CASE,
        )

    // URL pattern — length-capped to prevent ReDoS on pathological inputs
    private val urlPattern =
        Regex(
            "https?://[^\\s]{1,2048}|www\\.[^\\s]{1,2048}|" +
                "[a-zA-Z0-9][a-zA-Z0-9-]*\\.[a-zA-Z]{2,}/[^\\s]{0,2048}",
        )

    // ── Spam Domain Blocklist ─────────────────────────────────────────
    // Community-reported phishing/spam domains. Loaded from GitHub's
    // spam_domains.json and refreshed every 30 minutes by HotListSyncWorker.
    @Volatile
    private var spamDomains: Set<String> = emptySet()

    fun updateSpamDomains(domains: Collection<String>) {
        spamDomains =
            domains
                .asSequence()
                .mapNotNull(::normalizeDomainCandidate)
                .toSet()
    }

    fun hasSpamDomains(): Boolean = spamDomains.isNotEmpty()

    /** Extract root domain from a URL string (strips scheme, www, path, port). */
    private fun extractDomain(url: String): String {
        val lower =
            url
                .lowercase()
                .trim()
                .trimEnd('.', ',', '!', '?', ';', ':', ')', ']', '}')
        val withoutScheme =
            lower
                .substringAfter("://", lower)
                .removePrefix("www.")
        return withoutScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(':')
    }

    fun isKnownSpamDomainUrl(url: String): Boolean {
        val domain = extractDomain(url)
        return domain.isNotEmpty() && isKnownSpamDomain(domain)
    }

    private fun isKnownSpamDomain(domain: String): Boolean {
        var matched = false
        var candidate = if (spamDomains.isEmpty()) null else normalizeDomainCandidate(domain)
        while (!matched && !candidate.isNullOrBlank()) {
            if (candidate in spamDomains) {
                matched = true
            } else {
                val dotIndex = candidate.indexOf('.')
                candidate = if (dotIndex < 0) "" else candidate.substring(dotIndex + 1)
            }
        }
        return matched
    }

    /**
     * Extract only privacy-preserving SMS report fields. This never returns
     * message text, URL paths, query strings, or fragments.
     */
    fun extractReportableIndicators(body: String): SmsReportIndicators {
        if (body.isBlank()) return SmsReportIndicators()

        val analysisBody =
            if (body.length > MAX_ANALYSIS_LENGTH) {
                body.substring(0, MAX_ANALYSIS_LENGTH)
            } else {
                body
            }
        val domains = linkedSetOf<String>()
        val indicators = linkedSetOf<String>()

        urlPattern.findAll(analysisBody).forEach { match ->
            val url = match.value.lowercase()
            val domain = normalizeDomainCandidate(extractDomain(url))
            indicators.add("url_present")
            if (domain != null) {
                if (domains.size < MAX_REPORT_DOMAINS) {
                    domains.add(domain)
                }
                if (shortenerDomains.any { domain == it || domain.endsWith(".$it") }) {
                    indicators.add("shortener")
                }
                if (suspiciousTlds.any { domain.endsWith(it) }) {
                    indicators.add("suspicious_tld")
                }
                if (isKnownSpamDomain(domain)) {
                    indicators.add("known_spam_domain")
                }
            }
        }

        return SmsReportIndicators(
            domains = domains.toList(),
            urlIndicators = indicators.sorted(),
        )
    }

    /**
     * Hard cap on the body length we attempt to deep-analyze. A real SMS is
     * capped by the GSM/UCS-2 segment limit (~6 400 chars at 40 segments),
     * but RCS, MMS attachments, and inbox-scan paths can hand us much
     * larger blobs. Running 25+ regexes plus [urlPattern.findAll] over a
     * multi-MB string is a real ReDoS risk on the 5-second screening
     * deadline — at this length the message is almost certainly spam
     * anyway, so we sample the first 16 KB and stop.
     */
    fun analyze(body: String): SmsAnalysisResult {
        var score = 0
        val reasons = mutableListOf<String>()

        if (body.isBlank()) return SmsAnalysisResult(0, emptyList())

        // Length guard: cap the input we feed into the regex engine so a
        // hostile / malformed body can't pin the screening thread.
        val analysisBody =
            if (body.length > MAX_ANALYSIS_LENGTH) {
                score += 10
                reasons.add("oversized_body")
                body.substring(0, MAX_ANALYSIS_LENGTH)
            } else {
                body
            }

        // Check for URL shorteners (high spam signal)
        val urls = urlPattern.findAll(analysisBody).map { it.value.lowercase() }.toList()
        for (url in urls) {
            // Community-reported spam domain — highest confidence
            if (spamDomains.isNotEmpty()) {
                val domain = extractDomain(url)
                if (domain.isNotEmpty() && isKnownSpamDomain(domain)) {
                    score += 50
                    reasons.add("spam_domain")
                    continue
                }
            }
            if (shortenerDomains.any { url.contains(it) }) {
                score += 35
                reasons.add("shortened_url")
                continue
            }
            if (suspiciousTlds.any { url.endsWith(it) || url.contains("$it/") }) {
                score += 30
                reasons.add("suspicious_tld")
                continue
            }
        }

        // Check spam keyword patterns
        var patternHits = 0
        for (pattern in spamPatterns) {
            if (pattern.containsMatchIn(analysisBody)) {
                patternHits++
                if (patternHits == 1) {
                    score += 25
                    reasons.add("spam_keywords")
                } else {
                    score += 15 // Each additional pattern is more damning
                }
                if (patternHits >= 3) break // Cap pattern contribution
            }
        }

        // All caps text (>50% of message) — shouting is a spam signal
        val alphaChars = analysisBody.filter { it.isLetter() }
        if (alphaChars.length > 10 && alphaChars.count { it.isUpperCase() }.toFloat() / alphaChars.length > 0.5f) {
            score += 15
            reasons.add("excessive_caps")
        }

        // Contains phone number in body (callback scam) — regex is
        // case-insensitive so we match the original body instead of lowercasing.
        if (phoneInBody.containsMatchIn(analysisBody)) {
            score += 10
            reasons.add("callback_number")
        }

        // Excessive special characters / emoji (common in spam)
        val specialRatio =
            analysisBody.count { !it.isLetterOrDigit() && !it.isWhitespace() }.toFloat() /
                analysisBody.length.coerceAtLeast(1)
        if (specialRatio > 0.15f && analysisBody.length > 20) {
            score += 10
            reasons.add("special_chars")
        }

        // Very short message with URL (likely phishing).
        // Uses original body length so a 20-char SMS still triggers when
        // [analysisBody] hasn't been truncated.
        if (body.length < 50 && urls.isNotEmpty()) {
            score += 20
            reasons.add("short_msg_with_url")
        }

        return SmsAnalysisResult(score.coerceAtMost(100), reasons)
    }

    companion object {
        val shared: SmsContentAnalyzer = SmsContentAnalyzer()

        internal const val MAX_ANALYSIS_LENGTH = 16_384
        internal const val MAX_REPORT_DOMAINS = 10
        private const val MIN_REPORT_DOMAIN_LENGTH = 5
        private const val MAX_REPORT_DOMAIN_LENGTH = 253
        private const val MAX_REPORT_DOMAIN_LABEL_LENGTH = 63

        fun updateSpamDomains(domains: Collection<String>) {
            shared.updateSpamDomains(domains)
        }

        fun hasSpamDomains(): Boolean = shared.hasSpamDomains()

        fun isKnownSpamDomainUrl(url: String): Boolean = shared.isKnownSpamDomainUrl(url)

        internal fun normalizeDomainCandidate(rawDomain: String): String? {
            val normalized =
                rawDomain
                    .trim()
                    .trimEnd('.', ',', '!', '?', ';', ':', ')', ']', '}')
                    .lowercase()
                    .substringAfter("://")
                    .removePrefix("www.")
                    .substringBefore('/')
                    .substringBefore('?')
                    .substringBefore('#')
                    .substringBefore(':')
                    .trim('.')
            val labels = normalized.split(".")
            val isValid =
                listOf(
                    normalized.length in MIN_REPORT_DOMAIN_LENGTH..MAX_REPORT_DOMAIN_LENGTH,
                    "." in normalized,
                    normalized.all(::isReportDomainChar),
                    labels.all(::isValidReportDomainLabel),
                ).all { it }
            return normalized.takeIf { isValid }
        }

        fun analyze(body: String): SmsAnalysisResult = shared.analyze(body)

        fun extractReportableIndicators(body: String): SmsReportIndicators = shared.extractReportableIndicators(body)

        private fun isReportDomainChar(char: Char): Boolean =
            when {
                char in 'a'..'z' -> true
                char in '0'..'9' -> true
                char == '-' -> true
                char == '.' -> true
                else -> false
            }

        private fun isValidReportDomainLabel(label: String): Boolean =
            label.isNotBlank() &&
                label.length <= MAX_REPORT_DOMAIN_LABEL_LENGTH &&
                !label.startsWith("-") &&
                !label.endsWith("-")
    }
}
