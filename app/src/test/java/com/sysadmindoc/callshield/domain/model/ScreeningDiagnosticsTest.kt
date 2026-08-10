package com.sysadmindoc.callshield.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreeningDiagnosticsTest {
    @Test
    fun `wire value records cutoff and checker failures without exception text`() {
        val diagnostics =
            ScreeningDiagnostics(
                budgetExhausted = true,
                cutoffChecker = "ml_scorer",
                unevaluatedCheckers = listOf("ml_scorer", "sms_content"),
                failedCheckers = listOf("heuristic"),
            )

        assertEquals(
            "budget_exhausted|cutoff=ml_scorer|unevaluated=ml_scorer,sms_content|checker_errors=heuristic",
            diagnostics.toWireValue(),
        )
        assertFalse(diagnostics.toWireValue().orEmpty().contains("Exception"))
    }

    @Test
    fun `merge preserves unique stages and returns null for healthy runs`() {
        val first = ScreeningDiagnostics(failedCheckers = listOf("database"))
        val second = ScreeningDiagnostics(budgetExhausted = true, unevaluatedCheckers = listOf("ml_scorer"))

        val merged = ScreeningDiagnostics.merge(first, second)

        assertTrue(merged?.budgetExhausted == true)
        assertEquals(listOf("ml_scorer"), merged?.unevaluatedCheckers)
        assertEquals(listOf("database"), merged?.failedCheckers)
        assertNull(ScreeningDiagnostics.merge(null, null))
    }
}
