package com.sysadmindoc.callshield.data.repository

import android.content.Context
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.remote.GitHubFeedFailureReason
import com.sysadmindoc.callshield.data.remote.GitHubFeedHttpException
import com.sysadmindoc.callshield.data.remote.GitHubFeedValidationException
import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal enum class SyncFailureKind {
    OFFLINE,
    DNS,
    SECURE_CONNECTION,
    HTTP,
    SIGNATURE,
    PARSE,
    UNKNOWN,
}

/**
 * Turns a failed database sync into one plain sentence. The exception text
 * ("Unable to resolve host ...", "HTTP 404: Not Found") used to be the message
 * Home showed, so SyncRepository now logs it and shows this instead.
 */
internal object SyncFailureText {
    private const val MAX_CAUSE_DEPTH = 8
    private const val EMPTY_BODY = "Empty response body"
    private val httpStatus = Regex("""\bHTTP (\d{3})\b""")

    /** A feed host that refuses the request this way will refuse the retry too. */
    private val permanentHttpCodes = setOf(400, 401, 403, 404)

    fun kind(error: Throwable?): SyncFailureKind = causes(error).firstNotNullOfOrNull(::kindOf) ?: SyncFailureKind.UNKNOWN

    fun httpCode(error: Throwable?): Int? =
        causes(error).firstNotNullOfOrNull { cause ->
            (cause as? GitHubFeedHttpException)?.code
                ?: cause.message?.let { message ->
                    httpStatus
                        .find(message)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                }
        }

    fun shouldRetry(error: Throwable?): Boolean = httpCode(error) !in permanentHttpCodes

    fun sentence(
        context: Context,
        error: Throwable?,
    ): String =
        when (kind(error)) {
            SyncFailureKind.OFFLINE -> context.getString(R.string.sync_failure_offline)
            SyncFailureKind.DNS -> context.getString(R.string.sync_failure_dns)
            SyncFailureKind.SECURE_CONNECTION -> context.getString(R.string.sync_failure_secure_connection)
            SyncFailureKind.HTTP -> context.getString(R.string.sync_failure_http, httpCode(error) ?: 0)
            SyncFailureKind.SIGNATURE -> context.getString(R.string.sync_failure_signature)
            SyncFailureKind.PARSE -> context.getString(R.string.sync_failure_parse)
            SyncFailureKind.UNKNOWN -> context.getString(R.string.sync_unknown_error)
        }

    private fun causes(error: Throwable?): List<Throwable> = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).toList()

    // Moshi's JsonEncodingException is an IOException, so parse failures are
    // matched before the network ones.
    private fun kindOf(error: Throwable): SyncFailureKind? =
        when (error) {
            is GitHubFeedValidationException -> {
                if (error.reason == GitHubFeedFailureReason.SIGNATURE) SyncFailureKind.SIGNATURE else SyncFailureKind.PARSE
            }

            is SpamFeedManifestRejectedException, is JsonDataException, is JsonEncodingException -> {
                SyncFailureKind.PARSE
            }

            is GitHubFeedHttpException -> {
                SyncFailureKind.HTTP
            }

            is UnknownHostException -> {
                SyncFailureKind.DNS
            }

            is SSLException -> {
                SyncFailureKind.SECURE_CONNECTION
            }

            is IOException -> {
                SyncFailureKind.OFFLINE
            }

            else -> {
                val message = error.message.orEmpty()
                when {
                    httpStatus.containsMatchIn(message) -> SyncFailureKind.HTTP
                    message == EMPTY_BODY -> SyncFailureKind.PARSE
                    else -> null
                }
            }
        }
}
