package com.sysadmindoc.callshield.ui

import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
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
}
