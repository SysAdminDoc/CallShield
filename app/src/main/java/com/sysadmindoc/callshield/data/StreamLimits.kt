package com.sysadmindoc.callshield.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Maximum bytes read from a user-selected import/backup file before it is
 * rejected. Generous enough for any realistic CallShield export (blocklists and
 * logs are small), while preventing a pathological multi-hundred-MB file from
 * being materialized whole into memory (OOM/ANR) at a trust boundary.
 */
internal const val MAX_IMPORT_FILE_BYTES: Long = 32L * 1024L * 1024L // 32 MB

/**
 * Maximum number of rows applied from a single user-selected blocklist import.
 * Bounds the per-row insert loop; far above any realistic personal blocklist.
 */
internal const val MAX_IMPORT_ROWS: Int = 100_000

/**
 * Read this stream as UTF-8 text, returning null if it exceeds [maxBytes]
 * instead of allocating an unbounded String. The stream is always closed.
 *
 * Unlike `bufferedReader().readText()`, this caps the read so a hostile or
 * accidentally huge SAF-selected file can't OOM the import/restore path.
 */
internal fun InputStream.readTextBounded(
    maxBytes: Long = MAX_IMPORT_FILE_BYTES,
): String? = readBytesBounded(maxBytes)?.toString(Charsets.UTF_8)

/** Read this stream into memory without exceeding [maxBytes]. */
internal fun InputStream.readBytesBounded(maxBytes: Long = MAX_IMPORT_FILE_BYTES): ByteArray? =
    use { stream ->
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(chunk, 0, read)
        }
        out.toByteArray()
    }
