package com.sysadmindoc.callshield.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Thrown for a request that strict offline mode refused. Nothing was sent: it
 * is raised before OkHttp resolves a name or opens a socket. It is an
 * [IOException] so every caller's existing "the network is unavailable" path
 * handles it, and callers that want to say offline mode is on can look for it.
 */
class StrictOfflineException : IOException("Strict offline mode is on, so no network request was made")

private const val MAX_OFFLINE_CAUSE_DEPTH = 8

/** True when this or anything in its cause chain is a [StrictOfflineException]. */
internal fun Throwable.isStrictOffline(): Boolean = generateSequence(this) { it.cause }.take(MAX_OFFLINE_CAUSE_DEPTH).any { it is StrictOfflineException }

/**
 * The process-wide answer to "may the app touch the network right now?".
 *
 * `CallShieldApp` collects the stored setting into [set], so a change takes
 * effect on the next request. Until that first value arrives the state is
 * unknown, and the interceptor must not guess: it asks the reader installed
 * at process start, and a read that fails counts as on. Interceptors never run
 * on the main thread, so that read may block.
 */
internal object StrictOffline {
    @Volatile
    private var state: Boolean? = null

    @Volatile
    private var reader: (() -> Boolean)? = null

    /** Installed first thing in `Application.onCreate`: returns the stored setting, or throws. */
    fun installReader(read: () -> Boolean) {
        reader = read
    }

    /** The value the app is following. */
    fun set(on: Boolean) {
        state = on
    }

    /** Back to unknown, so the next request reads the stored setting again. */
    fun forget() {
        state = null
    }

    /** True once the setting is known to be on. For skipping optional work; a request is still judged by [blocksRequests]. */
    fun isOn(): Boolean = state == true

    /** Whether a request must be refused now. Unknown state reads the setting, and a failed read refuses. */
    @Suppress("TooGenericExceptionCaught")
    fun blocksRequests(): Boolean {
        state?.let { return it }
        val read =
            reader
                // No reader means no application ever started in this process. Only a unit
                // test gets there, and it has the loopback-only client to keep it local.
                ?: return System.getProperty(HttpClient.UNIT_TEST_PROPERTY) != "true"
        return try {
            read()
        } catch (_: Exception) {
            true
        }
    }

    /** Back to the state of a fresh process, for tests. */
    internal fun resetForTest() {
        state = null
        reader = null
    }
}

internal object StrictOfflineInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (StrictOffline.blocksRequests()) throw StrictOfflineException()
        return chain.proceed(chain.request())
    }
}
