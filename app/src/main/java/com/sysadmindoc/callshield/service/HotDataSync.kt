package com.sysadmindoc.callshield.service

import android.content.Context
import com.sysadmindoc.callshield.data.SmsContentAnalyzer
import com.sysadmindoc.callshield.data.SpamHeuristics
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.data.model.HotNumber
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.remote.GitHubDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object HotDataSync {
    private const val HOT_LIST_SOURCE = "hot_list"

    internal data class RefreshOutcome(
        val refreshedAnyFeed: Boolean,
        val hasAnyHotProtection: Boolean,
    )

    private data class FeedLoadResult<T>(
        val data: T,
        val resolved: Boolean,
    )

    suspend fun primeBundled(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val source = GitHubDataSource()
        val repo = SpamRepository.getInstance(appContext)
        val dao = AppDatabase.getInstance(appContext).spamDao()

        val bundledRanges = loadBundledHotRanges(appContext, source)
        if (bundledRanges.resolved) {
            SpamHeuristics.updateHotRanges(sanitizeHotRanges(bundledRanges.data))
        }

        val bundledDomains = loadBundledSpamDomains(appContext, source)
        if (bundledDomains.resolved) {
            SmsContentAnalyzer.updateSpamDomains(sanitizeSpamDomains(bundledDomains.data))
        }

        if (dao.getCountBySource(HOT_LIST_SOURCE) == 0) {
            val bundledHotList = loadBundledHotList(appContext, source)
            if (bundledHotList.resolved) {
                repo.replaceHotList(sanitizeHotNumbers(bundledHotList.data, repo::normalizeNumber))
            }
        }
    }

    suspend fun refresh(context: Context): RefreshOutcome = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val source = GitHubDataSource()
        val repo = SpamRepository.getInstance(appContext)
        val dao = AppDatabase.getInstance(appContext).spamDao()

        val hotList = loadHotList(appContext, source)
        if (hotList.resolved) {
            repo.replaceHotList(sanitizeHotNumbers(hotList.data, repo::normalizeNumber))
        }

        val hotRanges = loadHotRanges(appContext, source)
        if (hotRanges.resolved) {
            SpamHeuristics.updateHotRanges(sanitizeHotRanges(hotRanges.data))
        }

        val spamDomains = loadSpamDomains(appContext, source)
        if (spamDomains.resolved) {
            SmsContentAnalyzer.updateSpamDomains(sanitizeSpamDomains(spamDomains.data))
        }

        RefreshOutcome(
            refreshedAnyFeed = hotList.resolved || hotRanges.resolved || spamDomains.resolved,
            hasAnyHotProtection = dao.getCountBySource(HOT_LIST_SOURCE) > 0 ||
                SpamHeuristics.hasHotRanges() ||
                SmsContentAnalyzer.hasSpamDomains()
        )
    }

    private suspend fun loadHotList(context: Context, source: GitHubDataSource): FeedLoadResult<List<HotNumber>> {
        val remote = source.fetchHotList()
        if (remote.isSuccess) {
            return FeedLoadResult(remote.getOrDefault(emptyList()), resolved = true)
        }
        return loadBundledHotList(context, source)
    }

    private suspend fun loadHotRanges(context: Context, source: GitHubDataSource): FeedLoadResult<List<String>> {
        val remote = source.fetchHotRanges()
        if (remote.isSuccess) {
            return FeedLoadResult(remote.getOrDefault(emptyList()), resolved = true)
        }
        return loadBundledHotRanges(context, source)
    }

    private suspend fun loadSpamDomains(context: Context, source: GitHubDataSource): FeedLoadResult<List<String>> {
        val remote = source.fetchSpamDomains()
        if (remote.isSuccess) {
            return FeedLoadResult(remote.getOrDefault(emptyList()), resolved = true)
        }
        return loadBundledSpamDomains(context, source)
    }

    private fun loadBundledHotList(context: Context, source: GitHubDataSource): FeedLoadResult<List<HotNumber>> {
        val bundled = GitHubDataSource.readBundledAsset(context, GitHubDataSource.BUNDLED_HOT_LIST_ASSET)
            .map { source.parseHotListJson(it) }
        return FeedLoadResult(bundled.getOrDefault(emptyList()), bundled.isSuccess)
    }

    private fun loadBundledHotRanges(context: Context, source: GitHubDataSource): FeedLoadResult<List<String>> {
        val bundled = GitHubDataSource.readBundledAsset(context, GitHubDataSource.BUNDLED_HOT_RANGES_ASSET)
            .map { source.parseHotRangesJson(it) }
        return FeedLoadResult(bundled.getOrDefault(emptyList()), bundled.isSuccess)
    }

    private fun loadBundledSpamDomains(context: Context, source: GitHubDataSource): FeedLoadResult<List<String>> {
        val bundled = GitHubDataSource.readBundledAsset(context, GitHubDataSource.BUNDLED_SPAM_DOMAINS_ASSET)
            .map { source.parseSpamDomainsJson(it) }
        return FeedLoadResult(bundled.getOrDefault(emptyList()), bundled.isSuccess)
    }

    internal fun sanitizeHotNumbers(
        hotNumbers: Collection<HotNumber>,
        normalizeNumber: (String) -> String,
    ): List<SpamNumber> {
        val deduped = linkedSetOf<String>()

        return hotNumbers.mapNotNull { hot ->
            val normalizedNumber = normalizeNumber(hot.number)
            val dedupeKey = canonicalNumberKey(normalizedNumber)
            if (normalizedNumber.isBlank() || dedupeKey.isBlank() || !deduped.add(dedupeKey)) {
                null
            } else {
                SpamNumber(
                    number = normalizedNumber,
                    type = hot.type.trim().ifBlank { "robocall" },
                    reports = 1,
                    description = hot.description.trim().ifBlank { "Trending community report" },
                    source = HOT_LIST_SOURCE
                )
            }
        }
    }

    internal fun sanitizeHotRanges(ranges: Collection<String>): List<String> {
        return ranges
            .asSequence()
            .map { it.trim() }
            .filter { it.length == 6 && it.all(Char::isDigit) }
            .distinct()
            .toList()
    }

    internal fun sanitizeSpamDomains(domains: Collection<String>): List<String> {
        return domains
            .asSequence()
            .map { domain ->
                domain.trim()
                    .lowercase()
                    .removePrefix("https://")
                    .removePrefix("http://")
                    .removePrefix("www.")
                    .substringBefore('/')
                    .substringBefore('?')
                    .substringBefore('#')
                    .substringBefore(':')
            }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    private fun canonicalNumberKey(number: String): String {
        val digits = number.filter(Char::isDigit)
        return if (digits.length == 11 && digits.startsWith("1")) digits.drop(1) else digits
    }
}
