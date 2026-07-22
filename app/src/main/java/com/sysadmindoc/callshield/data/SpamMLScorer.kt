package com.sysadmindoc.callshield.data

import android.content.Context
import com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup
import com.sysadmindoc.callshield.data.remote.GitHubDataSource
import com.sysadmindoc.callshield.util.filterAsciiDigits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Observable outcome of the most recent model load/sync — lets a silent
 * detection-quality regression (a corrupt GBT payload falling back to logistic
 * regression) become visible instead of failing quietly.
 */
enum class ModelHealth {
    /** No load attempted yet. */
    UNINITIALIZED,

    /** Gradient-boosted-tree ensemble active (best). */
    GBT_ACTIVE,

    /** Logistic-regression model active — an intended v2/LR model. */
    LR_ACTIVE,

    /** A v3 GBT model was declared but its trees failed to parse — running LR. */
    DEGRADED_TO_LR,

    /** Payload could not be parsed at all; the previous model was kept. */
    PARSE_FAILED,

    /** No usable model found; built-in default weights are in use. */
    DEFAULTS,
}

/** True when [json] declares a v3+ gradient-boosted-tree model. */
internal fun jsonDeclaresGbt(json: String): Boolean {
    val version =
        Regex(""""version"\s*:\s*(\d+)""")
            .find(json)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull() ?: 2
    val type = Regex(""""model_type"\s*:\s*"(\w+)"""").find(json)?.groupValues?.get(1) ?: ""
    return version >= 3 && type == "gbt"
}

/** Classify a load/sync outcome into a [ModelHealth] (pure, for testability). */
internal fun modelHealthFor(
    parseSucceeded: Boolean,
    usingGbt: Boolean,
    declaredGbt: Boolean,
): ModelHealth =
    when {
        !parseSucceeded -> ModelHealth.PARSE_FAILED
        usingGbt -> ModelHealth.GBT_ACTIVE
        declaredGbt -> ModelHealth.DEGRADED_TO_LR
        else -> ModelHealth.LR_ACTIVE
    }

/**
 * Detection Layer 15 — On-Device ML Spam Scorer (v3: Gradient-Boosted Trees)
 *
 * Scores incoming numbers using either:
 *   - A gradient-boosted tree (GBT) ensemble (version 3 model), or
 *   - A logistic regression fallback (version 2, or if GBT parsing fails)
 *
 * No TFLite or heavy ML libraries needed — pure Kotlin inference on 20 features
 * runs in microseconds on any Android device.
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
 *  16. time_of_day_sin        — sin(2π * hour/24) cyclical time encoding
 *  17. time_of_day_cos        — cos(2π * hour/24) cyclical time encoding
 *  18. geographic_distance    — caller's area code differs from user's by >200 numerically
 *  19. short_number           — number has fewer than 7 digits (short codes)
 *  20. plus_one_prefix        — number starts with +1 (US/Canada)
 *
 * Threshold: 0.7 (conservative — avoids false positives).
 */
class SpamMLScorer
    @Inject
    constructor() {
        private val tollFreePrefixes = setOf("800", "888", "877", "866", "855", "844", "833")
        private val highSpamNpas =
            setOf(
                "800",
                "888",
                "877",
                "866",
                "855",
                "844",
                "833",
                "202",
                "213",
                "310",
                "323",
                "347",
                "404",
                "415",
                "512",
                "617",
                "646",
                "702",
                "713",
                "718",
                "786",
                "813",
                "832",
                "917",
                "929",
            )
        private val highSpamVoipNpanxx =
            setOf(
                "202555",
                "213226",
                "213555",
                "310555",
                "310400",
                "323555",
                "347555",
                "404555",
                "404430",
                "415555",
                "503555",
                "512555",
                "617555",
                "646555",
                "702555",
                "713555",
                "718555",
                "786555",
                "813555",
                "832555",
                "917555",
                "929555",
                "206455",
                "312454",
                "415523",
                "617286",
                "646397",
                "713291",
                "720420",
                "720660",
            )

        private data class ModelState(
            val useGbt: Boolean,
            val gbtTrees: List<GbtTree>?,
            val gbtLearningRate: Double,
            val gbtInitialScore: Double,
            val weights: DoubleArray?,
            val bias: Double,
            val threshold: Double,
        )

        /** Result of a single scoring pass — used to avoid double work on the hot path. */
        data class Verdict(
            val score: Double,
            val confidence: Int,
            val isSpam: Boolean,
        )

        // ── Atomic model state ───────────────────────────────────────────
        // All model parameters live in a single immutable ModelState object.
        // Reads grab the reference once (guaranteed atomic for object refs on
        // JVM), so score() never sees a half-updated model during syncWeights().
        @Volatile
        private var state: ModelState = defaultModelState()

        @Volatile
        private var _modelHealth: ModelHealth = ModelHealth.UNINITIALIZED

        /** Health of the most recent model load/sync (for diagnostics/logging). */
        val modelHealth: ModelHealth get() = _modelHealth

        private fun logDegradedModel(source: String) {
            android.util.Log.w("SpamMLScorer", "Model $source degraded: $_modelHealth")
        }

        /**
         * A single decision tree in the GBT ensemble.
         * Arrays follow scikit-learn's internal tree format:
         *   - feature[i]: feature index to split on (-2 = leaf)
         *   - threshold[i]: split threshold
         *   - childrenLeft[i]: left child node index (-1 = no child)
         *   - childrenRight[i]: right child node index (-1 = no child)
         *   - value[i]: leaf value (only meaningful when feature[i] == -2)
         */
        private data class GbtTree(
            val feature: IntArray,
            val threshold: DoubleArray,
            val childrenLeft: IntArray,
            val childrenRight: IntArray,
            val value: DoubleArray,
        )

        /**
         * Load model weights from the locally-cached weights file.
         * Falls back to bundled initial weights, then to default heuristic weights.
         *
         * Atomic: the current model is only replaced when a new one parses
         * successfully, so a corrupt cache file can never regress to defaults
         * when a working bundled model is available.
         */
        fun loadWeights(context: Context) {
            try {
                // Best-effort sweep of an orphan .tmp file from a prior
                // persistModelJson() that was killed between writeText and
                // renameTo. Ignored if persistModelJson is mid-flight: it
                // will re-create the file on its next write.
                runCatching {
                    val stale = File(context.filesDir, "spam_model_weights.json.tmp")
                    if (stale.exists()) stale.delete()
                }

                val file = File(context.filesDir, "spam_model_weights.json")
                if (file.exists()) {
                    val json = file.readText()
                    val parsed = parseModel(json)
                    if (parsed != null) {
                        state = parsed
                        recordModelHealth(json, parsed, "cache")
                        return
                    }
                }

                val bundled = GitHubDataSource.readBundledAsset(context, GitHubDataSource.BUNDLED_MODEL_WEIGHTS_ASSET)
                if (bundled.isSuccess) {
                    val json = bundled.getOrThrow()
                    val parsed = parseModel(json)
                    if (parsed != null) {
                        state = parsed
                        recordModelHealth(json, parsed, "bundled")
                        return
                    }
                }

                // Only fall all the way back to defaults if we have nothing at all.
                state = defaultModelState()
                _modelHealth = ModelHealth.DEFAULTS
                logDegradedModel("load")
            } catch (_: Exception) {
                state = defaultModelState()
                _modelHealth = ModelHealth.DEFAULTS
            }
        }

        private fun recordModelHealth(
            json: String,
            parsed: ModelState,
            source: String,
        ) {
            _modelHealth = modelHealthFor(parseSucceeded = true, usingGbt = parsed.useGbt, declaredGbt = jsonDeclaresGbt(json))
            if (_modelHealth == ModelHealth.DEGRADED_TO_LR) logDegradedModel(source)
        }

        /**
         * Download updated model weights from GitHub and save locally.
         * Called from SyncWorker after the main database sync.
         */
        suspend fun syncWeights(context: Context) =
            withContext(Dispatchers.IO) {
                try {
                    val body =
                        GitHubDataSource()
                            .fetchModelWeightsJson()
                            .getOrNull()
                            ?: GitHubDataSource
                                .readBundledAsset(context, GitHubDataSource.BUNDLED_MODEL_WEIGHTS_ASSET)
                                .getOrNull()

                    val json = body ?: return@withContext

                    // Parse first, only touch state + disk on success. This keeps the
                    // running model intact if the remote payload is corrupted and
                    // prevents transient blips where isSpam() would see defaults.
                    val parsed = parseModel(json)
                    if (parsed == null) {
                        // Malformed payload — keep the previous model, but record
                        // and log the failure instead of degrading silently.
                        _modelHealth = ModelHealth.PARSE_FAILED
                        logDegradedModel("sync")
                        return@withContext
                    }
                    state = parsed
                    recordModelHealth(json, parsed, "sync")
                    persistModelJson(context, json)
                } catch (_: Exception) {
                }
            }

        /**
         * Score a number. Returns 0.0–1.0 probability of spam.
         * Returns -1.0 if weights not loaded or number format invalid.
         *
         * Thread-safe: reads [state] once (atomic ref read on JVM) so the
         * entire scoring pass uses a consistent snapshot even if syncWeights()
         * is swapping the model on another thread.
         */
        fun score(number: String): Double = scoreWith(number, state)

        /**
         * Returns true if this number should be flagged as spam by the ML layer.
         * Only returns true at a high-confidence threshold to minimize false positives.
         */
        fun isSpam(number: String): Boolean = verdict(number).isSpam

        /** ML spam confidence as 0–100 integer for display. */
        fun confidence(number: String): Int = verdict(number).confidence

        /**
         * Single-call verdict — feature extraction, tree traversal, and
         * threshold comparison happen once over a consistent [state] snapshot.
         *
         * The call-screening hot path previously called [isSpam] followed by
         * [confidence], which repeated the entire scoring pipeline. Callers on
         * the 5-second deadline should use this instead.
         */
        fun verdict(number: String): Verdict {
            val snap = state
            val s = scoreWith(number, snap)
            val clamped = if (s < 0.0) 0.0 else s.coerceAtMost(1.0)
            return Verdict(
                score = s,
                confidence = (clamped * 100).toInt(),
                isSpam = s >= snap.threshold,
            )
        }

        /** Score using a specific model snapshot. */
        private fun scoreWith(
            number: String,
            snap: ModelState,
        ): Double {
            val features = extractFeatures(number).takeIf { it.isNotEmpty() } ?: return -1.0
            if (snap.useGbt) {
                val trees = snap.gbtTrees
                if (trees != null && trees.isNotEmpty()) {
                    return scoreGbt(
                        features = features,
                        trees = trees,
                        learningRate = snap.gbtLearningRate,
                        initialScore = snap.gbtInitialScore,
                    )
                }
            }
            val w = snap.weights ?: return -1.0
            var z = snap.bias
            val limit = minOf(w.size, features.size)
            for (i in 0 until limit) {
                z += w[i] * features[i]
            }
            return sigmoid(z)
        }

        /**
         * GBT inference: start from sklearn's class-prior log odds, traverse
         * each tree, sum leaf values * learning_rate, then apply sigmoid.
         */
        private fun scoreGbt(
            features: DoubleArray,
            trees: List<GbtTree>,
            learningRate: Double,
            initialScore: Double,
        ): Double {
            var rawScore = initialScore
            for (tree in trees) {
                rawScore += evaluateTree(features, tree) * learningRate
            }
            return sigmoid(rawScore)
        }

        private fun evaluateTree(
            features: DoubleArray,
            tree: GbtTree,
        ): Double {
            var node = 0
            val visited = BooleanArray(tree.feature.size)

            while (node in tree.feature.indices) {
                if (visited[node]) return 0.0
                visited[node] = true

                val featureIdx = tree.feature[node]
                if (featureIdx == GBT_LEAF_NODE) {
                    return tree.value.getOrElse(node) { 0.0 }
                }
                if (featureIdx !in features.indices) return 0.0

                val nextNode =
                    if (features[featureIdx] <= tree.threshold[node]) {
                        tree.childrenLeft[node]
                    } else {
                        tree.childrenRight[node]
                    }
                if (nextNode !in tree.feature.indices) return 0.0
                node = nextNode
            }

            return 0.0
        }

        private fun extractFeatures(number: String): DoubleArray {
            // Check raw number properties before normalizing to 10 digits
            val rawDigits = filterAsciiDigits(number)
            val plusOnePrefix = if (number.trimStart().startsWith("+1")) 1.0 else 0.0
            val shortNumber = if (rawDigits.length in 1..6) 1.0 else 0.0

            var digits = rawDigits
            if (digits.length == 11 && digits.startsWith("1")) digits = digits.drop(1)
            if (digits.length != 10) return doubleArrayOf()

            val npa = digits.substring(0, 3)
            val nxx = digits.substring(3, 6)
            val sub = digits.substring(6) // last 4
            val npanxx = digits.substring(0, 6)
            val nxxInt = nxx.toIntOrNull() ?: 0

            // ── Features 1–8 (original) ──────────────────────────────────
            val tollFree = if (npa in tollFreePrefixes) 1.0 else 0.0
            val highSpamNpa = if (npa in highSpamNpas) 1.0 else 0.0
            val voipRange = if (npanxx in highSpamVoipNpanxx) 1.0 else 0.0

            val digitCounts = digits.groupingBy { it }.eachCount()
            val repeatedRatio = (digitCounts.values.maxOrNull() ?: 1) / 10.0

            val seqAsc = (0 until 9).count { digits[it + 1].digitToInt() == digits[it].digitToInt() + 1 }
            val seqAscRatio = seqAsc / 9.0

            val allSame = if (digits.toSet().size == 1) 1.0 else 0.0
            val is555 = if (nxx == "555") 1.0 else 0.0
            val last4Zero = if (sub == "0000") 1.0 else 0.0

            // ── Features 9–15 ───────────────────────────────────────────
            // NXX must start with 2–9 in NANP; 0 or 1 = spoofed/invalid
            val invalidNxx = if (nxx.firstOrNull().let { it == '0' || it == '1' }) 1.0 else 0.0

            // Subscriber (last 4) all same digit: 9999, 1111, etc.
            val subAllSame = if (sub.toSet().size == 1) 1.0 else 0.0

            // Alternating: even-indexed and odd-indexed digits are each uniform
            // but different from each other (e.g. 5050505050, 1212121212)
            val evenSet = (0 until 10 step 2).map { digits[it] }.toSet()
            val oddSet = (1 until 10 step 2).map { digits[it] }.toSet()
            val alternating = if (evenSet.size == 1 && oddSet.size == 1 && evenSet != oddSet) 1.0 else 0.0

            val seqDesc = (0 until 9).count { digits[it + 1].digitToInt() == digits[it].digitToInt() - 1 }
            val seqDescRatio = seqDesc / 9.0

            // NXX below 200 — many unassigned or rarely-used-legitimately ranges
            val nxxBelow200 = if (nxxInt < 200) 1.0 else 0.0

            // Low entropy — fewer than 4 distinct digits in the entire number
            val lowEntropy = if (digitCounts.size < 4) 1.0 else 0.0

            // Subscriber sequential — last 4 form a fully ascending or descending run
            val subAsc = (0 until 3).count { sub[it + 1].digitToInt() == sub[it].digitToInt() + 1 }
            val subDesc = (0 until 3).count { sub[it + 1].digitToInt() == sub[it].digitToInt() - 1 }
            val subSeq = if (subAsc == 3 || subDesc == 3) 1.0 else 0.0

            // ── Features 16–20 (behavioral & temporal) ──────────────────
            // Cyclical time-of-day encoding using current hour
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val timeAngle = 2.0 * Math.PI * hour / 24.0
            val timeSin = sin(timeAngle)
            val timeCos = cos(timeAngle)

            // "geographic_distance" feature (model position 18). NOTE: this is
            // NOT true user-relative geographic distance — NPA codes are not
            // ordered by geography. It is a coarse proxy: whether the caller's
            // NPA is numerically far (>200) from a fixed central reference
            // (312). It fires only when the NPA resolves to a known US area
            // code. A genuinely user-relative feature (distance from the user's
            // home area code) is roadmap item 2.2.4; the feature name/position
            // is kept stable so existing trained model weights stay valid.
            val callerNpa = npa.toIntOrNull() ?: 0
            val callerLocation = AreaCodeLookup.lookup(number)
            val geoDist =
                if (callerLocation != null) {
                    val refNpa = REFERENCE_NPA
                    if (kotlin.math.abs(callerNpa - refNpa) > 200) 1.0 else 0.0
                } else {
                    0.0
                }

            return doubleArrayOf(
                tollFree,
                highSpamNpa,
                voipRange,
                repeatedRatio,
                seqAscRatio,
                allSame,
                is555,
                last4Zero,
                invalidNxx,
                subAllSame,
                alternating,
                seqDescRatio,
                nxxBelow200,
                lowEntropy,
                subSeq,
                // New behavioral & temporal features (16–20)
                timeSin,
                timeCos,
                geoDist,
                shortNumber,
                plusOnePrefix,
            )
        }

        private fun sigmoid(x: Double): Double =
            if (x >= 0) {
                1.0 / (1.0 + exp(-x))
            } else {
                val e = exp(x)
                e / (1.0 + e)
            }

        private fun defaultModelState(): ModelState =
            ModelState(
                useGbt = false,
                gbtTrees = null,
                gbtLearningRate = 0.1,
                gbtInitialScore = 0.0,
                weights =
                    doubleArrayOf(
                        1.2, // toll_free
                        0.8, // high_spam_npa
                        1.8, // voip_range
                        1.5, // repeated_digits_ratio
                        0.6, // sequential_asc_ratio
                        2.1, // all_same_digit
                        1.4, // nxx_555
                        0.9, // last4_zero
                        2.0, // invalid_nxx
                        1.2, // subscriber_all_same
                        1.8, // alternating_pattern
                        0.5, // sequential_desc_ratio
                        1.0, // nxx_below_200
                        1.5, // low_digit_entropy
                        0.8, // subscriber_sequential
                        0.3, // time_of_day_sin
                        0.3, // time_of_day_cos
                        0.6, // geographic_distance
                        1.0, // short_number
                        0.1, // plus_one_prefix
                    ),
                bias = -2.5,
                threshold = 0.7,
            )

        /**
         * Pure parser: builds a ModelState from a JSON string without mutating
         * [state]. Returns null if the payload can't be understood at all.
         *
         * Returning the built value instead of committing lets callers avoid a
         * race where a concurrent score() call briefly observes default weights
         * between "oops, bad JSON" and "restore snapshot".
         */
        private fun parseModel(json: String): ModelState? {
            return try {
                val versionMatch = Regex(""""version"\s*:\s*(\d+)""").find(json)
                val modelTypeMatch = Regex(""""model_type"\s*:\s*"(\w+)"""").find(json)
                val thresholdMatch = Regex(""""threshold"\s*:\s*([\d.]+)""").find(json)
                val initialScoreMatch = Regex(""""initial_score"\s*:\s*([-\d.eE]+)""").find(json)

                val version = versionMatch?.groupValues?.get(1)?.toIntOrNull() ?: 2
                val modelType = modelTypeMatch?.groupValues?.get(1) ?: ""
                val parsedThreshold = thresholdMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.7
                val parsedInitialScore = initialScoreMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

                // Always try to load fallback LR weights (used by v2 or as fallback for v3)
                val fallback = parseFallbackWeights(json, version)

                if (version >= 3 && modelType == "gbt") {
                    val trees = parseGbtTreesList(json)
                    if (trees != null) {
                        val lrMatch = Regex(""""learning_rate"\s*:\s*([\d.]+)""").find(json)
                        val lr = lrMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.1
                        return ModelState(
                            useGbt = true,
                            gbtTrees = trees,
                            gbtLearningRate = lr,
                            gbtInitialScore = parsedInitialScore,
                            weights = fallback?.first,
                            bias = fallback?.second ?: -2.5,
                            threshold = parsedThreshold,
                        )
                    }
                }

                if (fallback != null) {
                    return ModelState(
                        useGbt = false,
                        gbtTrees = null,
                        gbtLearningRate = 0.1,
                        gbtInitialScore = 0.0,
                        weights = fallback.first,
                        bias = fallback.second,
                        threshold = parsedThreshold,
                    )
                }

                null
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Parse logistic regression weights from JSON without mutating state.
         * Returns (weights, bias) or null if parsing fails.
         * For v2: reads "weights" array and "bias".
         * For v3: reads "fallback_weights" object and "fallback_bias".
         */
        internal fun parseFallbackWeights(
            json: String,
            version: Int,
        ): Pair<DoubleArray, Double>? {
            if (version >= 3) {
                // v3 stores LR weights as fallback_weights object: {"feature_name": weight, ...}
                val fbWeightsMatch = Regex(""""fallback_weights"\s*:\s*\{([^}]+)\}""").find(json)
                val fbBiasMatch = Regex(""""fallback_bias"\s*:\s*([-\d.eE]+)""").find(json)

                if (fbWeightsMatch != null) {
                    val pairs =
                        Regex(""""(\w+)"\s*:\s*([-\d.eE]+)""")
                            .findAll(fbWeightsMatch.groupValues[1])
                            .map { it.groupValues[1] to it.groupValues[2].toDouble() }
                            .toMap()

                    val featureNames =
                        listOf(
                            "toll_free",
                            "high_spam_npa",
                            "voip_range",
                            "repeated_digits_ratio",
                            "sequential_asc_ratio",
                            "all_same_digit",
                            "nxx_555",
                            "last4_zero",
                            "invalid_nxx",
                            "subscriber_all_same",
                            "alternating_pattern",
                            "sequential_desc_ratio",
                            "nxx_below_200",
                            "low_digit_entropy",
                            "subscriber_sequential",
                            "time_of_day_sin",
                            "time_of_day_cos",
                            "geographic_distance",
                            "short_number",
                            "plus_one_prefix",
                        )
                    // Count how many of the named weights were actually present
                    // in the parsed block. The old guard checked
                    // `parsedWeights.size >= 15`, but `featureNames.map { ... }`
                    // always yields exactly 20 entries, so that guard was always
                    // true — a `fallback_weights` block whose keys failed to
                    // parse silently produced an all-zero LR (constant output,
                    // never spam). Require a minimum count of *present* named
                    // weights instead so a malformed block is rejected here and
                    // falls through to the v2-style / null path.
                    val presentCount = featureNames.count { pairs.containsKey(it) }
                    val parsedWeights = featureNames.map { pairs[it] ?: 0.0 }.toDoubleArray()

                    if (presentCount >= MIN_FALLBACK_NAMED_WEIGHTS) {
                        val bias = fbBiasMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: -2.5
                        return parsedWeights to bias
                    }
                }
                // If fallback_weights parsing fails, try v2-style as last resort
            }

            // v2-style: "weights": [array] and "bias": value.
            // The leading `"` in the pattern anchors on the key boundary so this
            // never eats v3's "fallback_weights" or tree "value" arrays.
            val weightsMatch = Regex(""""weights"\s*:\s*\[([^\]]+)]""").find(json)
            val biasMatch = Regex(""""bias"\s*:\s*([-\d.eE]+)""").find(json)

            val parsedWeights =
                weightsMatch
                    ?.groupValues
                    ?.get(1)
                    ?.split(",")
                    ?.mapNotNull { it.trim().toDoubleOrNull() }
                    ?.toDoubleArray()

            if (parsedWeights != null && (parsedWeights.size >= 15)) {
                // Backward compat: pad old 15-weight models to 20 with zeros
                val w =
                    if (parsedWeights.size < 20) {
                        parsedWeights + DoubleArray(20 - parsedWeights.size)
                    } else {
                        parsedWeights
                    }
                val bias = biasMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: -2.5
                return w to bias
            }
            return null
        }

        /**
         * Parse the "trees" array from the v3 JSON model.
         * Each tree object has: feature, threshold, children_left, children_right, value
         * (all integer/double arrays).
         *
         * Returns the parsed list, or null if parsing failed.
         */
        private fun parseGbtTreesList(json: String): List<GbtTree>? {
            try {
                val treesStart = json.indexOf("\"trees\"")
                if (treesStart == -1) return null

                val arrStart = json.indexOf('[', treesStart + 7)
                if (arrStart == -1) return null

                val arrEnd = findMatchingBracket(json, arrStart, '[', ']')
                if (arrEnd == -1) return null

                val treesJson = json.substring(arrStart + 1, arrEnd)

                val parsedTrees = mutableListOf<GbtTree>()
                var pos = 0
                while (pos < treesJson.length) {
                    val objStart = treesJson.indexOf('{', pos)
                    if (objStart == -1) break
                    val objEnd = findMatchingBracket(treesJson, objStart, '{', '}')
                    if (objEnd == -1) break

                    val treeObj = treesJson.substring(objStart, objEnd + 1)
                    val tree = parseOneTree(treeObj) ?: return null
                    parsedTrees.add(tree)
                    pos = objEnd + 1
                }

                return parsedTrees.takeIf { it.isNotEmpty() }
            } catch (_: Exception) {
                return null
            }
        }

        /**
         * Parse a single tree object JSON string into a GbtTree.
         */
        private fun parseOneTree(treeJson: String): GbtTree? {
            val feature = parseIntArray(treeJson, "feature") ?: return null
            val thresh = parseDoubleArray(treeJson, "threshold") ?: return null
            val left = parseIntArray(treeJson, "children_left") ?: return null
            val right = parseIntArray(treeJson, "children_right") ?: return null
            val value = parseDoubleArray(treeJson, "value") ?: return null

            // All arrays must be the same length
            if (feature.size != thresh.size || feature.size != left.size ||
                feature.size != right.size || feature.size != value.size
            ) {
                return null
            }

            return GbtTree(feature, thresh, left, right, value)
        }

        /**
         * Parse a named integer array from a JSON object string.
         * E.g., "feature": [0, 2, -1, -1, 1, -1, -1]
         */
        private fun parseIntArray(
            json: String,
            name: String,
        ): IntArray? {
            val pattern = Regex(""""$name"\s*:\s*\[([^\]]+)]""")
            val match = pattern.find(json) ?: return null
            return match.groupValues[1]
                .split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .toIntArray()
        }

        /**
         * Parse a named double array from a JSON object string.
         * E.g., "threshold": [0.5, 0.5, 0, 0, 0.5, 0, 0]
         */
        private fun parseDoubleArray(
            json: String,
            name: String,
        ): DoubleArray? {
            val pattern = Regex(""""$name"\s*:\s*\[([^\]]+)]""")
            val match = pattern.find(json) ?: return null
            return match.groupValues[1]
                .split(",")
                .mapNotNull { it.trim().toDoubleOrNull() }
                .toDoubleArray()
        }

        /**
         * Find the index of the matching closing bracket for an opening bracket at [startIdx].
         */
        private fun findMatchingBracket(
            s: String,
            startIdx: Int,
            open: Char,
            close: Char,
        ): Int {
            var depth = 0
            var inString = false
            var escape = false
            for (i in startIdx until s.length) {
                val c = s[i]
                when {
                    escape -> {
                        escape = false
                    }

                    c == '\\' -> {
                        escape = true
                    }

                    c == '"' -> {
                        inString = !inString
                    }

                    !inString && c == open -> {
                        depth++
                    }

                    !inString && c == close -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            return -1
        }

        private fun persistModelJson(
            context: Context,
            json: String,
        ) {
            val file = File(context.filesDir, "spam_model_weights.json")
            val tmpFile = File(context.filesDir, "spam_model_weights.json.tmp")
            try {
                tmpFile.writeText(json)
                if (file.exists() && !file.delete()) {
                    file.writeText(json)
                } else if (!tmpFile.renameTo(file)) {
                    file.writeText(json)
                }
            } finally {
                if (tmpFile.exists()) {
                    tmpFile.delete()
                }
            }
        }

        companion object {
            val shared: SpamMLScorer = SpamMLScorer()

            private const val GBT_LEAF_NODE = -2

            /**
             * Fixed reference NPA for the coarse "geographic_distance" feature
             * proxy. Not a real geographic anchor — see the extraction site.
             */
            private const val REFERENCE_NPA = 312

            /**
             * Minimum number of *named* logistic-regression fallback weights
             * that must be present in a v3 `fallback_weights` block before it is
             * accepted. Guards against a malformed block silently producing an
             * all-zero LR (constant output, never spam).
             */
            internal const val MIN_FALLBACK_NAMED_WEIGHTS = 15

            fun loadWeights(context: Context) {
                shared.loadWeights(context)
            }

            suspend fun syncWeights(context: Context) {
                shared.syncWeights(context)
            }

            fun score(number: String): Double = shared.score(number)

            fun isSpam(number: String): Boolean = shared.isSpam(number)

            fun confidence(number: String): Int = shared.confidence(number)

            fun verdict(number: String): Verdict = shared.verdict(number)

            /** Health of the most recent model load/sync. */
            fun modelHealth(): ModelHealth = shared.modelHealth
        }
    }
