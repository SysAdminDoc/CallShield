package com.sysadmindoc.callshield.data.model

import java.net.URI

data class ExternalBlocklistSubscription(
    val id: String,
    val label: String,
    val url: String,
    val enabled: Boolean = true,
    val lastSyncedAt: Long = 0L,
    val lastNumberCount: Int = 0,
    val lastAdded: Int = 0,
    val lastRemoved: Int = 0,
    val lastError: String = "",
    /** Hours between refreshes that the list declares in an `Expires:` line, or 0 for none. */
    val declaredRefreshHours: Int = 0,
    /** The last fetch, successful or not. Paces retries of a list that keeps failing. */
    val lastAttemptAt: Long = 0L,
) {
    val source: String get() = sourceFor(id)

    /** Only the host the list comes from, which is all a settings row has room for. */
    val host: String get() = runCatching { URI(url).host }.getOrNull() ?: url

    companion object {
        const val SOURCE_PREFIX = "subscription:"

        fun sourceFor(id: String): String = "$SOURCE_PREFIX$id"
    }
}

data class ExternalBlocklistPreview(
    val id: String,
    val label: String,
    val url: String,
    val source: String,
    val format: String,
    val numberCount: Int,
    val added: Int,
    val removed: Int,
    val unchanged: Int,
    val skippedRows: Int,
    val blockedByOtherSources: Int,
)

enum class ExternalBlocklistRefreshOutcome {
    REFRESHED,

    /** The download was empty or under half the list's size, so the last good rows stay. */
    HELD,
    FAILED,
}

data class ExternalBlocklistImportResult(
    val success: Boolean,
    val message: String,
    val preview: ExternalBlocklistPreview? = null,
    val subscription: ExternalBlocklistSubscription? = null,
)
