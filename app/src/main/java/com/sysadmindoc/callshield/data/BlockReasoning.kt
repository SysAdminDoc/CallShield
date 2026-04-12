package com.sysadmindoc.callshield.data

/**
 * Generates a plain-English explanation of why a given block fired.
 *
 * Built from what we already store on every `BlockedCall` — the
 * `matchReason` (which pipeline layer matched), `description` (the raw
 * reasons from heuristics / content analysis), and `confidence`. We do
 * NOT add a new column or mutate the hot path; the reasoning is
 * reconstructed at view time from existing persisted fields.
 *
 * The goal is trust-building for the #1 false-positive complaint pattern
 * (*"CallShield blocked my bank — why?"*). A clear narrative lets the
 * user understand the decision and either confirm it's spam, whitelist
 * the number, or report a false positive.
 */
object BlockReasoning {

    data class Reasoning(
        /** One-line summary shown at the top of the panel. */
        val headline: String,
        /** Ordered bullet points with the decision details. */
        val bullets: List<String>,
    )

    /**
     * @param matchReason from `BlockedCall.matchReason` or `SpamCheckResult.matchSource`
     *   (e.g. `user_blocklist`, `database`, `prefix`, `wildcard`, `time_block`,
     *   `frequency`, `heuristic`, `campaign_burst`, `ml_scorer`, `keyword`,
     *   `sms_content`, `rcs_*` derivatives, `emergency_contact`, `manual_whitelist`).
     * @param description from `BlockedCall.description` — for heuristics and
     *   content analysis this is a comma-separated list of reasons like
     *   "high_spam_npa, voip_spam_range, neighbor_spoof".
     * @param confidence 0-100 score (only meaningful for heuristic, ML,
     *   campaign_burst, sms_content layers).
     */
    fun explain(matchReason: String, description: String, confidence: Int): Reasoning {
        val bullets = mutableListOf<String>()
        val headline: String

        when {
            matchReason == "user_blocklist" -> {
                headline = "You blocked this number."
                bullets += "Matched your personal blocklist at detection layer 5."
                if (description.isNotBlank()) bullets += "Note: \"$description\""
            }

            matchReason == "database" -> {
                headline = "This number is in CallShield's community spam database."
                bullets += "Matched at detection layer 6 (database lookup)."
                if (description.isNotBlank()) bullets += "Type on file: $description"
            }

            matchReason == "prefix" -> {
                headline = "This number's prefix is a known spam range."
                bullets += "Matched at detection layer 7 (prefix rules — premium-rate / wangiri country codes)."
                if (description.isNotBlank()) bullets += "Prefix tag: $description"
            }

            matchReason == "wildcard" -> {
                headline = "This number matched one of your wildcard / regex rules."
                bullets += "Matched at detection layer 8 (wildcard rules)."
                if (description.isNotBlank()) bullets += "Rule: $description"
            }

            matchReason == "time_block" -> {
                headline = "Blocked during your quiet hours."
                bullets += "Matched at detection layer 9 (quiet-hours time window)."
                bullets += "Your contacts and whitelisted numbers still ring through during quiet hours."
            }

            matchReason == "frequency" -> {
                headline = "This number has called you too often."
                bullets += "Matched at detection layer 10 (frequency auto-block — 3+ calls)."
                if (description.isNotBlank()) bullets += description
            }

            matchReason == "heuristic" -> {
                headline = "Flagged by the heuristic engine at ${confidence}% confidence."
                bullets += "Matched at detection layer 11 (heuristics)."
                description.split(",").map { it.trim().replace("_", " ") }.filter { it.isNotBlank() }.forEach {
                    bullets += "• $it"
                }
            }

            matchReason == "campaign_burst" -> {
                headline = "This prefix is running an active spam campaign."
                bullets += "Matched at detection layer 11.5 (campaign burst detector)."
                bullets += "5+ distinct numbers from this NPA-NXX prefix have called in the last hour."
                bullets += "Campaign confidence: ${confidence}%."
            }

            matchReason == "ml_scorer" -> {
                headline = "The on-device ML model flagged this number as ${confidence}% likely spam."
                bullets += "Matched at detection layer 15 (gradient-boosted tree spam scorer)."
                bullets += "The model runs entirely on your device — no data sent anywhere."
                if (description.isNotBlank()) bullets += description
            }

            matchReason == "keyword" -> {
                headline = "The SMS matched one of your keyword rules."
                bullets += "Matched at detection layer 13 (SMS keyword rules)."
                if (description.isNotBlank()) bullets += "Rule: $description"
            }

            matchReason == "sms_content" -> {
                headline = "The SMS content looked like spam (${confidence}% confidence)."
                bullets += "Matched at detection layer 14 (SMS content analysis)."
                description.split(",").map { it.trim().replace("_", " ") }.filter { it.isNotBlank() }.forEach {
                    bullets += "• $it"
                }
            }

            matchReason.startsWith("rcs_") -> {
                val inner = matchReason.removePrefix("rcs_")
                headline = "RCS message blocked via notification filter."
                bullets += "Matched via the RCS Filter (NotificationListener bridge)."
                bullets += "Underlying reason: $inner."
                if (description.isNotBlank()) bullets += description
            }

            matchReason == "stir_shaken_failed" -> {
                headline = "Caller ID verification failed (STIR/SHAKEN)."
                bullets += "The carrier could not verify this call's caller ID."
                bullets += "Usually means the number was spoofed."
            }

            matchReason == "hidden_number" -> {
                headline = "Call came in with no phone number attached."
                bullets += "Blocked by your \"block unknown numbers\" setting."
            }

            // Allow-through sources (shown only on NumberDetail for allowed calls)
            matchReason == "emergency_contact" -> {
                headline = "This is one of your emergency contacts."
                bullets += "Always rings through — bypasses blocklist, quiet hours, and aggressive mode."
            }

            matchReason == "manual_whitelist" -> {
                headline = "You added this number to your whitelist."
                bullets += "Always allowed — matched layer 1 (manual whitelist)."
            }

            matchReason == "contact_whitelist" -> {
                headline = "This number is in your phone's contacts."
                bullets += "Always allowed — matched layer 2 (contact whitelist)."
            }

            matchReason == "recently_dialed" -> {
                headline = "You called this number recently, so we let the callback through."
                bullets += "Matched layer 3 (callback detection) — any number you've dialed in the last 24h rings through even if it's in a spam database."
            }

            matchReason == "repeated_urgent" -> {
                headline = "Likely urgent — same number called twice in under 5 minutes."
                bullets += "Matched layer 4 (repeated-urgent-caller allow-through)."
                bullets += "Robocallers don't usually retry immediately; humans with an emergency do."
            }

            matchReason == "sms_context" -> {
                headline = "You've had a real conversation with this number."
                bullets += "Matched SMS context trust — you've sent a message to this number, or received from it on 2+ distinct days."
            }

            matchReason.isBlank() -> {
                headline = "No block — this number was allowed through."
                bullets += "None of the 15+ detection layers matched."
            }

            else -> {
                headline = "Blocked at layer: $matchReason"
                if (description.isNotBlank()) bullets += description
                if (confidence in 1..99) bullets += "Confidence: ${confidence}%."
            }
        }

        return Reasoning(headline = headline, bullets = bullets)
    }
}
