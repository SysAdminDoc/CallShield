package com.sysadmindoc.callshield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure model-health classifiers that turn a silent LR fallback into an
 * observable state. The load/sync side effects need a Context (instrumented),
 * but the classification logic is pure and covered here.
 */
class ModelHealthTest {
    @Test
    fun `jsonDeclaresGbt true only for v3+ gbt`() {
        assertTrue(jsonDeclaresGbt("""{"version":3,"model_type":"gbt"}"""))
        assertTrue(jsonDeclaresGbt("""{"version":4,"model_type":"gbt"}"""))
        assertFalse(jsonDeclaresGbt("""{"version":2,"model_type":"logistic"}"""))
        assertFalse(jsonDeclaresGbt("""{"version":3,"model_type":"logistic"}"""))
        assertFalse(jsonDeclaresGbt("{}")) // defaults to version 2
    }

    @Test
    fun `parse failure maps to PARSE_FAILED`() {
        assertEquals(
            ModelHealth.PARSE_FAILED,
            modelHealthFor(parseSucceeded = false, usingGbt = false, declaredGbt = true),
        )
    }

    @Test
    fun `active gbt maps to GBT_ACTIVE`() {
        assertEquals(
            ModelHealth.GBT_ACTIVE,
            modelHealthFor(parseSucceeded = true, usingGbt = true, declaredGbt = true),
        )
    }

    @Test
    fun `declared gbt but running lr maps to DEGRADED_TO_LR`() {
        assertEquals(
            ModelHealth.DEGRADED_TO_LR,
            modelHealthFor(parseSucceeded = true, usingGbt = false, declaredGbt = true),
        )
    }

    @Test
    fun `intended lr model maps to LR_ACTIVE`() {
        assertEquals(
            ModelHealth.LR_ACTIVE,
            modelHealthFor(parseSucceeded = true, usingGbt = false, declaredGbt = false),
        )
    }
}
