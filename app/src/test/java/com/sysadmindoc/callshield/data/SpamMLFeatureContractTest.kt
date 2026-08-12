package com.sysadmindoc.callshield.data

import com.squareup.moshi.Moshi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

/** Keeps the Kotlin extractor and Python trainer on one named feature contract. */
class SpamMLFeatureContractTest {
    private lateinit var scorer: SpamMLScorer
    private lateinit var parseModel: java.lang.reflect.Method
    private lateinit var stateField: java.lang.reflect.Field

    @Before
    fun setUp() {
        scorer = SpamMLScorer()
        parseModel = SpamMLScorer::class.java.getDeclaredMethod("parseModel", String::class.java).also { it.isAccessible = true }
        stateField = SpamMLScorer::class.java.getDeclaredField("state").also { it.isAccessible = true }

        val modelJson = locate("data/spam_model_weights.json").readText()
        val parsed = parseModel.invoke(scorer, modelJson)
        assertNotNull("The shipped model must satisfy the feature schema", parsed)
        stateField.set(scorer, parsed)
    }

    @Test
    fun `Kotlin vectors and model scores match the shared fixture`() {
        val root = loadFixture()
        assertEquals(
            SPAM_ML_FEATURE_SCHEMA_VERSION.toDouble(),
            (root["schema_version"] as Number).toDouble(),
            0.0,
        )
        assertEquals(SPAM_ML_FEATURE_NAMES, (root["feature_names"] as List<*>).map { it as String })

        for (entry in root["cases"] as List<*>) {
            val case = entry as Map<*, *>
            val actual =
                scorer.extractFeaturesForContract(
                    number = case["input"] as String,
                    hourOfDay = (case["hour"] as Double).toInt(),
                )
            val expected = (case["expected"] as List<*>).map { (it as Number).toDouble() }.toDoubleArray()
            assertArrayEquals(case["name"] as String, expected, actual, 1e-12)

            if (case["scoreable"] as Boolean) {
                assertEquals(
                    case["gbt_score"] as Double,
                    scorer.scoreFeaturesForContract(actual, useLogisticFallback = false),
                    1e-12,
                )
                assertEquals(
                    case["lr_score"] as Double,
                    scorer.scoreFeaturesForContract(actual, useLogisticFallback = true),
                    1e-12,
                )
            }
        }
    }

    @Test
    fun `declared model schema mismatch is rejected`() {
        val json = locate("data/spam_model_weights.json").readText()
        val wrongVersion = json.replace("\"feature_schema_version\": 1", "\"feature_schema_version\": 99")
        assertNull(parseModel.invoke(scorer, wrongVersion))

        val wrongNames = json.replace("\"toll_free\"", "\"wrong_feature\"")
        assertNull(parseModel.invoke(scorer, wrongNames))
    }

    private fun loadFixture(): Map<*, *> {
        val json = locate("scripts/ml_feature_fixtures.json").readText()
        val moshi = Moshi.Builder().build()
        return moshi.adapter(Any::class.java).fromJson(json) as Map<*, *>
    }

    private fun locate(relativePath: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("Could not find $relativePath walking up from ${File("").absolutePath}")
    }
}
