package com.sysadmindoc.callshield.service

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The last network copy of each feed that lives only in memory (hot ranges,
 * scam domains, the community watch list), one line per entry, so a new
 * process starts from what the publisher last sent instead of the build-time
 * copy or nothing. Kept in noBackupFilesDir: a cloud backup restored on
 * another phone fetches its own. A copy that can't be read counts as none.
 */
internal class PersistedHotFeeds(
    private val directory: File,
) {
    constructor(context: Context) : this(File(context.applicationContext.noBackupFilesDir, DIRECTORY))

    /** The entries kept for [feed], or null when none were ever kept or the file is unreadable. */
    fun read(feed: String): List<String>? {
        val source = file(feed)
        if (!source.exists()) return null
        return try {
            source.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "The kept copy of $feed can't be read", e)
            null
        }
    }

    /**
     * Written beside the target and moved over it, so a crash never leaves
     * half a file. The temp name is unique, so two refreshes writing the same
     * feed at once (the periodic one and a requested one) don't share it.
     */
    fun write(
        feed: String,
        entries: List<String>,
    ) {
        try {
            directory.mkdirs()
            val target = file(feed)
            val temp = File.createTempFile(feed, ".tmp", directory)
            FileOutputStream(temp).use { out ->
                out.write(entries.joinToString(separator = "\n", postfix = "\n").toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            // The next refresh writes again; until then a restart asks for one.
            Log.w(TAG, "Couldn't keep a copy of $feed", e)
        }
    }

    /** Forgets every kept copy, as a fresh install has none. */
    fun clear() {
        directory.deleteRecursively()
    }

    private fun file(feed: String) = File(directory, "$feed.txt")

    private companion object {
        const val TAG = "PersistedHotFeeds"
        const val DIRECTORY = "hot-feeds"
    }
}
