package com.sysadmindoc.callshield.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.sysadmindoc.callshield.data.remote.HttpClient
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

    private val reportDomainPattern = Regex("^[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?$")
    private val urlIndicatorPattern = Regex("^[a-z_]{3,40}$")

    private val client = HttpClient.shared.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    data class ContributeResult(val success: Boolean, val message: String)

    /**
     * Report a number as spam.
     */
    suspend fun contribute(
        number: String,
        type: String = "spam",
        smsIndicators: SmsContentAnalyzer.SmsReportIndicators? = null,
    ): ContributeResult {
        return post(number, type, smsIndicators)
    }

    /**
     * Report SMS spam with body-free URL/domain indicators only.
     */
    suspend fun contributeSmsSpam(number: String, smsBody: String): ContributeResult {
        return post(number, "sms_spam", SmsContentAnalyzer.extractReportableIndicators(smsBody))
    }

    /**
     * Report a false positive — this number is NOT spam.
     * The merge script will subtract votes from this number.
     */
    suspend fun reportNotSpam(number: String): ContributeResult {
        return post(number, "not_spam")
    }

    private suspend fun post(
        number: String,
        type: String,
        smsIndicators: SmsContentAnalyzer.SmsReportIndicators? = null,
    ): ContributeResult = withContext(Dispatchers.IO) {
        try {
            val normalized = normalizeForReport(number) ?: return@withContext ContributeResult(false, "Invalid number")
            val json = buildReportJson(normalized, type, smsIndicators)
            val body = json.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(WORKER_URL)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val msg = if (type == "not_spam") "Reported as not spam" else "Contributed anonymously"
                    ContributeResult(true, msg)
                } else {
                    ContributeResult(false, "Server error (${response.code})")
                }
            }
        } catch (e: Exception) {
            ContributeResult(false, "Network error: ${e.message}")
        }
    }

    private fun escapeJson(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    internal fun buildReportJson(
        normalizedNumber: String,
        type: String,
        smsIndicators: SmsContentAnalyzer.SmsReportIndicators? = null,
    ): String {
        val fields = mutableListOf(
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
            value.lowercase()
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

    private fun jsonStringList(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { """"${escapeJson(it)}"""" }

    internal fun normalizeForReport(number: String): String? {
        val normalized = normalizePhoneNumber(number)
        val digits = normalized.filter { it in '0'..'9' }
        return when {
            digits.length == 10 -> "+1$digits"
            digits.length in 7..15 -> "+$digits"
            else -> null
        }
    }
}
