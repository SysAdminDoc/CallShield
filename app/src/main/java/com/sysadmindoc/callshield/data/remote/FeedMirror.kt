package com.sysadmindoc.callshield.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A second place to download the protection feeds from, for when
 * raw.githubusercontent.com is blocked where someone lives or the repository
 * moves. Off unless the user sets it.
 *
 * The mirror is tried after every GitHub branch has failed, or first for a
 * few minutes after GitHub couldn't be reached at all, and before the callers
 * fall back to the bundled snapshot. It needs no certificate pins of its own:
 * every signed feed is checked against [FeedSignature] whichever host serves
 * it, so a mirror can't hand a device anything the maintainer didn't sign.
 * The database's rollback checks run on the parsed manifest, so they apply to
 * a mirrored one the same way. A mirror can still hold back updates.
 */
internal object FeedMirror {
    /**
     * jsDelivr's copy of the repository, offered in Settings as a ready-made
     * mirror. It serves the same commits from a different host, cached for up
     * to 12 hours.
     */
    const val JSDELIVR_BASE_URL =
        "https://cdn.jsdelivr.net/gh/${GitHubDataSource.DEFAULT_REPO_OWNER}/${GitHubDataSource.DEFAULT_REPO_NAME}@master/"

    @Volatile
    var baseUrl: String? = null
        private set

    @Volatile
    private var loading = false

    @Volatile
    private var loaded = CompletableDeferred<Unit>()

    /**
     * Called just before the app starts reading the stored setting. Until
     * [set] or [loadFailed] follows, [awaitLoaded] holds a fetch briefly, so a
     * worker that starts in a fresh process doesn't build its source list
     * before the setting arrives and skip the mirror.
     */
    fun startLoading() {
        loading = true
    }

    /** The stored setting couldn't be read, so stop holding fetches for it. */
    fun loadFailed() {
        loaded.complete(Unit)
    }

    /** Waits up to [timeoutMs] for the stored setting while the app is still reading it. */
    suspend fun awaitLoaded(timeoutMs: Long = LOAD_WAIT_MS) {
        if (loading) withTimeoutOrNull(timeoutMs) { loaded.await() }
    }

    /** Sets the mirror from a stored value; anything that isn't a usable base URL clears it. */
    fun set(raw: String?) {
        baseUrl = raw?.let(::normalize)
        loaded.complete(Unit)
    }

    /** Back to the state of a fresh process, for tests. */
    internal fun resetForTest() {
        baseUrl = null
        loading = false
        loaded = CompletableDeferred()
    }

    private const val LOAD_WAIT_MS = 3_000L

    /** The mirror's URL for a repository path such as `data/hot_numbers.json`, or null with no mirror. */
    fun urlFor(path: String): String? = baseUrl?.let { it + path }

    /**
     * An https base URL ending in a slash, or null when [raw] can't be one: not
     * https, carrying a user name or password, or carrying a query string that
     * a repository path couldn't follow.
     */
    fun normalize(raw: String): String? {
        val url = raw.trim().toHttpUrlOrNull() ?: return null
        val usable = url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty() && url.query == null
        if (!usable) return null
        val text =
            url
                .newBuilder()
                .fragment(null)
                .build()
                .toString()
        return if (text.endsWith("/")) text else "$text/"
    }
}
