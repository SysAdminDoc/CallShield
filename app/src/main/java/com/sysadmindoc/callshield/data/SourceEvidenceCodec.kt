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
}
