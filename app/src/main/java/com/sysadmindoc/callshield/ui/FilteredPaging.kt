package com.sysadmindoc.callshield.ui

import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.LazyPagingItems
import androidx.paging.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** A row and the filter its page was loaded under. */
data class Filtered<F, T : Any>(
    val filter: F,
    val item: T,
)

/**
 * One pager that follows a filter. A new filter value replaces the pager.
 * Building a pager per filter value and caching each in the view model's
 * scope keeps every one of them running, reloading on each database change,
 * until the view model is cleared.
 *
 * Each row carries the filter it was loaded under. Paging keeps the previous
 * filter's rows on screen until the new filter's first page arrives, and a
 * screen must not show them as the new filter's results ([isStaleFor]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <F, T : Any> Flow<F>.pagedWith(
    scope: CoroutineScope,
    pages: (F) -> Flow<PagingData<T>>,
): Flow<PagingData<Filtered<F, T>>> = flatMapLatest { filter -> pages(filter).map { page -> page.map { Filtered(filter, it) } } }.cachedIn(scope)

/** Whether [first], the top row on screen, was loaded under a filter other than [filter]. */
internal fun <F> isStale(
    filter: F,
    first: Filtered<F, *>?,
): Boolean = first != null && first.filter != filter

/**
 * Whether a list shows its load error: its first page failed, whether the list
 * is empty or Paging still holds the last filter's rows. A refresh that fails
 * under the same filter keeps its rows on screen.
 */
internal fun firstPageFailed(
    refresh: LoadState,
    stale: Boolean,
    itemCount: Int,
): Boolean = refresh is LoadState.Error && (stale || itemCount == 0)

/** Whether the rows on screen still belong to the filter before [filter]. */
internal fun <F, T : Any> LazyPagingItems<Filtered<F, T>>.isStaleFor(filter: F): Boolean = itemCount > 0 && isStale(filter, peek(0))
