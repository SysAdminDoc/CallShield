package com.sysadmindoc.callshield.data

import android.content.Context
import androidx.paging.PagingSource
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.model.SpamNumber
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseFilterPagingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fixture: IsolatedRepositoryFixture

    @Before
    fun setUp() {
        fixture = IsolatedRepositoryFixture(context)
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    private suspend fun numbers(
        type: DatabaseTypeFilter,
        source: DatabaseSourceFilter,
        trending: Set<String> = emptySet(),
    ): List<String> =
        fixture.repository
            .pageSpamNumbers(type, source, trending)
            .refresh(loadSize = 500)
            .data
            .map { it.number }

    @Test
    fun `a hot list over the query limit pages the same whatever order it arrives in`() =
        runBlocking {
            fixture.dao.insertNumbers(listOf(SpamNumber(number = "+12025550100", type = "robocall", reports = 4, source = "github")))
            // 1,000 numbers, more than one query takes; the one in the database first or last.
            val filler = (0 until 999).map { "+1999555" + it.toString().padStart(4, '0') }
            val forward = LinkedHashSet(listOf("+12025550100") + filler)
            val backward = LinkedHashSet(forward.reversed())

            assertEquals(listOf("+12025550100"), numbers(DatabaseTypeFilter.ALL, DatabaseSourceFilter.TRENDING, forward))
            assertEquals(listOf("+12025550100"), numbers(DatabaseTypeFilter.ALL, DatabaseSourceFilter.TRENDING, backward))
        }

    @Test
    fun `a subscribed list's capitalized types land under their own chips`() =
        runBlocking {
            // External lists store the type as written.
            fixture.dao.insertNumbers(
                listOf(
                    SpamNumber(number = "+12125550111", type = "Robocall", reports = 3, source = "subscription:acme"),
                    SpamNumber(number = "+12125550112", type = "SPAM", reports = 2, source = "subscription:acme"),
                    SpamNumber(number = "+12125550113", type = "Debt Collection", reports = 1, source = "subscription:acme"),
                ),
            )

            assertEquals(listOf("+12125550111"), numbers(DatabaseTypeFilter.ROBOCALL, DatabaseSourceFilter.ALL))
            assertEquals(listOf("+12125550112"), numbers(DatabaseTypeFilter.SPAM, DatabaseSourceFilter.ALL))
            assertEquals(listOf("+12125550113"), numbers(DatabaseTypeFilter.OTHER, DatabaseSourceFilter.ALL))
        }

    @Test
    fun `the trending chip also shows trending numbers that were already in the database`() =
        runBlocking {
            // The hot list keeps an existing database row rather than adding a trending one.
            fixture.dao.insertNumbers(
                listOf(
                    SpamNumber(number = "+12125550121", type = "robocall", reports = 9, source = "github"),
                    SpamNumber(number = "+12125550122", type = "robocall", reports = 8, source = "hot_list"),
                    SpamNumber(number = "+12125550123", type = "robocall", reports = 7, source = "github"),
                ),
            )

            assertEquals(
                listOf("+12125550121", "+12125550122"),
                numbers(DatabaseTypeFilter.ALL, DatabaseSourceFilter.TRENDING, trending = setOf("+12125550121", "+12125550122")),
            )
            assertEquals(listOf("+12125550122"), numbers(DatabaseTypeFilter.ALL, DatabaseSourceFilter.TRENDING))
            assertEquals(
                "the trending set only widens the Trending chip",
                listOf("+12125550121", "+12125550123"),
                numbers(DatabaseTypeFilter.ALL, DatabaseSourceFilter.DATABASE, trending = setOf("+12125550121")),
            )
        }

    @Test
    fun `type and source chips narrow the list and All clears them`() =
        runBlocking {
            fixture.dao.insertNumbers(
                listOf(
                    SpamNumber(number = "+12125550101", type = "robocall", reports = 9, source = "github"),
                    SpamNumber(number = "+12125550102", type = "telemarketer", reports = 8, source = "github"),
                    SpamNumber(number = "+12125550103", type = "sms_spam", reports = 7, source = "hot_list"),
                    SpamNumber(number = "+12125550104", type = "debt_collector", reports = 6, source = "subscription:ftc"),
                    SpamNumber(number = "+12125550105", type = "spam", reports = 5, source = "user", isUserBlocked = true),
                    SpamNumber(number = "+12125550106", type = "unknown", reports = 4, source = "github"),
                ),
            )

            assertEquals(6, numbers(DatabaseTypeFilter.ALL, DatabaseSourceFilter.ALL).size)
            assertEquals(listOf("+12125550101"), numbers(DatabaseTypeFilter.ROBOCALL, DatabaseSourceFilter.ALL))
            assertEquals(listOf("+12125550103"), numbers(DatabaseTypeFilter.SPAM_TEXT, DatabaseSourceFilter.ALL))
            assertEquals(
                listOf("+12125550101", "+12125550102", "+12125550106"),
                numbers(DatabaseTypeFilter.ALL, DatabaseSourceFilter.DATABASE),
            )
            assertEquals(listOf("+12125550103"), numbers(DatabaseTypeFilter.ALL, DatabaseSourceFilter.TRENDING))
            assertEquals(listOf("+12125550104"), numbers(DatabaseTypeFilter.ALL, DatabaseSourceFilter.LISTS))
            assertEquals(listOf("+12125550105"), numbers(DatabaseTypeFilter.ALL, DatabaseSourceFilter.MINE))
            assertEquals(listOf("+12125550106"), numbers(DatabaseTypeFilter.OTHER, DatabaseSourceFilter.DATABASE))
            assertEquals(emptyList<String>(), numbers(DatabaseTypeFilter.ROBOCALL, DatabaseSourceFilter.TRENDING))
        }

    @Test
    fun `the other chip holds exactly the types that have no chip of their own`() =
        runBlocking {
            // One row per named chip, taken from the enum: a chip added there
            // without its type in SpamDao's NOT IN list shows up under Other too.
            val named = DatabaseTypeFilter.entries.filterNot { it == DatabaseTypeFilter.ALL || it == DatabaseTypeFilter.OTHER }
            fixture.dao.insertNumbers(
                named.mapIndexed { index, filter ->
                    SpamNumber(number = "+1212555020$index", type = filter.key, source = "github")
                } + SpamNumber(number = "+12125550299", type = "political", source = "github"),
            )

            assertEquals(listOf("+12125550299"), numbers(DatabaseTypeFilter.OTHER, DatabaseSourceFilter.ALL))
        }

    @Test
    fun `a broad search pages past a hundred matches and counts them all`() =
        runBlocking {
            fixture.dao.insertNumbers(
                (0 until 150).map { index ->
                    SpamNumber(number = "+1212555" + index.toString().padStart(4, '0'), type = "robocall", reports = index)
                },
            )

            val source = fixture.repository.pageSearchNumbers("555")
            val first = source.refresh(loadSize = 50)
            val second = source.append(requireNotNull(first.nextKey), loadSize = 50)
            val third = source.append(requireNotNull(second.nextKey), loadSize = 50)

            assertEquals(150, (first.data + second.data + third.data).map { it.number }.toSet().size)
            assertEquals(150, fixture.repository.observeSearchCount("555").first())
        }

    private suspend fun <T : Any> PagingSource<Int, T>.refresh(loadSize: Int): PagingSource.LoadResult.Page<Int, T> =
        load(PagingSource.LoadParams.Refresh(key = null, loadSize = loadSize, placeholdersEnabled = false))
            as PagingSource.LoadResult.Page<Int, T>

    private suspend fun <T : Any> PagingSource<Int, T>.append(
        key: Int,
        loadSize: Int,
    ): PagingSource.LoadResult.Page<Int, T> =
        load(PagingSource.LoadParams.Append(key = key, loadSize = loadSize, placeholdersEnabled = false))
            as PagingSource.LoadResult.Page<Int, T>
}
