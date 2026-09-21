package com.sysadmindoc.callshield.data.remote

import com.sysadmindoc.callshield.data.ExternalBlocklistFailureReason
import com.sysadmindoc.callshield.data.ExternalBlocklistParser
import com.sysadmindoc.callshield.data.ExternalBlocklistValidationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

interface ExternalBlocklistDataSource {
    suspend fun fetchText(url: String): Result<String>
}

class OkHttpExternalBlocklistDataSource : ExternalBlocklistDataSource {
    private val client =
        HttpClient.shared
            .newBuilder()
            .connectTimeout(EXTERNAL_BLOCKLIST_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(EXTERNAL_BLOCKLIST_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    override suspend fun fetchText(url: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                fetchValidated(ExternalBlocklistParser.validateHttpUrl(url))
            }
        }

    private fun fetchValidated(safeUrl: String): String {
        val originalUrl = safeUrl.toHttpUrl()
        var currentUrl = originalUrl
        var redirectCount = 0
        while (true) {
            val request =
                Request
                    .Builder()
                    .url(currentUrl)
                    .header("User-Agent", "CallShield/1.0")
                    .build()
            val bodyText =
                client.newCall(request).execute().use { response ->
                    if (response.code in EXTERNAL_BLOCKLIST_REDIRECT_CODES) {
                        currentUrl =
                            validatedExternalBlocklistRedirect(
                                originalUrl = originalUrl,
                                currentUrl = currentUrl,
                                location = response.header("Location"),
                                redirectCount = redirectCount,
                            )
                        redirectCount += 1
                        return@use null
                    }
                    if (!response.isSuccessful) {
                        throw ExternalBlocklistHttpException(response.code)
                    }
                    externalBlocklistBodyText(response.body.readUtf8Bounded(ExternalBlocklistParser.MAX_SUBSCRIPTION_BYTES))
                }
            if (bodyText != null) return bodyText
        }
    }
}

/** The list server answered with an error status; kept apart so the row can say which. */
internal class ExternalBlocklistHttpException(
    val code: Int,
) : IOException("HTTP $code")

/**
 * A body that arrived but holds nothing is an empty list, the same answer the
 * parser gives for a list with no numbers, so a background refresh holds the
 * last good copy instead of recording a failure.
 */
internal fun externalBlocklistBodyText(body: BoundedResponseBody): String =
    when (body) {
        is BoundedResponseBody.Text -> {
            body.value
        }

        BoundedResponseBody.Empty -> {
            throw ExternalBlocklistValidationException(
                ExternalBlocklistFailureReason.EMPTY,
                "External blocklist returned an empty body",
            )
        }

        is BoundedResponseBody.Oversized -> {
            throw ExternalBlocklistValidationException(
                ExternalBlocklistFailureReason.OVERSIZE,
                "External blocklist exceeded ${body.maxBytes} byte cap",
            )
        }

        BoundedResponseBody.Unreadable -> {
            throw IOException("External blocklist response could not be read")
        }
    }

internal fun validatedExternalBlocklistRedirect(
    originalUrl: HttpUrl,
    currentUrl: HttpUrl,
    location: String?,
    redirectCount: Int,
): HttpUrl {
    require(redirectCount < EXTERNAL_BLOCKLIST_MAX_REDIRECTS) {
        "External blocklist exceeded redirect limit"
    }
    val resolved =
        location
            ?.takeIf(String::isNotBlank)
            ?.let(currentUrl::resolve)
            ?: throw IllegalArgumentException("External blocklist redirect omitted a valid Location")
    val validated = ExternalBlocklistParser.validateHttpUrl(resolved.toString()).toHttpUrl()
    require(validated.host == originalUrl.host) {
        "External blocklist redirect changed host"
    }
    return validated
}

private const val EXTERNAL_BLOCKLIST_CONNECT_TIMEOUT_SECONDS = 15L
private const val EXTERNAL_BLOCKLIST_READ_TIMEOUT_SECONDS = 20L
internal const val EXTERNAL_BLOCKLIST_MAX_REDIRECTS = 5
private val EXTERNAL_BLOCKLIST_REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
