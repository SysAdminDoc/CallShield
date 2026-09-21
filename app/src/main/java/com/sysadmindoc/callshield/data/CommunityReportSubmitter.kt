package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.CommunityContributor.ContributeOutcome
import com.sysadmindoc.callshield.data.CommunityContributor.ContributeResult
import com.sysadmindoc.callshield.data.SmsContentAnalyzer.SmsReportIndicators
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * One report on its way from a tap to the Worker. The id stays the same for
 * every attempt, so the Worker and the pipeline can recognise a resend of a
 * report that was already stored.
 */
internal data class CommunityReport(
    val id: String,
    val number: String,
    val type: String,
    val indicators: SmsReportIndicators?,
)

/**
 * Sends a community report at most once a day per number and vote, and sees
 * that a claimed report is delivered, left in the outbox, or released for
 * another try.
 *
 * The claim comes first, so a second tap while the first is in flight is
 * suppressed too. The report goes into the outbox before the direct attempt:
 * if the caller is cancelled or the process dies mid-send, the queued copy
 * still goes out, carrying the same id. A delivered report, or one refused
 * for good, takes its queued copy back out, and a refusal also releases the
 * claim so the user isn't told it was already sent.
 */
internal class CommunityReportSubmitter(
    private val claim: suspend (number: String, vote: String, now: Long) -> Boolean,
    private val release: suspend (number: String, vote: String) -> Unit,
    private val transport: suspend (CommunityReport) -> ContributeResult,
    private val enqueue: suspend (CommunityReport) -> Unit,
    private val dequeue: suspend (CommunityReport) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun submit(
        number: String,
        type: String,
        indicators: SmsReportIndicators?,
    ): ContributeResult {
        val normalized =
            CommunityContributor.normalizeForReport(number)
                ?: return ContributeResult(false, "Invalid number", ContributeOutcome.INVALID_NUMBER)
        val vote = CommunityReportLedger.voteOf(type)
        if (!claim(normalized, vote, clock())) {
            // The community already has this report from this device, so it
            // counts as done rather than as a failure.
            return ContributeResult(true, "Already submitted", ContributeOutcome.ALREADY_SUBMITTED)
        }
        val report = CommunityReport(newId(), normalized, type, indicators)
        val queued =
            try {
                enqueue(report)
                true
            } catch (e: CancellationException) {
                withContext(NonCancellable) { release(normalized, vote) }
                throw e
            } catch (_: Exception) {
                // WorkManager couldn't take it. The direct attempt below still
                // runs; it just has no safety net.
                false
            }
        val result = transport(report)
        return when {
            result.success -> {
                withContext(NonCancellable) { if (queued) dequeue(report) }
                result
            }

            !result.outcome.isTransient || !queued -> {
                withContext(NonCancellable) {
                    if (queued) dequeue(report)
                    release(normalized, vote)
                }
                result
            }

            else -> {
                ContributeResult(true, "Queued after: ${result.message}", ContributeOutcome.QUEUED, result.retryAfterSeconds)
            }
        }
    }
}
