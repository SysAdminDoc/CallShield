package com.sysadmindoc.callshield.domain.model

/**
 * Privacy-safe evidence that a screening decision did not evaluate the whole
 * checker chain. Only stable checker names and counts are retained; exception
 * messages and call content never cross this boundary.
 */
data class ScreeningDiagnostics(
    val budgetExhausted: Boolean = false,
    val cutoffChecker: String? = null,
    val unevaluatedCheckers: List<String> = emptyList(),
    val failedCheckers: List<String> = emptyList(),
) {
    val hasIssues: Boolean
        get() = budgetExhausted || failedCheckers.isNotEmpty()

    /** Stable, export-safe representation for the Room log and CSV export. */
    fun toWireValue(): String? {
        if (!hasIssues) return null
        val fields = mutableListOf<String>()
        if (budgetExhausted) fields += "budget_exhausted"
        cutoffChecker?.takeIf(String::isNotBlank)?.let { fields += "cutoff=$it" }
        if (unevaluatedCheckers.isNotEmpty()) {
            fields += "unevaluated=${unevaluatedCheckers.joinToString(",")}"
        }
        if (failedCheckers.isNotEmpty()) fields += "checker_errors=${failedCheckers.joinToString(",")}"
        return fields.joinToString("|")
    }

    fun merge(other: ScreeningDiagnostics?): ScreeningDiagnostics {
        if (other == null) return this
        return ScreeningDiagnostics(
            budgetExhausted = budgetExhausted || other.budgetExhausted,
            cutoffChecker = cutoffChecker ?: other.cutoffChecker,
            unevaluatedCheckers = (unevaluatedCheckers + other.unevaluatedCheckers).distinct(),
            failedCheckers = (failedCheckers + other.failedCheckers).distinct(),
        )
    }

    companion object {
        fun merge(
            first: ScreeningDiagnostics?,
            second: ScreeningDiagnostics?,
        ): ScreeningDiagnostics? {
            val merged = first?.merge(second) ?: second
            return merged?.takeIf { it.hasIssues }
        }
    }
}
