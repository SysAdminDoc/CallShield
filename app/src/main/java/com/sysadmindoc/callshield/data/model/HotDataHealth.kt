package com.sysadmindoc.callshield.data.model

/** Local health summary for the three short-lived hot protection feeds. */
data class HotDataHealth(
    val lastGoodTimestamp: Long = 0L,
    val unavailableFeeds: Set<String> = emptySet(),
)
