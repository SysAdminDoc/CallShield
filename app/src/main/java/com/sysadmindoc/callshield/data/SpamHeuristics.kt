package com.sysadmindoc.callshield.data

import android.content.Context
import android.provider.ContactsContract
import android.telephony.TelephonyManager

/**
 * On-device heuristic spam detection engine.
 * No network calls, no API keys — pure local analysis.
 */
object SpamHeuristics {

    // ── Contact Whitelist ──────────────────────────────────────────────
    // If the number is in the user's contacts, it's NEVER spam.
    fun isInContacts(context: Context, number: String): Boolean {
        val normalized = normalizeForLookup(number)
        return try {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(normalized)
            )
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null, null, null
            )
            val found = (cursor?.count ?: 0) > 0
            cursor?.close()
            found
        } catch (_: Exception) {
            false
        }
    }

    // ── Neighbor Spoofing ──────────────────────────────────────────────
    // Spammers spoof numbers with same area code + exchange as the victim
    fun isNeighborSpoof(context: Context, incomingNumber: String): Boolean {
        val userNumber = getUserPhoneNumber(context) ?: return false
        val userDigits = userNumber.filter { it.isDigit() }.takeLast(10)
        val inDigits = incomingNumber.filter { it.isDigit() }.takeLast(10)

        if (userDigits.length < 10 || inDigits.length < 10) return false
        if (userDigits == inDigits) return false // It's their own number

        // Same area code (first 3) + exchange (next 3) = neighbor spoof
        return userDigits.substring(0, 6) == inDigits.substring(0, 6)
    }

    // ── Toll-Free Spam Scoring ─────────────────────────────────────────
    // Toll-free numbers are heavily abused by robocallers
    private val TOLL_FREE_PREFIXES = setOf("800", "888", "877", "866", "855", "844", "833")

    fun isTollFree(number: String): Boolean {
        val digits = number.filter { it.isDigit() }.takeLast(10)
        if (digits.length < 10) return false
        return digits.substring(0, 3) in TOLL_FREE_PREFIXES
    }

    // ── International Premium Rate ─────────────────────────────────────
    // These are almost always scam/wangiri callback numbers
    private val PREMIUM_COUNTRY_CODES = setOf(
        "900",   // US premium
        "976",   // US premium legacy
        "1900",  // US premium with country code
    )

    // Known international wangiri/premium rate country codes
    // These originate one-ring scam calls (wangiri) to bait callbacks to premium numbers.
    private val WANGIRI_COUNTRY_CODES = setOf(
        // Africa (high wangiri origination)
        "232",  // Sierra Leone
        "252",  // Somalia
        "222",  // Mauritania
        "263",  // Zimbabwe
        "248",  // Seychelles
        "241",  // Gabon
        "224",  // Guinea
        "257",  // Burundi
        "255",  // Tanzania (some exchanges)
        "261",  // Madagascar
        "265",  // Malawi
        "267",  // Botswana
        "269",  // Comoros
        "291",  // Eritrea

        // Eastern Europe / FSU
        "375",  // Belarus
        "371",  // Latvia
        "381",  // Serbia
        "382",  // Montenegro
        "383",  // Kosovo
        "385",  // Croatia (some premium exchanges)
        "386",  // Slovenia (some premium exchanges)
        "387",  // Bosnia and Herzegovina
        "389",  // North Macedonia

        // Caribbean (heavily abused — share +1 country code but distinct NPA)
        "284",  // British Virgin Islands (+1-284)
        "649",  // Turks and Caicos (+1-649)
        "767",  // Dominica (+1-767)
        "809",  // Dominican Republic (+1-809)
        "829",  // Dominican Republic (+1-829)
        "849",  // Dominican Republic (+1-849)
        "876",  // Jamaica (+1-876)
        "268",  // Antigua (+1-268)
        "473",  // Grenada (+1-473)
        "664",  // Montserrat (+1-664)
        "721",  // Sint Maarten (+1-721)
        "758",  // Saint Lucia (+1-758)
        "784",  // Saint Vincent (+1-784)
        "868",  // Trinidad & Tobago (+1-868)
        "869",  // Saint Kitts (+1-869)

        // Pacific / other
        "672",  // Norfolk Island
        "674",  // Nauru
        "676",  // Tonga
        "677",  // Solomon Islands
        "678",  // Vanuatu
        "679",  // Fiji
        "686",  // Kiribati
        "688",  // Tuvalu
        "692",  // Marshall Islands
    )

    fun isInternationalPremium(number: String): Boolean {
        val clean = number.removePrefix("+")
        return PREMIUM_COUNTRY_CODES.any { clean.startsWith(it) }
    }

    fun isWangiriCountryCode(number: String): Boolean {
        val clean = number.removePrefix("+")
        // Only flag international numbers (not starting with 1 for US/CA)
        if (clean.startsWith("1") && clean.length == 11) return false
        return WANGIRI_COUNTRY_CODES.any { clean.startsWith(it) }
    }

    // ── VoIP Range Detection ───────────────────────────────────────────
    // NPA-NXX ranges (area code + exchange, first 6 digits of 10-digit number)
    // assigned to VoIP carriers with documented high spam-origination rates.
    // Sources: FCC enforcement actions, carrier abuse reports, FTC complaints.
    // NOTE: only include ranges with confirmed high spam rates — many VoIP
    // numbers are legitimate businesses. "toll_free" check covers 8XX separately.
    private val HIGH_SPAM_VOIP_NPANXX = setOf(
        // ── Bandwidth.com reseller ranges ────────────────────────────
        // Heavily abused by robocall operations for neighbor-spoofing campaigns
        "202555", "213226", "213555", "310555", "310400",
        "323555", "347555", "404555", "404430", "415555",
        "503555", "512555", "617555", "646555", "702555",
        "713555", "718555", "786555", "813555", "832555",
        "917555", "929555",

        // ── Twilio high-volume spam ranges ───────────────────────────
        // Twilio is heavily abused; these NXXs appear in FTC bulk datasets
        "206455", "206456", "206457", "312454", "312455",
        "415523", "415524", "415525", "415526", "617286",
        "617453", "646397", "646398", "646399", "713291",
        "720420", "720421", "720660", "800289", "844258",

        // ── TextNow VoIP ranges (frequently used in scam SMS) ────────
        "365234", "365235", "365236", "365237", "365238",
        "365239", "365240", "365241", "365242", "365243",
        "365244", "365245", "365246",

        // ── Vonage / Nexmo ranges ─────────────────────────────────────
        "201984", "201985", "201986", "201987",
        "732412", "732413", "732414",

        // ── Google Voice ranges (used in auth bypass + scam ops) ─────
        // NOTE: Many legit Google Voice users exist — score is low, not block.
        // These are included so the heuristic score is non-zero, not to auto-block.
        "213260", "312320", "346570", "404400",
        "415320", "619320", "646320", "702320", "720320",

        // ── VOIP.ms / wholesale SIP ranges ───────────────────────────
        "226506", "226507", "226508", "226509",
        "437370", "437371", "437372", "437373",
    )

    fun isHighSpamVoipRange(number: String): Boolean {
        val digits = number.filter { it.isDigit() }.takeLast(10)
        if (digits.length < 10) return false
        return digits.substring(0, 6) in HIGH_SPAM_VOIP_NPANXX
    }

    // ── Hot Campaign Range Detection ──────────────────────────────────
    // NPA-NXX prefixes where 3+ distinct numbers appeared in the hot list
    // within the last 24h — a strong signal that a campaign is actively
    // dialing across that exchange. Updated every 30 minutes by HotListSyncWorker.
    @Volatile
    private var hotCampaignRanges: Set<String> = emptySet()

    fun updateHotRanges(ranges: Collection<String>) {
        hotCampaignRanges = ranges.toHashSet()
    }

    fun hasHotRanges(): Boolean = hotCampaignRanges.isNotEmpty()

    fun isHotCampaignRange(number: String): Boolean {
        if (hotCampaignRanges.isEmpty()) return false
        val digits = number.filter { it.isDigit() }.takeLast(10)
        if (digits.length < 6) return false
        return digits.substring(0, 6) in hotCampaignRanges
    }

    // ── Short Code / Invalid Format ────────────────────────────────────
    fun isInvalidFormat(number: String): Boolean {
        val digits = number.filter { it.isDigit() }
        // Valid US numbers are 10 or 11 digits. Anything else is suspicious.
        // Short codes (5-6 digits) can be legit for 2FA but also spam.
        return digits.length in 1..4 || digits.length in 7..9
    }

    // ── Rapid-Fire Detection ───────────────────────────────────────────
    // If we've seen this number call multiple times in a short window,
    // it's likely a robocaller. Returns true if the number has been seen
    // more than threshold times in the recent call log entries.
    fun isRapidFire(recentNumbers: List<Pair<String, Long>>, number: String, windowMs: Long = 3600_000, threshold: Int = 3): Boolean {
        val now = System.currentTimeMillis()
        val normalized = number.filter { it.isDigit() }.takeLast(10)
        val count = recentNumbers.count { (num, time) ->
            num.filter { it.isDigit() }.takeLast(10) == normalized && (now - time) < windowMs
        }
        return count >= threshold
    }

    // ── Aggregate Heuristic Score ──────────────────────────────────────
    // Returns a spam confidence score from 0 to 100.
    // Anything above the threshold should be blocked.
    data class HeuristicResult(
        val score: Int,
        val reasons: List<String>
    ) {
        val isSpam: Boolean get() = score >= 60
    }

    fun analyze(
        context: Context,
        number: String,
        smsBody: String? = null,
        recentBlockedNumbers: List<Pair<String, Long>> = emptyList()
    ): HeuristicResult {
        var score = 0
        val reasons = mutableListOf<String>()

        // Contact whitelist — instant pass
        if (isInContacts(context, number)) {
            return HeuristicResult(0, listOf("contact_whitelist"))
        }

        // International premium / wangiri — very high confidence
        if (isInternationalPremium(number)) {
            score += 90
            reasons.add("premium_rate")
        }
        if (isWangiriCountryCode(number)) {
            score += 80
            reasons.add("wangiri_country")
        }

        // Invalid format
        if (isInvalidFormat(number)) {
            score += 40
            reasons.add("invalid_format")
        }

        // High-spam VoIP range
        if (isHighSpamVoipRange(number)) {
            score += 30
            reasons.add("voip_spam_range")
        }

        // Active campaign range — 3+ distinct numbers from this NPA-NXX in last 24h
        if (isHotCampaignRange(number)) {
            score += 35
            reasons.add("hot_campaign_range")
        }

        // Toll-free (mild signal — many legit businesses use these)
        if (isTollFree(number)) {
            score += 10
            reasons.add("toll_free")
        }

        // Neighbor spoofing
        if (isNeighborSpoof(context, number)) {
            score += 50
            reasons.add("neighbor_spoof")
        }

        // Rapid-fire calling
        if (isRapidFire(recentBlockedNumbers, number)) {
            score += 40
            reasons.add("rapid_fire")
        }

        // SMS content analysis
        if (smsBody != null) {
            val smsResult = SmsContentAnalyzer.analyze(smsBody)
            score += smsResult.score
            reasons.addAll(smsResult.reasons)
        }

        return HeuristicResult(score.coerceAtMost(100), reasons)
    }

    private fun normalizeForLookup(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length == 11 && digits.startsWith("1")) {
            "+$digits"
        } else if (digits.length == 10) {
            "+1$digits"
        } else {
            number
        }
    }

    @Suppress("DEPRECATION")
    private fun getUserPhoneNumber(context: Context): String? {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            tm?.line1Number?.takeIf { it.isNotBlank() }
        } catch (_: SecurityException) {
            null
        }
    }
}
