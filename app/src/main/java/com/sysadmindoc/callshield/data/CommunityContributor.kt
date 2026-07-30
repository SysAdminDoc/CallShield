package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.remote.HttpClient
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
 */
object CommunityContributor {
    private const val WORKER_URL = "https://callshield-reports.snafumatthew.workers.dev"
    private const val MAX_SMS_REPORT_DOMAINS = 10
    private const val MAX_SMS_URL_INDICATORS = 10
    private const val MIN_SMS_REPORT_DOMAIN_LENGTH = 5
    private const val MAX_SMS_REPORT_DOMAIN_LENGTH = 253
    private const val MAX_SMS_REPORT_DOMAIN_LABEL_LENGTH = 63
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val DEFAULT_RETRY_AFTER_SECONDS = 60

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
        number: String,
        type: String = "spam",
        smsIndicators: SmsContentAnalyzer.SmsReportIndicators? = null,
    ): ContributeResult = post(number, type, smsIndicators)

    /**
     * Report SMS spam with body-free URL/domain indicators only.
     */
    suspend fun contributeSmsSpam(
        number: String,
        smsBody: String,
    ): ContributeResult = post(number, "sms_spam", SmsContentAnalyzer.extractReportableIndicators(smsBody))

    /**
     * Report a false positive — this number is NOT spam.
     * The merge script will subtract votes from this number.
     */
    suspend fun reportNotSpam(number: String): ContributeResult = post(number, "not_spam")

    private suspend fun post(
        number: String,
        type: String,
        smsIndicators: SmsContentAnalyzer.SmsReportIndicators? = null,
    ): ContributeResult =
        withContext(Dispatchers.IO) {
            try {
                val normalized =
                    normalizeForReport(number)
                        ?: return@withContext ContributeResult(
                            success = false,
                            message = "Invalid number",
                            outcome = ContributeOutcome.INVALID_NUMBER,
                        )
                val json = buildReportJson(normalized, type, smsIndicators)
                val body = json.toRequestBody("application/json".toMediaType())

                val request =
                    Request
                        .Builder()
                        .url(WORKER_URL)
                        .post(body)
                        .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        if (type == "not_spam") {
                            ContributeResult(true, "Reported as not spam", ContributeOutcome.REPORTED_NOT_SPAM)
                        } else {
                            ContributeResult(true, "Contributed anonymously", ContributeOutcome.REPORTED_SPAM)
                        }
                    } else if (response.code == HTTP_TOO_MANY_REQUESTS) {
                        val retryAfter = response.header("Retry-After")?.toIntOrNull() ?: DEFAULT_RETRY_AFTER_SECONDS
                        ContributeResult(
                            success = false,
                            message = "Too many reports. Please wait ${retryAfter}s and try again.",
                            outcome = ContributeOutcome.RATE_LIMITED,
                            retryAfterSeconds = retryAfter,
                        )
                    } else {
                        ContributeResult(
                            success = false,
                            message = "Server error (${response.code})",
                            outcome = ContributeOutcome.SERVER_ERROR,
                        )
                    }
                }
            } catch (e: Exception) {
                ContributeResult(
                    success = false,
                    message = "Network error: ${e.message}",
                    outcome = ContributeOutcome.NETWORK_ERROR,
                )
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
    ): String {
        val fields =
            mutableListOf(
                """"number":"${escapeJson(normalizedNumber)}"""",
                """"type":"${escapeJson(type)}"""",
            )
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
