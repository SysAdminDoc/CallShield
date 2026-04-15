package com.sysadmindoc.callshield.data

/**
 * Analyzes SMS message body content for spam indicators.
 * Pure regex/keyword-based — runs entirely on-device.
 */
object SmsContentAnalyzer {

    data class SmsAnalysisResult(
        val score: Int,
        val reasons: List<String>
    )

    // URL shorteners frequently used in SMS spam
    private val SHORTENER_DOMAINS = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd",
        "buff.ly", "rebrand.ly", "cutt.ly", "shorturl.at", "rb.gy",
        "t.ly", "v.gd", "tiny.cc", "qr.ae", "bl.ink", "lnk.to"
    )

    // Suspicious TLDs commonly used in phishing
    private val SUSPICIOUS_TLDS = setOf(
        ".xyz", ".top", ".club", ".work", ".buzz", ".icu", ".cam",
        ".life", ".click", ".link", ".info", ".loan", ".win", ".bid",
        ".stream", ".racing", ".download", ".gq", ".ml", ".tk", ".cf",
        ".ga", ".pw"
    )

    // Spam keyword patterns (case-insensitive)
    private val SPAM_PATTERNS = listOf(
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
        Regex("(?i)(amazon|apple|google|microsoft|paypal|netflix|fedex|ups|usps).{0,30}(verify|confirm|update|suspend|locked|expire)"),
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
    private val PHONE_IN_BODY = Regex("(?:call|dial|text|contact)\\s*(?:us\\s+(?:at|on))?\\s*\\+?\\d[\\d\\s\\-()]{7,}")

    // URL pattern — length-capped to prevent ReDoS on pathological inputs
    private val URL_PATTERN = Regex("https?://[^\\s]{1,2048}|www\\.[^\\s]{1,2048}|[a-zA-Z0-9][a-zA-Z0-9-]*\\.[a-zA-Z]{2,}/[^\\s]{0,2048}")

    // ── Spam Domain Blocklist ─────────────────────────────────────────
    // Community-reported phishing/spam domains. Loaded from GitHub's
    // spam_domains.json and refreshed every 30 minutes by HotListSyncWorker.
    @Volatile
    private var spamDomains: Set<String> = emptySet()

    fun updateSpamDomains(domains: Collection<String>) {
        spamDomains = domains.toHashSet()
    }

    fun hasSpamDomains(): Boolean = spamDomains.isNotEmpty()

    /** Extract root domain from a URL string (strips scheme, www, path, port). */
    private fun extractDomain(url: String): String {
        val lower = url.lowercase()
            .removePrefix("https://").removePrefix("http://").removePrefix("www.")
        return lower.split("/")[0].split("?")[0].split("#")[0].split(":")[0]
    }

    fun analyze(body: String): SmsAnalysisResult {
        var score = 0
        val reasons = mutableListOf<String>()

        if (body.isBlank()) return SmsAnalysisResult(0, emptyList())

        val lower = body.lowercase()

        // Check for URL shorteners (high spam signal)
        val urls = URL_PATTERN.findAll(body).map { it.value.lowercase() }.toList()
        for (url in urls) {
            // Community-reported spam domain — highest confidence
            if (spamDomains.isNotEmpty()) {
                val domain = extractDomain(url)
                if (domain.isNotEmpty() && domain in spamDomains) {
                    score += 50
                    reasons.add("spam_domain")
                    continue
                }
            }
            if (SHORTENER_DOMAINS.any { url.contains(it) }) {
                score += 35
                reasons.add("shortened_url")
                continue
            }
            if (SUSPICIOUS_TLDS.any { url.endsWith(it) || url.contains("$it/") }) {
                score += 30
                reasons.add("suspicious_tld")
                continue
            }
        }

        // Check spam keyword patterns
        var patternHits = 0
        for (pattern in SPAM_PATTERNS) {
            if (pattern.containsMatchIn(body)) {
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
        val alphaChars = body.filter { it.isLetter() }
        if (alphaChars.length > 10 && alphaChars.count { it.isUpperCase() }.toFloat() / alphaChars.length > 0.5f) {
            score += 15
            reasons.add("excessive_caps")
        }

        // Contains phone number in body (callback scam)
        if (PHONE_IN_BODY.containsMatchIn(lower)) {
            score += 10
            reasons.add("callback_number")
        }

        // Excessive special characters / emoji (common in spam)
        val specialRatio = body.count { !it.isLetterOrDigit() && !it.isWhitespace() }.toFloat() / body.length.coerceAtLeast(1)
        if (specialRatio > 0.15f && body.length > 20) {
            score += 10
            reasons.add("special_chars")
        }

        // Very short message with URL (likely phishing)
        if (body.length < 50 && urls.isNotEmpty()) {
            score += 20
            reasons.add("short_msg_with_url")
        }

        return SmsAnalysisResult(score.coerceAtMost(100), reasons)
    }
}
