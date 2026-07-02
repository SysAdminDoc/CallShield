package com.sysadmindoc.callshield.data.model

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
) {
    val source: String get() = sourceFor(id)

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

data class ExternalBlocklistImportResult(
    val success: Boolean,
    val message: String,
    val preview: ExternalBlocklistPreview? = null,
    val subscription: ExternalBlocklistSubscription? = null,
)
