package com.sysadmindoc.callshield.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AppDatabase.isCorruptionException] — the pure detector that
 * decides whether a thrown error is on-disk SQLite corruption (recoverable by
 * rebuilding the DB) versus a transient/logic error (must not wipe user data).
 */
class AppDatabaseCorruptionTest {
    private class FakeCorruptException(
        message: String,
    ) : RuntimeException(message)

    // A stand-in whose simple class name matches the Android type we match on.
    private class SQLiteDatabaseCorruptException(
        message: String,
    ) : RuntimeException(message)

    @Test
    fun `malformed disk image message is corruption`() {
        val e = FakeCorruptException("android.database.sqlite.SQLiteException: database disk image is malformed (code 11)")
        assertTrue(AppDatabase.isCorruptionException(e))
    }

    @Test
    fun `file is not a database message is corruption`() {
        assertTrue(AppDatabase.isCorruptionException(FakeCorruptException("file is not a database")))
    }

    @Test
    fun `encrypted-or-not-a-database message is corruption`() {
        assertTrue(
            AppDatabase.isCorruptionException(
                FakeCorruptException("file is encrypted or is not a database"),
            ),
        )
    }

    @Test
    fun `class name ending in SQLiteDatabaseCorruptException is corruption`() {
        assertTrue(AppDatabase.isCorruptionException(SQLiteDatabaseCorruptException("boom")))
    }

    @Test
    fun `corruption nested in the cause chain is detected`() {
        val root = FakeCorruptException("database disk image is malformed")
        val wrapped = IllegalStateException("failed to query", RuntimeException("dao failure", root))
        assertTrue(AppDatabase.isCorruptionException(wrapped))
    }

    @Test
    fun `ordinary runtime error is not corruption`() {
        assertFalse(AppDatabase.isCorruptionException(IllegalStateException("no rows")))
    }

    @Test
    fun `null throwable is not corruption`() {
        assertFalse(AppDatabase.isCorruptionException(null))
    }

    @Test
    fun `cyclic cause chain terminates`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b) // a -> b -> a cycle
        // Depth bound must prevent an infinite walk; neither is corruption.
        assertFalse(AppDatabase.isCorruptionException(a))
    }
}
