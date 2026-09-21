package com.sysadmindoc.callshield.data

import android.content.Context
import com.sysadmindoc.callshield.data.remote.HttpClient
import com.sysadmindoc.callshield.service.CommunityReportWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * One-tap anonymous community contribution.
 * Sends reports to the CallShield Cloudflare Worker,
 * which stores them in data/reports/ via GitHub API.
 * No user account or API key needed from the app side.
 *
 * Supports both spam reports AND false positive reports ("not_spam").
 *
 * A number and vote is sent at most once a day ([CommunityReportLedger]). Each
 * report waits in [CommunityReportWorker] while it is sent, so one that can't
 * go out now (offline, rate-limited, a server error, a cancelled or killed
 * sender) is delivered once the network is back instead of being dropped.
 */
object CommunityContributor {
    internal const val WORKER_URL = "https://callshield-reports.snafumatthew.workers.dev"
    private const val MAX_SMS_REPORT_DOMAINS = 10
    private const val MAX_SMS_URL_INDICATORS = 10
    private const val MIN_SMS_REPORT_DOMAIN_LENGTH = 5
    private const val MAX_SMS_REPORT_DOMAIN_LENGTH = 253
    private const val MAX_SMS_REPORT_DOMAIN_LABEL_LENGTH = 63
    private const val HTTP_BAD_REQUEST = 400
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val DEFAULT_RETRY_AFTER_SECONDS = 60
    private const val ERROR_BODY_PEEK_BYTES = 1024L

    /**
     * The Worker's answer to a report it already stored. A Worker older than
     * this marker wrote its duplicate record before storing the report, so
     * its bare "Duplicate report" can mean the report was lost; that answer
     * is retried like any rate limit.
     */
    private val alreadyStoredPattern = Regex(""""already_stored"\s*:\s*true""")

    private val reportDomainPattern = Regex("^[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?$")
    private val urlIndicatorPattern = Regex("^[a-z_]{3,40}$")

    private val client =
        HttpClient.shared
            .newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

    /**
     * Typed outcome so the UI can localize and color-code without sniffing
     * English substrings out of [ContributeResult.message] (which is
     * diagnostics-only).
     */
    enum class ContributeOutcome {
        REPORTED_SPAM,
        REPORTED_NOT_SPAM,
        INVALID_NUMBER,
        RATE_LIMITED,
        SERVER_ERROR,
        NETWORK_ERROR,

        /** The same number and vote type went out in the last day; nothing was sent. */
        ALREADY_SUBMITTED,

        /** Couldn't be sent now; the outbox delivers it when the network allows. */
        QUEUED,
        ;

        /** A failure that trying again later can fix. */
        val isTransient: Boolean get() = this == RATE_LIMITED || this == SERVER_ERROR || this == NETWORK_ERROR
    }

    data class ContributeResult(
        val success: Boolean,
        val message: String,
        val outcome: ContributeOutcome,
        /** Only meaningful for [ContributeOutcome.RATE_LIMITED]. */
        val retryAfterSeconds: Int = 0,
    )

    /**
     * Report a number as spam.
     */
    suspend fun contribute(
        context: Context,
        number: String,
        type: String = "spam",
        smsIndicators: SmsContentAnalyzer.SmsReportIndicators? = null,
    ): ContributeResult = submitter(context).submit(number, type, smsIndicators)

    /**
     * Report a false positive: this number is NOT spam. The merge files it as
     * a review candidate rather than a vote against the number.
     */
    suspend fun reportNotSpam(
        context: Context,
        number: String,
    ): ContributeResult = submitter(context).submit(number, "not_spam", null)

    /** The network half of a report. Tests replace it to watch what would be sent. */
    internal var transport: suspend (CommunityReport) -> ContributeResult = ::send

    /** Where the day's ledger lives. Tests point it at an isolated repository. */
    internal var repositoryFor: (Context) -> SpamRepository = { SpamRepository.getInstance(it) }

    private fun submitter(context: Context): CommunityReportSubmitter {
        val appContext = context.applicationContext
        val repository = repositoryFor(appContext)
        return CommunityReportSubmitter(
            claim = { number, vote, now -> repository.claimCommunityReport(number, vote, now) },
            release = { number, vote -> repository.releaseCommunityReport(number, vote) },
            transport = { report -> transport(report) },
            enqueue = { report -> CommunityReportWorker.enqueue(appContext, report) },
            dequeue = { report -> CommunityReportWorker.dequeue(appContext, report) },
        )
    }

    /** One attempt to deliver an already-normalized report. Used by the outbox too. */
    internal suspend fun send(report: CommunityReport): ContributeResult =
        withContext(Dispatchers.IO) {
            val type = report.type
            try {
                val json = buildReportJson(report.number, type, report.indicators, report.id)
                val body = json.toRequestBody("application/json".toMediaType())

                val request =
                    Request
                        .Builder()
                        .url(WORKER_URL)
                        .post(body)
                        .build()

                client.newCall(request).execute().use { response ->
                    resultFor(
                        code = response.code,
                        type = type,
                        body = if (response.isSuccessful) "" else response.peekBody(ERROR_BODY_PEEK_BYTES).string(),
                        retryAfterHeader = response.header("Retry-After"),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ContributeResult(
                    success = false,
                    message = "Network error: ${e.message}",
                    outcome = ContributeOutcome.NETWORK_ERROR,
                )
            }
        }

    /** How the app reads the Worker's answer to one report. */
    internal fun resultFor(
        code: Int,
        type: String,
        body: String,
        retryAfterHeader: String?,
    ): ContributeResult {
        // A duplicate the Worker says it stored means an earlier attempt got
        // through and only its response was lost. Sending it again would store
        // it twice.
        val duplicate = code == HTTP_TOO_MANY_REQUESTS && alreadyStoredPattern.containsMatchIn(body)
        return when {
            code in 200..299 || duplicate -> {
                if (type == "not_spam") {
                    ContributeResult(true, "Reported as not spam", ContributeOutcome.REPORTED_NOT_SPAM)
                } else {
                    ContributeResult(true, "Contributed anonymously", ContributeOutcome.REPORTED_SPAM)
                }
            }

            code == HTTP_TOO_MANY_REQUESTS -> {
                val retryAfter = retryAfterHeader?.toIntOrNull() ?: DEFAULT_RETRY_AFTER_SECONDS
                ContributeResult(
                    success = false,
                    message = "Too many reports. Please wait ${retryAfter}s and try again.",
                    outcome = ContributeOutcome.RATE_LIMITED,
                    retryAfterSeconds = retryAfter,
                )
            }

            // The Worker's plausibility gate refused the number, and sending it
            // again later gets the same answer.
            code == HTTP_BAD_REQUEST -> {
                ContributeResult(false, "Rejected as invalid", ContributeOutcome.INVALID_NUMBER)
            }

            else -> {
                ContributeResult(false, "Server error ($code)", ContributeOutcome.SERVER_ERROR)
            }
        }
    }

    private fun escapeJson(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    internal fun buildReportJson(
        normalizedNumber: String,
        type: String,
        smsIndicators: SmsContentAnalyzer.SmsReportIndicators? = null,
        reportId: String? = null,
    ): String {
        val fields =
            mutableListOf(
                """"number":"${escapeJson(normalizedNumber)}"""",
                """"type":"${escapeJson(type)}"""",
            )
        // The Worker and the pipeline use the id to recognise a resend.
        reportId?.let { fields += """"report_id":"${escapeJson(it)}"""" }
        val sanitized = sanitizeSmsIndicators(smsIndicators)
        if (!sanitized.isEmpty()) {
            if (sanitized.domains.isNotEmpty()) {
                fields += """"sms_domains":${jsonStringList(sanitized.domains)}"""
            }
            if (sanitized.urlIndicators.isNotEmpty()) {
                fields += """"sms_url_indicators":${jsonStringList(sanitized.urlIndicators)}"""
            }
        }
        return "{${fields.joinToString(",")}}"
    }

    internal fun sanitizeSmsIndicators(
        smsIndicators: SmsContentAnalyzer.SmsReportIndicators?,
    ): SmsContentAnalyzer.SmsReportIndicators {
        if (smsIndicators == null) return SmsContentAnalyzer.SmsReportIndicators()
        val domains =
            smsIndicators.domains
                .mapNotNull { normalizeSmsDomainForReport(it) }
                .distinct()
                .take(MAX_SMS_REPORT_DOMAINS)
        val indicators =
            smsIndicators.urlIndicators
                .map { it.lowercase().trim() }
                .filter { urlIndicatorPattern.matches(it) }
                .distinct()
                .take(MAX_SMS_URL_INDICATORS)
        return SmsContentAnalyzer.SmsReportIndicators(domains, indicators)
    }

    private fun normalizeSmsDomainForReport(value: String): String? {
        val domain =
            value
                .lowercase()
                .trim()
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("www.")
                .split("/")[0]
                .split("?")[0]
                .split("#")[0]
                .split(":")[0]
                .trim('.')

        val labels = domain.split(".")
        val isValid =
            listOf(
                domain.length in MIN_SMS_REPORT_DOMAIN_LENGTH..MAX_SMS_REPORT_DOMAIN_LENGTH,
                "." in domain,
                reportDomainPattern.matches(domain),
                labels.all { label ->
                    listOf(
                        label.isNotBlank(),
                        label.length <= MAX_SMS_REPORT_DOMAIN_LABEL_LENGTH,
                        !label.startsWith("-"),
                        !label.endsWith("-"),
                    ).all { it }
                },
            ).all { it }
        return domain.takeIf { isValid }
    }

    private fun jsonStringList(values: List<String>): String = values.joinToString(prefix = "[", postfix = "]") { """"${escapeJson(it)}"""" }

    internal fun normalizeForReport(number: String): String? {
        val normalized = normalizePhoneNumber(number)
        if (!normalized.startsWith("+")) return null
        val digits = stripNationalTrunkPrefix(normalized.filter { it in '0'..'9' })
        return "+$digits".takeIf { digits.length in 7..15 }
    }

    /**
     * Drop the national trunk prefix from a hand-entered international number,
     * e.g. "+86 0558 646 8536" -> "+86 558 646 8536".
     *
     * Incoming calls are canonicalized with `PhoneNumberUtils.formatNumberToE164`,
     * which never emits the trunk digit. Reporting the domestic dialling form
     * would publish a row that can never match a real call. Mirrors
     * `scripts/phone_normalization.py:strip_national_trunk_prefix` — keep both
     * in step so app reports and issue-filed reports land on the same key.
     */
    internal fun stripNationalTrunkPrefix(digits: String): String {
        val countryCode =
            when {
                digits.isEmpty() -> return digits
                digits.take(1) in ONE_DIGIT_COUNTRY_CODES -> digits.take(1)
                digits.take(2) in TWO_DIGIT_COUNTRY_CODES -> digits.take(2)
                digits.length >= 3 -> digits.take(3)
                else -> return digits
            }
        if (countryCode in TRUNK_ZERO_SIGNIFICANT_COUNTRY_CODES) return digits
        val national = digits.drop(countryCode.length).trimStart('0')
        // An all-zero national part is junk; leave it for the length check to reject.
        return if (national.isEmpty()) digits else countryCode + national
    }

    /** Every assigned one/two-digit ITU-T E.164 country calling code. Calling codes are
     *  prefix-free, so anything not matching these is a three-digit code. */
    private val ONE_DIGIT_COUNTRY_CODES = setOf("1", "7")

    private val TWO_DIGIT_COUNTRY_CODES =
        (
            "20 27 " +
                "30 31 32 33 34 36 39 " +
                "40 41 43 44 45 46 47 48 49 " +
                "51 52 53 54 55 56 57 58 " +
                "60 61 62 63 64 65 66 " +
                "81 82 84 86 " +
                "90 91 92 93 94 95 98"
        ).split(" ").toSet()

    /** Countries whose national significant numbers genuinely keep a leading 0 in
     *  E.164 — stripping it there would corrupt the number, not repair it:
     *  +39 Italy (and Vatican City, which splits under 39): +39 06 … is correct
     *  E.164 for Rome. +225 Côte d'Ivoire: the 2021 ARTCI renumbering moved to
     *  10-digit national numbers beginning with 0 (01/05/07 mobile, 21/25/27
     *  fixed), so formatNumberToE164 legitimately emits +2250x…. */
    private val TRUNK_ZERO_SIGNIFICANT_COUNTRY_CODES = setOf("39", "225")
}
