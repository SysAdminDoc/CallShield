package com.sysadmindoc.callshield.data

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

/**
 * Proof that no restore journal is waiting, so startup can skip looking for
 * one. On an API 29 emulator (2026-09-23) the look (initializing BackupRestore,
 * whose reflective Moshi adapter took about 500 ms, then opening Room) held
 * the main thread 640 to 720 ms on every process start and 1.2 s on a fresh
 * install, inside the five seconds an incoming call that starts the process
 * gets. Callers must check [isClean] without touching BackupRestore.
 *
 * The file only ever claims "clean". A restore deletes it, and syncs the
 * delete, before it writes the journal; startup recreates it after a full
 * check found no journal or reconciled one. A missing file, whether from a
 * fresh install, an update from a version without it, a restore, a crash or
 * a failed write, only costs one full check. It lives in noBackupFilesDir so
 * a restored device backup can't bring one along.
 */
internal object RestoreSentinel {
    private const val FILE_NAME = "restore-journal-clean"

    fun isClean(context: Context): Boolean = file(context).exists()

    /** Must run before a restore journal is written; throws when the claim can't be withdrawn. */
    fun markDirty(context: Context) {
        val file = file(context)
        if (file.exists() && !file.delete()) throw IOException("Couldn't clear $FILE_NAME")
        file.parentFile?.let(::syncDirectory)
    }

    /** After a full check left no journal behind. A failed write only costs the next start a full check. */
    fun markClean(context: Context) {
        runCatching { file(context).createNewFile() }
    }

    private fun file(context: Context) = File(context.noBackupFilesDir, FILE_NAME)

    // So the delete can't be lost behind the journal's own commit if the power fails.
    private fun syncDirectory(directory: File) {
        try {
            val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        } catch (_: ErrnoException) {
            // Some filesystems refuse to sync a directory; process death still can't undo the delete.
        }
    }
}
