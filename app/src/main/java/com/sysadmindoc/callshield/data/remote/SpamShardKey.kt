package com.sysadmindoc.callshield.data.remote

import java.security.MessageDigest

/** The first SHA-256 byte is the stable shard partition used by the data pipeline. */
internal fun spamShardIdFor(value: String): String {
    val firstByte =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.trim().toByteArray(Charsets.UTF_8))[0]
            .toInt() and 0xff
    val hex = "0123456789abcdef"
    return "${hex[firstByte ushr 4]}${hex[firstByte and 0x0f]}"
}

internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val hex = "0123456789abcdef"
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append(hex[unsigned ushr 4])
            append(hex[unsigned and 0x0f])
        }
    }
}
