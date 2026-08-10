package com.sysadmindoc.callshield.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.data.model.PendingBlockedCallLog
import com.sysadmindoc.callshield.domain.model.BlockReasonCode
import kotlinx.coroutines.flow.first
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
            assertEquals(1, dao.getCallFrequencySince("+15551234567", since = 0L))

            assertTrue(dao.insertPendingBlockedCallLog(pending.copy(createdAt = 2_000L)) != -1L)
            assertEquals(-1L, dao.consumePendingBlockedCallLog(pending))
            assertEquals(0, dao.getPendingBlockedCallLogCount())
            assertEquals(1, dao.getCallFrequencySince("+15551234567", since = 0L))
        }

    @Test
    fun messageLogsDoNotInflateCallFrequency() =
        runBlocking {
            val number = "+15551234568"
            dao.insertBlockedCall(
                BlockedCall(
                    number = number,
                    timestamp = 1_000L,
                    type = "sms_spam",
                    isCall = false,
                    matchReason = "sms_content",
                ),
            )
            dao.insertBlockedCall(
                BlockedCall(
                    number = number,
                    timestamp = 2_000L,
                    type = "call",
                    isCall = true,
                    matchReason = "database",
                ),
            )

            assertEquals(1, dao.getCallFrequencySince(number, since = 0L))
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

    @Test
    fun pendingLogCarriesStableReasonCodeAndRuleIdIntoFinalLog() =
        runBlocking {
            val pending =
                PendingBlockedCallLog(
                    idempotencyKey = "call:3000:+15550000000:wildcard:90",
                    number = "+15550000000",
                    timestamp = 3_000L,
                    matchReason = "wildcard",
                    confidence = 90,
                    ruleId = 23L,
                )

            dao.insertPendingBlockedCallLog(pending)
            assertEquals(BlockReasonCode.WILDCARD, dao.getReadyPendingBlockedCallLogs(0L, 10).single().reasonCode)
            dao.consumePendingBlockedCallLog(pending)

            val finalLog = dao.getBlockedCalls().first().single()
            assertEquals(BlockReasonCode.WILDCARD, finalLog.reasonCode)
            assertEquals(23L, finalLog.ruleId)
            assertEquals(1, dao.getBlockedCallsByReasonCode(BlockReasonCode.WILDCARD).first().size)
        }
}
