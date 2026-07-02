package com.sysadmindoc.callshield.data.remote

import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

private const val READ_CHUNK_BYTES = 8_192L

internal sealed interface BoundedResponseBody {
    data class Text(val value: String) : BoundedResponseBody
    data object Empty : BoundedResponseBody
    data class Oversized(val maxBytes: Long) : BoundedResponseBody
    data object Unreadable : BoundedResponseBody
}

internal fun ResponseBody.readUtf8Bounded(maxBytes: Long): BoundedResponseBody {
    require(maxBytes > 0) { "maxBytes must be positive" }
    val declaredLength = contentLength()
    return if (declaredLength > maxBytes) {
        BoundedResponseBody.Oversized(maxBytes)
    } else {
        readUtf8BoundedSource(maxBytes)
    }
}

private fun ResponseBody.readUtf8BoundedSource(maxBytes: Long): BoundedResponseBody {
    val source = source()
    val state = BodyReadState(maxBytes = maxBytes)
    val charset = contentType()?.charset(StandardCharsets.UTF_8)
    var result: BoundedResponseBody? = null
    return try {
        while (result == null) {
            result = state.readNext(source, charset)
        }
        result ?: BoundedResponseBody.Unreadable
    } catch (_: IOException) {
        BoundedResponseBody.Unreadable
    } catch (_: RuntimeException) {
        BoundedResponseBody.Unreadable
    }
}

private data class BodyReadState(
    val maxBytes: Long,
    val buffer: Buffer = Buffer(),
    var totalBytes: Long = 0L
) {
    fun readNext(source: BufferedSource, charset: Charset?): BoundedResponseBody? {
        val nextLimit = minOf(READ_CHUNK_BYTES, maxBytes + 1 - totalBytes)
        val read = if (nextLimit > 0L) source.read(buffer, nextLimit) else 0L
        return when {
            nextLimit <= 0L -> BoundedResponseBody.Oversized(maxBytes)
            read == -1L -> buffer.toBoundedText(charset)
            totalBytes + read > maxBytes -> BoundedResponseBody.Oversized(maxBytes)
            else -> {
                totalBytes += read
                null
            }
        }
    }
}

private fun Buffer.toBoundedText(charset: Charset?): BoundedResponseBody =
    if (size == 0L) {
        BoundedResponseBody.Empty
    } else {
        BoundedResponseBody.Text(readString(charset ?: StandardCharsets.UTF_8))
    }

internal fun BoundedResponseBody.status(): RemoteLookupStatus = when (this) {
    is BoundedResponseBody.Text -> RemoteLookupStatus.FOUND
    BoundedResponseBody.Empty -> RemoteLookupStatus.EMPTY_BODY
    is BoundedResponseBody.Oversized -> RemoteLookupStatus.BODY_TOO_LARGE
    BoundedResponseBody.Unreadable -> RemoteLookupStatus.UNREADABLE_BODY
}
