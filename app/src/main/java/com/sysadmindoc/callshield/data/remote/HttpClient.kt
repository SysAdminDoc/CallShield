package com.sysadmindoc.callshield.data.remote

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared OkHttpClient for all network requests in the app.
 *
 * OkHttpClient instances are expensive — each creates its own connection pool,
 * thread pool, and cache. Sharing a single instance enables HTTP/2 connection
 * reuse across GitHub API, ExternalLookup, URLhaus, and community report calls.
 *
 * Callers that need different timeouts should use [OkHttpClient.newBuilder] to
 * create a derived client that shares the same connection pool:
 *   `HttpClient.shared.newBuilder().readTimeout(5, SECONDS).build()`
 */
object HttpClient {
    val shared: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
}
