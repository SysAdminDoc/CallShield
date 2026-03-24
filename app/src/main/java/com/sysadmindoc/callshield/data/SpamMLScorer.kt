package com.sysadmindoc.callshield.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.exp

/**
 * Detection Layer 15 — On-Device Logistic Regression Spam Scorer
 *
 * Scores incoming numbers using a lightweight logistic regression model
 * trained weekly from the CallShield spam database. No TFLite or heavy
 * ML libraries needed — pure math on 15 features runs in microseconds.
 *
 * The model is stored in data/spam_model_weights.json on GitHub and
 * synced locally via SyncWorker. Falls back to the bundled initial
 * weights if the sync hasn't happened yet.
 *
 * Features (all derived locally, no network calls):
 *  1.  toll_free              — 800/888/877/etc. prefix
 *  2.  high_spam_npa          — area code in high FTC/FCC complaint set
 *  3.  voip_range             — NPA-NXX in known high-spam VoIP carrier range
 *  4.  repeated_digits_ratio  — fraction occupied by most-common digit
 *  5.  sequential_asc_ratio   — ascending sequential pairs / 9
 *  6.  all_same_digit         — all 10 digits identical
 *  7.  nxx_555                — exchange is 555 (unassigned test numbers)
 *  8.  last4_zero             — last 4 digits are 0000
 *  9.  invalid_nxx            — NXX starts with 0 or 1 (NANP-invalid, often spoofed)
 *  10. subscriber_all_same    — last 4 digits all the same digit (9999, 1111)
 *  11. alternating_pattern    — even/odd positions each uniform but differ (5050505050)
 *  12. sequential_desc_ratio  — descending sequential pairs / 9
 *  13. nxx_below_200          — NXX integer < 200 (often unassigned ranges)
 *  14. low_digit_entropy      — fewer than 4 distinct digits in full number
 *  15. subscriber_sequential  — last 4 form a complete ascending/descending run (1234, 9876)
 *
 * Threshold: 0.7 (conservative — avoids false positives).
 */
object SpamMLScorer {

    private val TOLL_FREE_PREFIXES = setOf("800", "888", "877", "866", "855", "844", "833")
    private val HIGH_SPAM_NPAS = setOf(
        "800", "888", "877", "866", "855", "844", "833",
        "202", "213", "310", "323", "347", "404", "415",
        "512", "617", "646", "702", "713", "718", "786",
        "813", "832", "917", "929",
    )
    private val HIGH_SPAM_VOIP_NPANXX = setOf(
        "202555", "213226", "213555", "310555", "310400",
        "323555", "347555", "404555", "404430", "415555",
        "503555", "512555", "617555", "646555", "702555",
        "713555", "718555", "786555", "813555", "832555",
        "917555", "929555",
        "206455", "312454", "415523", "617286", "646397",
        "713291", "720420", "720660",
    )

    @Volatile
    private var weights: DoubleArray? = null
    private var bias: Double = -2.5
    private var threshold: Double = 0.7  // Conservative threshold

    /**
     * Load model weights from the locally-cached weights file.
     * Falls back to default heuristic weights if file not found.
     */
    fun loadWeights(context: Context) {
        try {
            val file = File(context.filesDir, "spam_model_weights.json")
            if (!file.exists()) {
                applyDefaultWeights()
                return
            }
            val json = file.readText()
            parseAndApply(json)
        } catch (_: Exception) {
            applyDefaultWeights()
        }
    }

    /**
     * Download updated model weights from GitHub and save locally.
     * Called from SyncWorker after the main database sync.
     */
    suspend fun syncWeights(context: Context) = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            val url = "https://raw.githubusercontent.com/SysAdminDoc/CallShield/master/data/spam_model_weights.json"
            val request = Request.Builder().url(url)
                .header("Cache-Control", "no-store, max-age=0")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext

            val body = response.body?.string() ?: return@withContext
            val file = File(context.filesDir, "spam_model_weights.json")
            file.writeText(body)
            parseAndApply(body)
        } catch (_: Exception) { }
    }

    /**
     * Score a number. Returns 0.0–1.0 probability of spam.
     * Returns -1.0 if weights not loaded or number format invalid.
     */
    fun score(number: String): Double {
        val w = weights ?: return -1.0
        val features = extractFeatures(number).takeIf { it.isNotEmpty() } ?: return -1.0

        val z = w.zip(features.toTypedArray()).sumOf { (wi, xi) -> wi * xi } + bias
        return sigmoid(z)
    }

    /**
     * Returns true if this number should be flagged as spam by the ML layer.
     * Only returns true at a high-confidence threshold to minimize false positives.
     */
    fun isSpam(number: String): Boolean {
        val s = score(number)
        return s >= threshold
    }

    /** ML spam confidence as 0–100 integer for display. */
    fun confidence(number: String): Int = (score(number).coerceIn(0.0, 1.0) * 100).toInt()

    private fun extractFeatures(number: String): DoubleArray {
        var digits = number.filter { it.isDigit() }
        if (digits.length == 11 && digits.startsWith("1")) digits = digits.drop(1)
        if (digits.length != 10) return doubleArrayOf()

        val npa    = digits.substring(0, 3)
        val nxx    = digits.substring(3, 6)
        val sub    = digits.substring(6)      // last 4
        val npanxx = digits.substring(0, 6)
        val nxxInt = nxx.toIntOrNull() ?: 0

        // ── Features 1–8 (original) ──────────────────────────────────
        val tollFree     = if (npa in TOLL_FREE_PREFIXES) 1.0 else 0.0
        val highSpamNpa  = if (npa in HIGH_SPAM_NPAS) 1.0 else 0.0
        val voipRange    = if (npanxx in HIGH_SPAM_VOIP_NPANXX) 1.0 else 0.0

        val digitCounts  = digits.groupingBy { it }.eachCount()
        val repeatedRatio = (digitCounts.values.maxOrNull() ?: 1) / 10.0

        val seqAsc = (0 until 9).count { digits[it + 1].digitToInt() == digits[it].digitToInt() + 1 }
        val seqAscRatio = seqAsc / 9.0

        val allSame   = if (digits.toSet().size == 1) 1.0 else 0.0
        val is555     = if (nxx == "555") 1.0 else 0.0
        val last4Zero = if (sub == "0000") 1.0 else 0.0

        // ── Features 9–15 (new) ──────────────────────────────────────
        // NXX must start with 2–9 in NANP; 0 or 1 = spoofed/invalid
        val invalidNxx = if (nxx.firstOrNull().let { it == '0' || it == '1' }) 1.0 else 0.0

        // Subscriber (last 4) all same digit: 9999, 1111, etc.
        val subAllSame = if (sub.toSet().size == 1) 1.0 else 0.0

        // Alternating: even-indexed and odd-indexed digits are each uniform
        // but different from each other (e.g. 5050505050, 1212121212)
        val evenSet = (0 until 10 step 2).map { digits[it] }.toSet()
        val oddSet  = (1 until 10 step 2).map { digits[it] }.toSet()
        val alternating = if (evenSet.size == 1 && oddSet.size == 1 && evenSet != oddSet) 1.0 else 0.0

        val seqDesc = (0 until 9).count { digits[it + 1].digitToInt() == digits[it].digitToInt() - 1 }
        val seqDescRatio = seqDesc / 9.0

        // NXX below 200 — many unassigned or rarely-used-legitimately ranges
        val nxxBelow200 = if (nxxInt < 200) 1.0 else 0.0

        // Low entropy — fewer than 4 distinct digits in the entire number
        val lowEntropy = if (digitCounts.size < 4) 1.0 else 0.0

        // Subscriber sequential — last 4 form a fully ascending or descending run
        val subAsc  = (0 until 3).count { sub[it + 1].digitToInt() == sub[it].digitToInt() + 1 }
        val subDesc = (0 until 3).count { sub[it + 1].digitToInt() == sub[it].digitToInt() - 1 }
        val subSeq  = if (subAsc == 3 || subDesc == 3) 1.0 else 0.0

        return doubleArrayOf(
            tollFree, highSpamNpa, voipRange,
            repeatedRatio, seqAscRatio, allSame,
            is555, last4Zero,
            invalidNxx, subAllSame, alternating,
            seqDescRatio, nxxBelow200, lowEntropy, subSeq
        )
    }

    private fun sigmoid(x: Double): Double =
        if (x >= 0) 1.0 / (1.0 + exp(-x)) else { val e = exp(x); e / (1.0 + e) }

    private fun applyDefaultWeights() {
        // Initial heuristic priors for 15 features.
        // Will be replaced by trained weights after first CI run.
        weights = doubleArrayOf(
            1.2,  // toll_free
            0.8,  // high_spam_npa
            1.8,  // voip_range
            1.5,  // repeated_digits_ratio
            0.6,  // sequential_asc_ratio
            2.1,  // all_same_digit
            1.4,  // nxx_555
            0.9,  // last4_zero
            2.0,  // invalid_nxx
            1.2,  // subscriber_all_same
            1.8,  // alternating_pattern
            0.5,  // sequential_desc_ratio
            1.0,  // nxx_below_200
            1.5,  // low_digit_entropy
            0.8,  // subscriber_sequential
        )
        bias = -2.5
        threshold = 0.7
    }

    private fun parseAndApply(json: String) {
        try {
            // Simple regex parse to avoid additional Moshi adapter registration
            val weightsMatch = Regex(""""weights"\s*:\s*\[([^\]]+)]""").find(json)
            val biasMatch = Regex(""""bias"\s*:\s*([-\d.]+)""").find(json)
            val thresholdMatch = Regex(""""threshold"\s*:\s*([\d.]+)""").find(json)

            val parsedWeights = weightsMatch?.groupValues?.get(1)
                ?.split(",")
                ?.mapNotNull { it.trim().toDoubleOrNull() }
                ?.toDoubleArray()

            if (parsedWeights != null && parsedWeights.size >= 15) {
                weights = parsedWeights
                bias = biasMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: -2.5
                threshold = thresholdMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.7
            } else {
                applyDefaultWeights()
            }
        } catch (_: Exception) {
            applyDefaultWeights()
        }
    }
}
