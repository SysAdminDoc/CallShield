package com.sysadmindoc.callshield.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sysadmindoc.callshield.data.model.PendingBlockedCallLog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingBlockedCallLogDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: SpamDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = database.spamDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun pendingLogQueueSuppressesDuplicateFinalLogs() =
        runBlocking {
            val pending =
                PendingBlockedCallLog(
                    idempotencyKey = "call:1000:+15551234567:database:100",
                    number = "+15551234567",
                    timestamp = 1_000L,
                    matchReason = "database",
                    confidence = 100,
                )

            assertTrue(dao.insertPendingBlockedCallLog(pending) != -1L)
            assertEquals(-1L, dao.insertPendingBlockedCallLog(pending))
            assertEquals(1, dao.getReadyPendingBlockedCallLogs(now = 0L, limit = 10).size)

            assertTrue(dao.consumePendingBlockedCallLog(pending) != -1L)
            assertEquals(0, dao.getPendingBlockedCallLogCount())
            assertEquals(1, dao.getNumberFrequencySince("+15551234567", since = 0L))

            assertTrue(dao.insertPendingBlockedCallLog(pending.copy(createdAt = 2_000L)) != -1L)
            assertEquals(-1L, dao.consumePendingBlockedCallLog(pending))
            assertEquals(0, dao.getPendingBlockedCallLogCount())
            assertEquals(1, dao.getNumberFrequencySince("+15551234567", since = 0L))
        }

    @Test
    fun failedPendingLogIsHeldUntilRetryTime() =
        runBlocking {
            val pending =
                PendingBlockedCallLog(
                    idempotencyKey = "call:2000:+15557654321:heuristic:80",
                    number = "+15557654321",
                    timestamp = 2_000L,
                    matchReason = "heuristic",
                    confidence = 80,
                )

            dao.insertPendingBlockedCallLog(pending)
            dao.markPendingBlockedCallLogFailed(
                idempotencyKey = pending.idempotencyKey,
                nextAttemptAt = 10_000L,
            )

            assertEquals(0, dao.getReadyPendingBlockedCallLogs(now = 9_999L, limit = 10).size)

            val ready = dao.getReadyPendingBlockedCallLogs(now = 10_000L, limit = 10)
            assertEquals(1, ready.size)
            assertEquals(1, ready.single().attempts)
        }
}
