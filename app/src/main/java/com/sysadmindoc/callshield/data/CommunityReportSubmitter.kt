package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.CommunityContributor.ContributeOutcome
import com.sysadmindoc.callshield.data.CommunityContributor.ContributeResult
import com.sysadmindoc.callshield.data.SmsContentAnalyzer.SmsReportIndicators

/**
 * Sends a community report at most once a day per number and vote type, and
 * hands one it can't send now to the outbox instead of dropping it.
 *
 * The claim comes before the network, so a second tap while the first is
 * still in flight is suppressed too. A report that was claimed and then
 * queued stays claimed: the outbox will deliver it.
 */
internal class CommunityReportSubmitter(
    private val claim: suspend (number: String, type: String, now: Long) -> Boolean,
    private val transport: suspend (number: String, type: String, indicators: SmsReportIndicators?) -> ContributeResult,
    private val enqueue: (number: String, type: String, indicators: SmsReportIndicators?) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun submit(
        number: String,
        type: String,
        indicators: SmsReportIndicators?,
    ): ContributeResult {
        val normalized =
            CommunityContributor.normalizeForReport(number)
                ?: return ContributeResult(false, "Invalid number", ContributeOutcome.INVALID_NUMBER)
        if (!claim(normalized, type, clock())) {
            // The community already has this report from this device, so it
            // counts as done rather than as a failure.
            return ContributeResult(true, "Already submitted", ContributeOutcome.ALREADY_SUBMITTED)
        }
        val result = transport(normalized, type, indicators)
        if (!result.outcome.isTransient) return result
        enqueue(normalized, type, indicators)
        return ContributeResult(true, "Queued after: ${result.message}", ContributeOutcome.QUEUED, result.retryAfterSeconds)
    }
}
