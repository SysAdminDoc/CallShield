package com.sysadmindoc.callshield.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.sysadmindoc.callshield.data.model.SourceEvidenceJson

/** Small codec for the opaque Room evidence column. */
internal object SourceEvidenceCodec {
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val adapter =
        moshi.adapter<List<SourceEvidenceJson>>(
            Types.newParameterizedType(List::class.java, SourceEvidenceJson::class.java),
        )

    fun encode(evidence: List<SourceEvidenceJson>): String = adapter.toJson(evidence)

    fun decode(json: String): List<SourceEvidenceJson> = runCatching { adapter.fromJson(json).orEmpty() }.getOrDefault(emptyList())

    /**
     * When a row stops blocking: a row stays live while any of its evidence is
     * live, so the latest expiry decides, and a record without one (or no
     * evidence at all) keeps the row live for good. Taking the earliest instead
     * made FCC-corroborated numbers lapse years before database-only ones.
     * `scripts/pipeline_liveness.py` applies the same rule to the same fixture,
     * `scripts/evidence_expiry_fixtures.json`.
     */
    fun rowExpiry(evidence: List<SourceEvidenceJson>): Long? {
        val stamps = evidence.map { it.expiresAtEpochMs ?: return null }
        return stamps.maxOrNull()
    }
}

/**
 * Stored rows, as (id, evidence JSON, stored expiry), whose expiry differs from
 * [SourceEvidenceCodec.rowExpiry], mapped to the corrected value. A row whose
 * evidence can't be read is left alone rather than made permanent.
 */
internal fun evidenceExpiryCorrections(rows: List<Triple<Long, String, Long?>>): Map<Long, Long?> =
    rows
        .mapNotNull { (id, json, stored) ->
            val evidence = SourceEvidenceCodec.decode(json)
            val expiry = SourceEvidenceCodec.rowExpiry(evidence)
            if (evidence.isEmpty() || expiry == stored) null else id to expiry
        }.toMap()
