package com.sysadmindoc.callshield.service

import android.content.Context
import com.sysadmindoc.callshield.data.SmsContentAnalyzer
import com.sysadmindoc.callshield.data.SourceEvidenceCodec
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.checker.CheckerDependencies
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.data.local.SpamDao
import com.sysadmindoc.callshield.data.model.HotNumber
import com.sysadmindoc.callshield.data.model.SourceEvidenceJson
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.remote.GitHubDataSource
import com.sysadmindoc.callshield.data.remote.HotFeedDataSource
import com.sysadmindoc.callshield.util.isAsciiDigit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object HotDataSync {
    private const val HOT_LIST_SOURCE = "hot_list"

    internal data class RefreshOutcome(
        val refreshedAnyFeed: Boolean,
        val hasAnyHotProtection: Boolean,
        val unavailableFeeds: Set<String>,
    )

    private data class FeedLoadResult<T>(
        val data: T,
        val resolved: Boolean,
        val explicitlyCleared: Boolean = false,
    )

    suspend fun primeBundled(
        context: Context,
        source: HotFeedDataSource = GitHubDataSource(),
        repo: SpamRepository = SpamRepository.getInstance(context.applicationContext),
        dao: SpamDao = AppDatabase.getInstance(context.applicationContext).spamDao(),
        dependencies: CheckerDependencies = CheckerDependencies(),
    ) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext

        // Gate every store on emptiness, not just the hot list below. WorkManager
        // can run an overdue HotListSyncWorker in-process right after startup, so
        // an ungated write here can land after its fresh data and replace it with
        // the build-time snapshot until the next 30-minute cycle.
        if (!dependencies.spamHeuristics.hasHotRanges()) {
            val bundledRanges = loadBundledHotRanges(appContext, source)
            val ranges = sanitizeHotRanges(bundledRanges.data)
            if (bundledRanges.resolved && shouldApplyFeed(ranges, bundledRanges.explicitlyCleared)) {
                dependencies.spamHeuristics.updateHotRanges(ranges)
            }
        }

        if (!dependencies.smsContentAnalyzer.hasSpamDomains()) {
            val bundledDomains = loadBundledSpamDomains(appContext, source)
            val domains = sanitizeSpamDomains(bundledDomains.data)
            if (bundledDomains.resolved && shouldApplyFeed(domains, bundledDomains.explicitlyCleared)) {
                dependencies.smsContentAnalyzer.updateSpamDomains(domains)
            }
        }

        if (dao.getCountBySource(HOT_LIST_SOURCE) == 0) {
            val bundledHotList = loadBundledHotList(appContext, source)
            val hotNumbers = sanitizeHotNumbers(bundledHotList.data, repo::normalizeNumber)
            if (bundledHotList.resolved && shouldApplyFeed(hotNumbers, bundledHotList.explicitlyCleared)) {
                repo.replaceHotList(hotNumbers)
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
    ): RefreshOutcome =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext

            // The bundled snapshot is a bootstrap source, never a repair source.
            // replaceHotList is delete-then-insert, so falling back to the
            // build-time asset after a transient fetch failure would delete the
            // freshly synced trending rows and reinstate weeks-old data. Only
            // use it where the corresponding store is still empty.
            val hotList = loadHotList(appContext, source, dao.getCountBySource(HOT_LIST_SOURCE) > 0)
            val hotNumbers = sanitizeHotNumbers(hotList.data, repo::normalizeNumber)
            val hotListApplied = shouldApplyFeed(hotNumbers, hotList.explicitlyCleared)
            if (hotList.resolved && hotListApplied) {
                repo.replaceHotList(hotNumbers)
            }

            val hotRanges = loadHotRanges(appContext, source, dependencies.spamHeuristics.hasHotRanges())
            val ranges = sanitizeHotRanges(hotRanges.data)
            val hotRangesApplied = shouldApplyFeed(ranges, hotRanges.explicitlyCleared)
            if (hotRanges.resolved && hotRangesApplied) {
                dependencies.spamHeuristics.updateHotRanges(ranges)
            }

            val spamDomains = loadSpamDomains(appContext, source, dependencies.smsContentAnalyzer.hasSpamDomains())
            val domains = sanitizeSpamDomains(spamDomains.data)
            val spamDomainsApplied = shouldApplyFeed(domains, spamDomains.explicitlyCleared)
            if (spamDomains.resolved && spamDomainsApplied) {
                dependencies.smsContentAnalyzer.updateSpamDomains(domains)
            }

            val unavailableFeeds =
                buildSet {
                    if (!hotList.resolved || !hotListApplied) add(HOT_LIST_FEED)
                    if (!hotRanges.resolved || !hotRangesApplied) add(HOT_RANGES_FEED)
                    if (!spamDomains.resolved || !spamDomainsApplied) add(SPAM_DOMAINS_FEED)
                }
            repo.recordHotDataHealth(
                lastGoodTimestamp = System.currentTimeMillis().takeIf { unavailableFeeds.isEmpty() },
                unavailableFeeds = unavailableFeeds,
            )
            RefreshOutcome(
                refreshedAnyFeed = hotListApplied || hotRangesApplied || spamDomainsApplied,
                hasAnyHotProtection =
                    dao.getCountBySource(HOT_LIST_SOURCE) > 0 ||
                        dependencies.spamHeuristics.hasHotRanges() ||
                        dependencies.smsContentAnalyzer.hasSpamDomains(),
                unavailableFeeds = unavailableFeeds,
            )
        }

    /**
     * The bundled snapshot is a bootstrap source, not a repair source.
     *
     * Applying a feed marks it `resolved`, and resolving triggers a destructive
     * replace (`replaceHotList` is delete-then-insert). So after a transient
     * fetch failure the build-time asset would delete the freshly synced
     * trending rows and reinstate weeks-old data. It may only be used to
     * populate a store that is still empty.
     */
    internal fun shouldUseBundledFallback(
        remoteSucceeded: Boolean,
        hasExistingData: Boolean,
    ): Boolean = !remoteSucceeded && !hasExistingData

    private suspend fun loadHotList(
        context: Context,
        source: HotFeedDataSource,
        hasExistingData: Boolean,
    ): FeedLoadResult<List<HotNumber>> {
        val remote = source.fetchHotListSnapshot()
        if (remote.isSuccess) {
            val snapshot = remote.getOrThrow()
            return FeedLoadResult(snapshot.data, resolved = true, explicitlyCleared = snapshot.explicitlyCleared)
        }
        if (!shouldUseBundledFallback(false, hasExistingData)) {
            return FeedLoadResult(emptyList(), resolved = false)
        }
        return loadBundledHotList(context, source)
    }

    private suspend fun loadHotRanges(
        context: Context,
        source: HotFeedDataSource,
        hasExistingData: Boolean,
    ): FeedLoadResult<List<String>> {
        val remote = source.fetchHotRangesSnapshot()
        if (remote.isSuccess) {
            val snapshot = remote.getOrThrow()
            return FeedLoadResult(snapshot.data, resolved = true, explicitlyCleared = snapshot.explicitlyCleared)
        }
        if (!shouldUseBundledFallback(false, hasExistingData)) {
            return FeedLoadResult(emptyList(), resolved = false)
        }
        return loadBundledHotRanges(context, source)
    }

    private suspend fun loadSpamDomains(
        context: Context,
        source: HotFeedDataSource,
        hasExistingData: Boolean,
    ): FeedLoadResult<List<String>> {
        val remote = source.fetchSpamDomainsSnapshot()
        if (remote.isSuccess) {
            val snapshot = remote.getOrThrow()
            return FeedLoadResult(snapshot.data, resolved = true, explicitlyCleared = snapshot.explicitlyCleared)
        }
        if (!shouldUseBundledFallback(false, hasExistingData)) {
            return FeedLoadResult(emptyList(), resolved = false)
        }
        return loadBundledSpamDomains(context, source)
    }

    private fun loadBundledHotList(
        context: Context,
        source: HotFeedDataSource,
    ): FeedLoadResult<List<HotNumber>> {
        val bundled =
            GitHubDataSource
                .readBundledAsset(context, GitHubDataSource.BUNDLED_HOT_LIST_ASSET)
                .map { source.parseHotListSnapshotJson(it) }
        val snapshot = bundled.getOrNull()
        return FeedLoadResult(
            data = snapshot?.data.orEmpty(),
            resolved = bundled.isSuccess,
            explicitlyCleared = snapshot?.explicitlyCleared == true,
        )
    }

    private fun loadBundledHotRanges(
        context: Context,
        source: HotFeedDataSource,
    ): FeedLoadResult<List<String>> {
        val bundled =
            GitHubDataSource
                .readBundledAsset(context, GitHubDataSource.BUNDLED_HOT_RANGES_ASSET)
                .map { source.parseHotRangesSnapshotJson(it) }
        val snapshot = bundled.getOrNull()
        return FeedLoadResult(
            data = snapshot?.data.orEmpty(),
            resolved = bundled.isSuccess,
            explicitlyCleared = snapshot?.explicitlyCleared == true,
        )
    }

    private fun loadBundledSpamDomains(
        context: Context,
        source: HotFeedDataSource,
    ): FeedLoadResult<List<String>> {
        val bundled =
            GitHubDataSource
                .readBundledAsset(context, GitHubDataSource.BUNDLED_SPAM_DOMAINS_ASSET)
                .map { source.parseSpamDomainsSnapshotJson(it) }
        val snapshot = bundled.getOrNull()
        return FeedLoadResult(
            data = snapshot?.data.orEmpty(),
            resolved = bundled.isSuccess,
            explicitlyCleared = snapshot?.explicitlyCleared == true,
        )
    }

    internal fun shouldApplyFeed(
        data: Collection<*>,
        explicitlyCleared: Boolean,
    ): Boolean = data.isNotEmpty() || explicitlyCleared

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
                    source = HOT_LIST_SOURCE,
                    evidenceJson =
                        SourceEvidenceCodec.encode(
                            listOf(
                                SourceEvidenceJson(
                                    sourceId = HOT_LIST_SOURCE,
                                    evidenceType = "community_velocity",
                                    license = "CallShield community report policy",
                                    attribution = "CallShield hot-list generator",
                                    retrievedAt =
                                        java.time.Instant
                                            .now()
                                            .toString(),
                                    confidenceTier = "unverified",
                                    parserVersion = "hot-list-v1",
                                    expiresAtEpochMs = System.currentTimeMillis() + HOT_LIST_EVIDENCE_TTL_MS,
                                ),
                            ),
                        ),
                    evidenceExpiresAt = System.currentTimeMillis() + HOT_LIST_EVIDENCE_TTL_MS,
                )
            }
        }
    }

    internal fun sanitizeHotRanges(ranges: Collection<String>): List<String> =
        ranges
            .asSequence()
            .map { it.trim() }
            // ASCII-only digits: the screening path compares against
            // filterAsciiDigits() output, so a Unicode-digit range would be
            // admitted here yet never match (silent hot-campaign degradation).
            .filter { range -> range.length == 6 && range.all { it.isAsciiDigit() } }
            .distinct()
            .toList()

    internal fun sanitizeSpamDomains(domains: Collection<String>): List<String> =
        domains
            .asSequence()
            .mapNotNull(SmsContentAnalyzer::normalizeDomainCandidate)
            .distinct()
            .toList()

    private fun canonicalNumberKey(number: String): String = number.trim()

    private const val HOT_LIST_FEED = "hot_list"
    private const val HOT_RANGES_FEED = "hot_ranges"
    private const val SPAM_DOMAINS_FEED = "spam_domains"
    private const val HOT_LIST_EVIDENCE_TTL_MS = 7L * 24L * 60L * 60L * 1000L
}
