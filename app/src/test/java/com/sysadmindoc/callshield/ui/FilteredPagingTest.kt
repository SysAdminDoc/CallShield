package com.sysadmindoc.callshield.ui

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class FilteredPagingTest {
    /** A stand-in pager that counts how many are running. */
    private class Pages {
        val running = AtomicInteger()
        val started = Channel<String>(Channel.UNLIMITED)

        fun forFilter(filter: String): Flow<PagingData<String>> =
            flow {
                running.incrementAndGet()
                try {
                    started.send(filter)
                    emit(PagingData.from(listOf(filter)))
                    awaitCancellation()
                } finally {
                    running.decrementAndGet()
                }
            }
    }

    private suspend fun Pages.awaitRunning(count: Int) =
        withTimeout(5_000L) {
            while (running.get() != count) delay(10L)
        }

    @Test
    fun `changing the filter stops the previous pager`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val pages = Pages()
            val filter = MutableStateFlow("robocall")
            try {
                val paged = filter.pagedWith(scope, pages::forFilter)
                scope.launch { paged.collect {} }

                for (next in listOf("spam", "trending", "mine")) {
                    withTimeout(5_000L) { pages.started.receive() }
                    filter.value = next
                }
                assertEquals("mine", withTimeout(5_000L) { pages.started.receive() })
                pages.awaitRunning(1)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a pager per filter value, each cached, keeps every one running`() =
        runBlocking {
            // The pattern pagedWith replaces, kept as the control: without it the
            // test above could pass because stand-in pagers stop on their own.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val pages = Pages()
            try {
                for (filter in listOf("robocall", "spam", "trending", "mine")) {
                    val collector = scope.launch { pages.forFilter(filter).cachedIn(scope).collect {} }
                    withTimeout(5_000L) { pages.started.receive() }
                    collector.cancel()
                }
                pages.awaitRunning(4)
            } finally {
                scope.cancel()
            }
        }

    /** One page of fixed rows; a gated source holds its first load back. */
    private class RowsSource(
        private val rows: List<String>,
        private val gate: CompletableDeferred<Unit>?,
    ) : PagingSource<Int, String>() {
        override fun getRefreshKey(state: PagingState<Int, String>): Int? = null

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, String> {
            gate?.await()
            return LoadResult.Page(rows, prevKey = null, nextKey = null)
        }
    }

    /** Holds the rows a screen would show, the way LazyPagingItems does. */
    private class Screen : PagingDataPresenter<Filtered<String, String>>(Dispatchers.Unconfined) {
        override suspend fun presentPagingDataEvent(event: PagingDataEvent<Filtered<String, String>>) = Unit

        fun shows(filter: String): Boolean = size > 0 && !isStale(filter, peek(0))
    }

    @Test
    fun `the last filter's rows are marked stale until the new filter's first page arrives`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val filter = MutableStateFlow("robocall")
            val spamLoads = CompletableDeferred<Unit>()
            val screen = Screen()
            try {
                val paged =
                    filter.pagedWith(scope) { f ->
                        Pager(PagingConfig(pageSize = 2, enablePlaceholders = false)) {
                            RowsSource(listOf("$f-1", "$f-2"), spamLoads.takeIf { f == "spam" })
                        }.flow
                    }
                scope.launch { paged.collectLatest { screen.collectFrom(it) } }
                withTimeout(5_000L) { while (!screen.shows("robocall")) delay(10L) }

                filter.value = "spam"
                // Paging keeps robocall's rows on screen while spam's first page loads.
                delay(200L)
                assertEquals(Filtered("robocall", "robocall-1"), screen.peek(0))
                assertTrue(isStale("spam", screen.peek(0)))

                spamLoads.complete(Unit)
                withTimeout(5_000L) { while (!screen.shows("spam")) delay(10L) }
                assertEquals(listOf("spam-1", "spam-2"), screen.snapshot().items.map { it.item })
            } finally {
                scope.cancel()
            }
        }
}
