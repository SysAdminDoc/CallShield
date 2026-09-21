package com.sysadmindoc.callshield.data.model

/** Local health summary for the three short-lived hot protection feeds. */
data class HotDataHealth(
    val lastGoodTimestamp: Long = 0L,
    /**
     * Feeds whose last refresh changed nothing on the device: never fetched, or
     * fetched and refused because they arrived empty without `cleared`. This
     * drives the sync worker's retry, so it deliberately mixes both causes.
     */
    val unavailableFeeds: Set<String> = emptySet(),
    /**
     * Feeds that could not be fetched at all on the last refresh. Null on an
     * install that has not refreshed since this was recorded, where the only
     * evidence is [unavailableFeeds].
     */
    val unreachableFeeds: Set<String>? = null,
    /** Feeds the publisher emptied on purpose (`cleared: true`) on the last refresh. */
    val clearedFeeds: Set<String> = emptySet(),
    /**
     * Feeds that were reachable on the last refresh but refused: the download
     * didn't verify, or it was older than the copy already in use.
     */
    val refusedFeeds: Set<String> = emptySet(),
    /**
     * Each feed's own ISO-8601 `generated` value from the last time it was read.
     * `lastGoodTimestamp` records when *this device* last synced successfully,
     * which says nothing about whether the publisher is still producing; this
     * says when the publisher last did.
     */
    val feedGeneratedAt: Map<String, String> = emptyMap(),
    /** Each feed's report-queue digest, for spotting a publisher repeating itself. */
    val feedDigests: Map<String, String> = emptyMap(),
)

/** What one hot-feed refresh learned, ready to be written over the stored [HotDataHealth]. */
data class HotDataHealthUpdate(
    val unavailableFeeds: Set<String>,
    val unreachableFeeds: Set<String>,
    val clearedFeeds: Set<String>,
    /** Feeds that were read on this refresh; their metadata replaces whatever was stored. */
    val resolvedFeeds: Set<String>,
    val feedGeneratedAt: Map<String, String>,
    val feedDigests: Map<String, String>,
    val refusedFeeds: Set<String> = emptySet(),
) {
    companion object {
        /**
         * Per-feed metadata after a refresh. A feed that was read replaces its old
         * entry, including dropping it when the new file no longer declares one,
         * so a publisher that stops writing `generated` is not judged forever by
         * its last stamp. A feed that was not read keeps its previous entry: a
         * transient network failure must not turn a stalled publisher back into
         * "unknown".
         */
        fun mergeFeedMetadata(
            previous: Map<String, String>,
            resolvedFeeds: Set<String>,
            fresh: Map<String, String>,
        ): Map<String, String> = previous.filterKeys { it !in resolvedFeeds } + fresh
    }
}
