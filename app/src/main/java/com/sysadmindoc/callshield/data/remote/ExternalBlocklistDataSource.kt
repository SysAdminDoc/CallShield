package com.sysadmindoc.callshield.data.remote

import com.sysadmindoc.callshield.data.ExternalBlocklistParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
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
            .build()

    override suspend fun fetchText(url: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val safeUrl = ExternalBlocklistParser.validateHttpUrl(url)
                val request =
                    Request
                        .Builder()
                        .url(safeUrl)
                        .header("User-Agent", "CallShield/1.0")
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("HTTP ${response.code}")
                    }
                    when (val body = response.body?.readUtf8Bounded(ExternalBlocklistParser.MAX_SUBSCRIPTION_BYTES)) {
                        is BoundedResponseBody.Text -> {
                            body.value
                        }

                        BoundedResponseBody.Empty -> {
                            error("External blocklist returned an empty body")
                        }

                        is BoundedResponseBody.Oversized -> {
                            error(
                                "External blocklist exceeded ${body.maxBytes} byte cap",
                            )
                        }

                        BoundedResponseBody.Unreadable,
                        null,
                        -> {
                            error("External blocklist response could not be read")
                        }
                    }
                }
            }
        }
}

private const val EXTERNAL_BLOCKLIST_CONNECT_TIMEOUT_SECONDS = 15L
private const val EXTERNAL_BLOCKLIST_READ_TIMEOUT_SECONDS = 20L
