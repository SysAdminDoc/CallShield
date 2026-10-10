package com.sysadmindoc.callshield.data.model

import androidx.room.ColumnInfo
import androidx.room.Embedded

/** A compact SQL aggregate used by the statistics screen. */
data class LogAggregate(
    @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "count") val count: Int,
)

/** When a number first shows up in a window of the log. */
data class NumberSighting(
    @ColumnInfo(name = "number") val number: String,
    @ColumnInfo(name = "firstSeen") val firstSeen: Long,
)

/** The newest row and occurrence count for one number in grouped log mode. */
data class BlockedCallGroup(
    @Embedded val call: BlockedCall,
    @ColumnInfo(name = "occurrences") val occurrences: Int,
)
