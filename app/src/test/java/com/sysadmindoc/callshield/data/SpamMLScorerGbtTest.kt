package com.sysadmindoc.callshield.data

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Unit tests for SpamMLScorer's GBT inference, GbtTree data class,
 * new features 16-20, and backward compatibility.
 */
class SpamMLScorerGbtTest {

    /** Reflection handle for private scoreGbt(DoubleArray, List<*>, Double): Double */
    private lateinit var scoreGbtMethod: Method

    /** Reflection handle for private extractFeatures(String): DoubleArray */
    private lateinit var extractFeaturesMethod: Method

    /** Reflection handle for private sigmoid(Double): Double */
    private lateinit var sigmoidMethod: Method

    /** The private GbtTree class */
    private lateinit var gbtTreeClass: Class<*>

    /** GbtTree constructor */
    private lateinit var gbtTreeConstructor: java.lang.reflect.Constructor<*>

    @Before
    fun setUp() {
        // Locate the private GbtTree inner class
        gbtTreeClass = Class.forName("com.sysadmindoc.callshield.data.SpamMLScorer\$GbtTree")
        gbtTreeConstructor = gbtTreeClass.declaredConstructors.first().also { it.isAccessible = true }

        // scoreGbt(features: DoubleArray, trees: List<GbtTree>, learningRate: Double): Double
        scoreGbtMethod = SpamMLScorer::class.java.getDeclaredMethod(
            "scoreGbt", DoubleArray::class.java, List::class.java, Double::class.javaPrimitiveType
        ).also { it.isAccessible = true }

        // extractFeatures(number: String): DoubleArray
        extractFeaturesMethod = SpamMLScorer::class.java.getDeclaredMethod(
            "extractFeatures", String::class.java
        ).also { it.isAccessible = true }

        // sigmoid(x: Double): Double
        sigmoidMethod = SpamMLScorer::class.java.getDeclaredMethod(
            "sigmoid", Double::class.javaPrimitiveType
        ).also { it.isAccessible = true }
    }

    // ─── Helper: build a GbtTree via reflection ──────────────────────

    private fun makeTree(
        feature: IntArray,
        threshold: DoubleArray,
        childrenLeft: IntArray,
        childrenRight: IntArray,
        value: DoubleArray
    ): Any {
        return gbtTreeConstructor.newInstance(feature, threshold, childrenLeft, childrenRight, value)
    }

    private fun callScoreGbt(features: DoubleArray, trees: List<Any>, learningRate: Double): Double {
        return scoreGbtMethod.invoke(SpamMLScorer, features, trees, learningRate) as Double
    }

    private fun callExtractFeatures(number: String): DoubleArray {
        return extractFeaturesMethod.invoke(SpamMLScorer, number) as DoubleArray
    }

    private fun sigmoid(x: Double): Double {
        return sigmoidMethod.invoke(SpamMLScorer, x) as Double
    }

    // ─── GbtTree data class construction ─────────────────────────────

    @Test
    fun gbtTree_constructionAndFieldAccess() {
        val tree = makeTree(
            feature = intArrayOf(-2),
            threshold = doubleArrayOf(0.0),
            childrenLeft = intArrayOf(-1),
            childrenRight = intArrayOf(-1),
            value = doubleArrayOf(1.5)
        )
        // Access fields via reflection
        val featureField = gbtTreeClass.getDeclaredField("feature").also { it.isAccessible = true }
        val valueField = gbtTreeClass.getDeclaredField("value").also { it.isAccessible = true }

        val featureArr = featureField.get(tree) as IntArray
        val valueArr = valueField.get(tree) as DoubleArray

        assertArrayEquals(intArrayOf(-2), featureArr)
        assertArrayEquals(doubleArrayOf(1.5), valueArr, 0.0001)
    }

    // ─── scoreGbt: single leaf node ──────────────────────────────────

    @Test
    fun scoreGbt_singleLeafNode_returnsKnownValue() {
        // A tree with just one leaf node (feature=-2) that returns value 2.0
        val tree = makeTree(
            feature = intArrayOf(-2),
            threshold = doubleArrayOf(0.0),
            childrenLeft = intArrayOf(-1),
            childrenRight = intArrayOf(-1),
            value = doubleArrayOf(2.0)
        )
        val features = DoubleArray(20) { 0.0 }
        val lr = 1.0
        val result = callScoreGbt(features, listOf(tree), lr)
        // rawScore = 2.0 * 1.0 = 2.0; sigmoid(2.0) = 1/(1+exp(-2))
        val expected = 1.0 / (1.0 + exp(-2.0))
        assertEquals(expected, result, 0.0001)
    }

    // ─── scoreGbt: 2-level tree that branches on a feature ───────────

    @Test
    fun scoreGbt_twoLevelTree_branchesOnFeature() {
        // Tree structure:
        //   Node 0: split on feature[0] <= 0.5 -> left=1, right=2
        //   Node 1: leaf, value = -1.0
        //   Node 2: leaf, value =  3.0
        val tree = makeTree(
            feature = intArrayOf(0, -2, -2),
            threshold = doubleArrayOf(0.5, 0.0, 0.0),
            childrenLeft = intArrayOf(1, -1, -1),
            childrenRight = intArrayOf(2, -1, -1),
            value = doubleArrayOf(0.0, -1.0, 3.0)
        )
        val lr = 1.0

        // Feature[0] = 0.0 <= 0.5, so go left -> leaf value = -1.0
        val featuresLeft = DoubleArray(20) { 0.0 }
        featuresLeft[0] = 0.0
        val resultLeft = callScoreGbt(featuresLeft, listOf(tree), lr)
        assertEquals(sigmoid(-1.0), resultLeft, 0.0001)

        // Feature[0] = 1.0 > 0.5, so go right -> leaf value = 3.0
        val featuresRight = DoubleArray(20) { 0.0 }
        featuresRight[0] = 1.0
        val resultRight = callScoreGbt(featuresRight, listOf(tree), lr)
        assertEquals(sigmoid(3.0), resultRight, 0.0001)
    }

    // ─── scoreGbt: multiple trees sum their contributions ────────────

    @Test
    fun scoreGbt_multipleTrees_sumContributions() {
        // Two single-leaf trees with values 1.0 and 0.5, learning_rate = 0.5
        val tree1 = makeTree(
            feature = intArrayOf(-2),
            threshold = doubleArrayOf(0.0),
            childrenLeft = intArrayOf(-1),
            childrenRight = intArrayOf(-1),
            value = doubleArrayOf(1.0)
        )
        val tree2 = makeTree(
            feature = intArrayOf(-2),
            threshold = doubleArrayOf(0.0),
            childrenLeft = intArrayOf(-1),
            childrenRight = intArrayOf(-1),
            value = doubleArrayOf(0.5)
        )
        val features = DoubleArray(20) { 0.0 }
        val lr = 0.5
        val result = callScoreGbt(features, listOf(tree1, tree2), lr)
        // rawScore = 1.0*0.5 + 0.5*0.5 = 0.75; sigmoid(0.75)
        assertEquals(sigmoid(0.75), result, 0.0001)
    }

    // ─── scoreGbt: empty tree list => sigmoid(0) = 0.5 ──────────────

    @Test
    fun scoreGbt_emptyTreeList_returnsSigmoidZero() {
        val features = DoubleArray(20) { 0.0 }
        val result = callScoreGbt(features, emptyList<Any>(), 0.1)
        assertEquals(0.5, result, 0.0001)
    }

    // ─── scoreGbt: bounds safety — invalid node indices ──────────────

    @Test
    fun scoreGbt_invalidNodeIndices_doesNotCrash() {
        // Tree where left/right child point out of bounds
        // Node 0: split on feature[0] <= 0.5 -> left=99 (out of bounds), right=98
        val tree = makeTree(
            feature = intArrayOf(0),
            threshold = doubleArrayOf(0.5),
            childrenLeft = intArrayOf(99),
            childrenRight = intArrayOf(98),
            value = doubleArrayOf(1.0)
        )
        val features = DoubleArray(20) { 0.0 }
        // Should not crash — the while loop has a bounds check
        val result = callScoreGbt(features, listOf(tree), 1.0)
        // node=0, feature[0]=0 (not -2), featureIdx=0 valid, features[0]=0.0 <= 0.5,
        // go left to node=99 which is >= feature.size(1), so break.
        // value.getOrElse(99) { 0.0 } = 0.0, rawScore = 0.0*1.0 = 0.0
        assertEquals(0.5, result, 0.0001)
    }

    @Test
    fun scoreGbt_treeWithInvalidFeatureIndex_doesNotCrash() {
        // Tree node references feature index beyond feature array bounds
        val tree = makeTree(
            feature = intArrayOf(999, -2, -2),
            threshold = doubleArrayOf(0.5, 0.0, 0.0),
            childrenLeft = intArrayOf(1, -1, -1),
            childrenRight = intArrayOf(2, -1, -1),
            value = doubleArrayOf(0.0, -1.0, 3.0)
        )
        val features = DoubleArray(20) { 0.0 }
        // featureIdx=999 >= features.size(20), so breaks at node=0
        // value.getOrElse(0) { 0.0 } = 0.0, rawScore = 0.0
        val result = callScoreGbt(features, listOf(tree), 1.0)
        assertEquals(0.5, result, 0.0001)
    }

    @Test
    fun scoreGbt_negativeFeatureIndex_notLeaf_breaks() {
        // feature[0] = -5 which is < 0 but not -2 (not a leaf), so featureIdx<0 triggers break
        val tree = makeTree(
            feature = intArrayOf(-5),
            threshold = doubleArrayOf(0.0),
            childrenLeft = intArrayOf(-1),
            childrenRight = intArrayOf(-1),
            value = doubleArrayOf(7.0)
        )
        val features = DoubleArray(20) { 0.0 }
        // Malformed split nodes should fail closed instead of contributing arbitrary values.
        val result = callScoreGbt(features, listOf(tree), 1.0)
        assertEquals(0.5, result, 0.0001)
    }

    @Test
    fun scoreGbt_circularChildren_returnsNeutralScore() {
        val tree = makeTree(
            feature = intArrayOf(0, 0, 0),
            threshold = doubleArrayOf(0.5, 0.5, 0.5),
            childrenLeft = intArrayOf(1, 2, 0),
            childrenRight = intArrayOf(2, 0, 1),
            value = doubleArrayOf(0.0, 5.0, -4.0)
        )
        val features = DoubleArray(20) { 0.0 }

        val result = callScoreGbt(features, listOf(tree), 1.0)

        assertEquals(0.5, result, 0.0001)
    }

    // ─── New features 16-20 ──────────────────────────────────────────

    @Test
    fun feature_timeOfDaySin_variesWithHour() {
        // We can't control the clock, but we can verify the feature is set
        // to sin(2*PI*hour/24) based on current hour
        val features = callExtractFeatures("2125551234")
        assertTrue("Expected 20 features", features.size == 20)

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val expectedSin = sin(2.0 * Math.PI * hour / 24.0)
        assertEquals(expectedSin, features[15], 0.0001) // index 15 = time_of_day_sin
    }

    @Test
    fun feature_timeOfDayCos_variesWithHour() {
        val features = callExtractFeatures("2125551234")
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val expectedCos = cos(2.0 * Math.PI * hour / 24.0)
        assertEquals(expectedCos, features[16], 0.0001) // index 16 = time_of_day_cos
    }

    @Test
    fun feature_timeOfDay_sinCos_formUnitCircle() {
        val features = callExtractFeatures("2125551234")
        val sinVal = features[15]
        val cosVal = features[16]
        // sin^2 + cos^2 = 1
        assertEquals(1.0, sinVal * sinVal + cosVal * cosVal, 0.0001)
    }

    @Test
    fun feature_geographicDistance_farAreaCode() {
        // NPA=917, reference=312. |917-312|=605 > 200 => geo=1.0
        // (Only if AreaCodeLookup returns non-null for 917, which is a valid NYC code)
        val features = callExtractFeatures("9175551234")
        // Feature 17 = geographic_distance. Value depends on AreaCodeLookup.
        // We just verify it's 0.0 or 1.0
        assertTrue(features[17] == 0.0 || features[17] == 1.0)
    }

    @Test
    fun feature_shortNumber_shortCode() {
        // Short codes (< 7 digits) should return empty features (early return in extractFeatures)
        // because after normalization they won't be 10 digits.
        // The shortNumber feature is set BEFORE the 10-digit check but the function returns empty
        // if not 10 digits. So short numbers get an empty array.
        val features = callExtractFeatures("72345")
        assertEquals(0, features.size) // Can't be normalized to 10 digits
    }

    @Test
    fun feature_shortNumber_normalNumber_isZero() {
        val features = callExtractFeatures("2125551234")
        assertEquals(0.0, features[18], 0.0001) // index 18 = short_number
    }

    @Test
    fun feature_plusOnePrefix_withPlus1() {
        val features = callExtractFeatures("+1 2125551234")
        assertEquals(1.0, features[19], 0.0001) // index 19 = plus_one_prefix
    }

    @Test
    fun feature_plusOnePrefix_withoutPlus1() {
        val features = callExtractFeatures("2125551234")
        assertEquals(0.0, features[19], 0.0001)
    }

    @Test
    fun feature_plusOnePrefix_plainElevenDigit() {
        // "12125551234" — starts with 1 but no "+" prefix
        val features = callExtractFeatures("12125551234")
        assertEquals(0.0, features[19], 0.0001)
    }

    // ─── extractFeatures returns correct count ───────────────────────

    @Test
    fun extractFeatures_validNumber_returns20Features() {
        val features = callExtractFeatures("2125551234")
        assertEquals(20, features.size)
    }

    @Test
    fun extractFeatures_invalidNumber_returnsEmpty() {
        val features = callExtractFeatures("123")
        assertEquals(0, features.size)
    }

    @Test
    fun extractFeatures_emptyString_returnsEmpty() {
        val features = callExtractFeatures("")
        assertEquals(0, features.size)
    }

    // ─── Backward compatibility: 15-feature weights padded to 20 ────

    /** Read the live ModelState's `weights` array via reflection. */
    private fun currentWeights(): DoubleArray? {
        val stateField = SpamMLScorer::class.java.getDeclaredField("state").also { it.isAccessible = true }
        val state = stateField.get(SpamMLScorer) ?: return null
        val weightsField = state::class.java.getDeclaredField("weights").also { it.isAccessible = true }
        return weightsField.get(state) as DoubleArray?
    }

    /** Parse JSON with parseModel and commit the resulting state. */
    private fun parseAndCommit(json: String): Boolean {
        val parseModel = SpamMLScorer::class.java.getDeclaredMethod("parseModel", String::class.java)
            .also { it.isAccessible = true }
        val parsed = parseModel.invoke(SpamMLScorer, json) ?: return false
        val stateField = SpamMLScorer::class.java.getDeclaredField("state").also { it.isAccessible = true }
        stateField.set(SpamMLScorer, parsed)
        return true
    }

    @Test
    fun backwardCompat_15featureWeights_paddedToTwenty() {
        val json = """
        {
            "version": 2,
            "weights": [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0, 13.0, 14.0, 15.0],
            "bias": -1.5
        }
        """.trimIndent()

        assertTrue("parseModel should succeed for valid v2 JSON", parseAndCommit(json))

        val weights = currentWeights()
        assertNotNull(weights)
        assertEquals(20, weights!!.size)
        // First 15 should be the original values
        assertEquals(1.0, weights[0], 0.0001)
        assertEquals(15.0, weights[14], 0.0001)
        // Padded positions 15-19 should be 0.0
        for (i in 15..19) {
            assertEquals("Padded weight at index $i should be 0.0", 0.0, weights[i], 0.0001)
        }
    }

    @Test
    fun backwardCompat_20featureWeights_notPadded() {
        val weightsStr = (1..20).joinToString(", ") { "$it.0" }
        val json = """
        {
            "version": 2,
            "weights": [$weightsStr],
            "bias": -2.0
        }
        """.trimIndent()

        assertTrue("parseModel should succeed for valid v2 JSON", parseAndCommit(json))

        val weights = currentWeights()
        assertNotNull(weights)
        assertEquals(20, weights!!.size)
        assertEquals(20.0, weights[19], 0.0001)
    }

    // ─── Sigmoid sanity ──────────────────────────────────────────────

    @Test
    fun sigmoid_zero_returnsHalf() {
        assertEquals(0.5, sigmoid(0.0), 0.0001)
    }

    @Test
    fun sigmoid_largePositive_nearOne() {
        assertTrue(sigmoid(10.0) > 0.999)
    }

    @Test
    fun sigmoid_largeNegative_nearZero() {
        assertTrue(sigmoid(-10.0) < 0.001)
    }

    // ─── scoreGbt with learning rate scaling ─────────────────────────

    @Test
    fun scoreGbt_learningRate_scalesContributions() {
        val tree = makeTree(
            feature = intArrayOf(-2),
            threshold = doubleArrayOf(0.0),
            childrenLeft = intArrayOf(-1),
            childrenRight = intArrayOf(-1),
            value = doubleArrayOf(4.0)
        )
        val features = DoubleArray(20) { 0.0 }

        // lr=1.0: rawScore=4.0
        val result1 = callScoreGbt(features, listOf(tree), 1.0)
        // lr=0.1: rawScore=0.4
        val result01 = callScoreGbt(features, listOf(tree), 0.1)

        assertEquals(sigmoid(4.0), result1, 0.0001)
        assertEquals(sigmoid(0.4), result01, 0.0001)
        // Higher learning rate -> higher score for positive value
        assertTrue(result1 > result01)
    }
}
