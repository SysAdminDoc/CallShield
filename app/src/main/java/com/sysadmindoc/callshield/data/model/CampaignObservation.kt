package com.sysadmindoc.callshield.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Local-only evidence for bounded neighbor-number campaign detection. */
@Entity(
    tableName = "campaign_observations",
    indices = [
        Index(value = ["prefix", "observedAt"]),
        Index(value = ["observedAt"]),
        Index(value = ["number", "observedAt"]),
    ],
)
data class CampaignObservation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val prefix: String,
    val observedAt: Long,
    /** Comma-separated source IDs; empty means behavioral evidence only. */
    val sourceIds: String = "",
)
