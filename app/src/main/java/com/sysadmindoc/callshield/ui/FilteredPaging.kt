package com.sysadmindoc.callshield.ui

import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * One pager that follows a filter. A new filter value replaces the pager.
 * Building a pager per filter value and caching each in the view model's
 * scope keeps every one of them running, reloading on each database change,
 * until the view model is cleared.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <F, T : Any> Flow<F>.pagedWith(
    scope: CoroutineScope,
    pages: (F) -> Flow<PagingData<T>>,
): Flow<PagingData<T>> = flatMapLatest(pages).cachedIn(scope)
