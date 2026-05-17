package com.sysadmindoc.callshield.service

import android.content.Context
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.checker.CheckerDependencies
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.data.local.SpamDao
import com.sysadmindoc.callshield.data.model.HotNumber
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.remote.GitHubDataSource
import com.sysadmindoc.callshield.data.remote.HotFeedDataSource
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

    suspend fun primeBundled(
        context: Context,
        source: HotFeedDataSource = GitHubDataSource(),
        repo: SpamRepository = SpamRepository.getInstance(context.applicationContext),
        dao: SpamDao = AppDatabase.getInstance(context.applicationContext).spamDao(),
        dependencies: CheckerDependencies = CheckerDependencies(),
    ) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext

        val bundledRanges = loadBundledHotRanges(appContext, source)
        if (bundledRanges.resolved) {
            dependencies.spamHeuristics.updateHotRanges(sanitizeHotRanges(bundledRanges.data))
        }

        val bundledDomains = loadBundledSpamDomains(appContext, source)
        if (bundledDomains.resolved) {
            dependencies.smsContentAnalyzer.updateSpamDomains(sanitizeSpamDomains(bundledDomains.data))
        }

        if (dao.getCountBySource(HOT_LIST_SOURCE) == 0) {
            val bundledHotList = loadBundledHotList(appContext, source)
            if (bundledHotList.resolved) {
                repo.replaceHotList(sanitizeHotNumbers(bundledHotList.data, repo::normalizeNumber))
            }
        }
    }

    suspend fun refresh(
        context: Context,
        dependencies: CheckerDependencies = CheckerDependencies(),
    ): RefreshOutcome {
        val appContext = context.applicationContext
        return refresh(
            context = appContext,
            source = GitHubDataSource(),
            repo = SpamRepository.getInstance(appContext),
            dao = AppDatabase.getInstance(appContext).spamDao(),
            dependencies = dependencies,
        )
    }

    suspend fun refresh(
        context: Context,
        source: HotFeedDataSource,
        repo: SpamRepository,
        dao: SpamDao,
        dependencies: CheckerDependencies = CheckerDependencies(),
    ): RefreshOutcome = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext

        val hotList = loadHotList(appContext, source)
        if (hotList.resolved) {
            repo.replaceHotList(sanitizeHotNumbers(hotList.data, repo::normalizeNumber))
        }

        val hotRanges = loadHotRanges(appContext, source)
        if (hotRanges.resolved) {
            dependencies.spamHeuristics.updateHotRanges(sanitizeHotRanges(hotRanges.data))
        }

        val spamDomains = loadSpamDomains(appContext, source)
        if (spamDomains.resolved) {
            dependencies.smsContentAnalyzer.updateSpamDomains(sanitizeSpamDomains(spamDomains.data))
        }

        RefreshOutcome(
            refreshedAnyFeed = hotList.resolved || hotRanges.resolved || spamDomains.resolved,
            hasAnyHotProtection = dao.getCountBySource(HOT_LIST_SOURCE) > 0 ||
                dependencies.spamHeuristics.hasHotRanges() ||
                dependencies.smsContentAnalyzer.hasSpamDomains()
        )
    }

    private suspend fun loadHotList(context: Context, source: HotFeedDataSource): FeedLoadResult<List<HotNumber>> {
        val remote = source.fetchHotList()
        if (remote.isSuccess) {
            return FeedLoadResult(remote.getOrDefault(emptyList()), resolved = true)
        }
        return loadBundledHotList(context, source)
    }

    private suspend fun loadHotRanges(context: Context, source: HotFeedDataSource): FeedLoadResult<List<String>> {
        val remote = source.fetchHotRanges()
        if (remote.isSuccess) {
            return FeedLoadResult(remote.getOrDefault(emptyList()), resolved = true)
        }
        return loadBundledHotRanges(context, source)
    }

    private suspend fun loadSpamDomains(context: Context, source: HotFeedDataSource): FeedLoadResult<List<String>> {
        val remote = source.fetchSpamDomains()
        if (remote.isSuccess) {
            return FeedLoadResult(remote.getOrDefault(emptyList()), resolved = true)
        }
        return loadBundledSpamDomains(context, source)
    }

    private fun loadBundledHotList(context: Context, source: HotFeedDataSource): FeedLoadResult<List<HotNumber>> {
        val bundled = GitHubDataSource.readBundledAsset(context, GitHubDataSource.BUNDLED_HOT_LIST_ASSET)
            .map { source.parseHotListJson(it) }
        return FeedLoadResult(bundled.getOrDefault(emptyList()), bundled.isSuccess)
    }

    private fun loadBundledHotRanges(context: Context, source: HotFeedDataSource): FeedLoadResult<List<String>> {
        val bundled = GitHubDataSource.readBundledAsset(context, GitHubDataSource.BUNDLED_HOT_RANGES_ASSET)
            .map { source.parseHotRangesJson(it) }
        return FeedLoadResult(bundled.getOrDefault(emptyList()), bundled.isSuccess)
    }

    private fun loadBundledSpamDomains(context: Context, source: HotFeedDataSource): FeedLoadResult<List<String>> {
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
