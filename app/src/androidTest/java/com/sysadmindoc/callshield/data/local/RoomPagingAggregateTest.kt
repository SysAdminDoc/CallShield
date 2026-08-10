package com.sysadmindoc.callshield.data.local

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.domain.model.BlockReasonCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomPagingAggregateTest {
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
    fun pagingOrdersRowsFiltersRowsAndGroupsCounts() =
        runBlocking {
            dao.insertNumbers(
                listOf(
                    SpamNumber(number = "+15551230001", type = "scam", reports = 9),
                    SpamNumber(number = "+15551230002", type = "robocall", reports = 4),
                    SpamNumber(number = "+15551230003", type = "telemarketer", reports = 2),
                ),
            )
            dao.insertBlockedCall(
                BlockedCall(
                    number = "+15551230001",
                    timestamp = 100L,
                    isCall = true,
                    matchReason = "wildcard",
                    reasonCode = BlockReasonCode.WILDCARD,
                ),
            )
            dao.insertBlockedCall(
                BlockedCall(
                    number = "+15551230001",
                    timestamp = 300L,
                    isCall = true,
                    matchReason = "wildcard",
                    reasonCode = BlockReasonCode.WILDCARD,
                ),
            )
            dao.insertBlockedCall(
                BlockedCall(
                    number = "+15551230002",
                    timestamp = 200L,
                    isCall = false,
                    matchReason = "database",
                    reasonCode = BlockReasonCode.DATABASE,
                ),
            )

            val databasePage =
                dao
                    .pageAllSpamNumbers()
                    .refreshPage(loadSize = 2)
            assertEquals(listOf("+15551230001", "+15551230002"), databasePage.data.map { it.number })
            assertNotNull(databasePage.nextKey)

            val logPage = dao.pageBlockedCalls(null, null).refreshPage(loadSize = 2)
            assertEquals(2, logPage.data.size)
            assertEquals(300L, logPage.data.first().timestamp)

            val wildcardCalls = dao.pageBlockedCalls(1, BlockReasonCode.WILDCARD.wireValue).refreshPage(loadSize = 10)
            assertEquals(2, wildcardCalls.data.size)
            assertTrue(wildcardCalls.data.all { it.reasonCode == BlockReasonCode.WILDCARD && it.isCall })

            val groupedPage = dao.pageGroupedBlockedCalls(null, null).refreshPage(loadSize = 10)
            assertEquals(2, groupedPage.data.size)
            assertEquals("+15551230001", groupedPage.data.first().call.number)
            assertEquals(2, groupedPage.data.first().occurrences)
            assertEquals(1, groupedPage.data[1].occurrences)
        }

    @Test
    fun sqlAggregatesAvoidFullRowMaterialization() =
        runBlocking {
            dao.insertBlockedCall(
                BlockedCall(
                    number = "+15551230001",
                    timestamp = 1_700_000_000_000L,
                    isCall = true,
                    matchReason = "wildcard",
                    reasonCode = BlockReasonCode.WILDCARD,
                ),
            )
            dao.insertBlockedCall(
                BlockedCall(
                    number = "+15551230001",
                    timestamp = 1_700_000_001_000L,
                    isCall = true,
                    matchReason = "wildcard",
                    reasonCode = BlockReasonCode.WILDCARD,
                ),
            )
            dao.insertBlockedCall(
                BlockedCall(
                    number = "+15551230002",
                    timestamp = 1_700_000_002_000L,
                    isCall = false,
                    matchReason = "database",
                    reasonCode = BlockReasonCode.DATABASE,
                ),
            )

            assertEquals(3, dao.observeLogCount().first())
            assertEquals(2, dao.observeLogCallCount().first())
            assertEquals(1, dao.observeLogSmsCount().first())
            assertEquals(2, dao.observeLogNumberCounts(1).first().single().count)
            assertEquals("555", dao.observeLogAreaCodeCounts(1).first().single().key)
            assertEquals(2, dao.observeLogReasonCounts().first().first().count)
            assertEquals(3, dao.observeLogDayCounts(0L).first().sumOf { it.count })
            assertEquals(3, dao.observeLogCountBetween(0L, Long.MAX_VALUE).first())
        }

    @Test
    fun largeFixtureKeepsPagesAndAggregatesBounded() =
        runBlocking {
            val rowCount = 10_000
            dao.insertNumbers(
                (0 until rowCount).map { index ->
                    SpamNumber(
                        number = "+1555${index.toString().padStart(7, '0')}",
                        type = "scam",
                        reports = index,
                    )
                },
            )
            dao.insertBlockedCalls(
                (0 until rowCount).map { index ->
                    BlockedCall(
                        number = "+1555${index.toString().padStart(7, '0')}",
                        timestamp = index.toLong(),
                        logKey = "large-fixture-$index",
                        matchReason = "database",
                        reasonCode = BlockReasonCode.DATABASE,
                    )
                },
            )

            val firstPage = dao.pageAllSpamNumbers().refreshPage(loadSize = 50)
            val secondPage = dao.pageAllSpamNumbers().appendPage(firstPage.nextKey!!, loadSize = 50)
            assertEquals(50, firstPage.data.size)
            assertEquals(50, secondPage.data.size)
            assertTrue(firstPage.data.map { it.id }.intersect(secondPage.data.map { it.id }.toSet()).isEmpty())
            assertEquals(rowCount, dao.observeLogCount().first())
            assertEquals(rowCount, dao.observeLogReasonCounts().first().single().count)
        }

    private suspend fun <T : Any> PagingSource<Int, T>.refreshPage(loadSize: Int): PagingSource.LoadResult.Page<Int, T> {
        val result =
            load(
                PagingSource.LoadParams.Refresh(
                    key = null,
                    loadSize = loadSize,
                    placeholdersEnabled = false,
                ),
            )
        return result as PagingSource.LoadResult.Page<Int, T>
    }

    private suspend fun <T : Any> PagingSource<Int, T>.appendPage(
        key: Int,
        loadSize: Int,
    ): PagingSource.LoadResult.Page<Int, T> {
        val result =
            load(
                PagingSource.LoadParams.Append(
                    key = key,
                    loadSize = loadSize,
                    placeholdersEnabled = false,
                ),
            )
        return result as PagingSource.LoadResult.Page<Int, T>
    }
}
