package com.sysadmindoc.callshield.service

import android.content.Context
import com.sysadmindoc.callshield.data.SmsContentAnalyzer
import com.sysadmindoc.callshield.data.SourceEvidenceCodec
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.checker.CheckerDependencies
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.data.local.SpamDao
import com.sysadmindoc.callshield.data.model.HotDataHealthUpdate
import com.sysadmindoc.callshield.data.model.HotNumber
import com.sysadmindoc.callshield.data.model.SourceEvidenceJson
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.remote.GitHubDataSource
import com.sysadmindoc.callshield.data.remote.GitHubFeedValidationException
import com.sysadmindoc.callshield.data.remote.HotFeedDataSource
import com.sysadmindoc.callshield.data.remote.HttpClient
import com.sysadmindoc.callshield.util.HotFeedFreshness
import com.sysadmindoc.callshield.util.isAsciiDigit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

internal object HotDataSync {
    private const val HOT_LIST_SOURCE = "hot_list"

    internal data class RefreshOutcome(
        val refreshedAnyFeed: Boolean,
        val hasAnyHotProtection: Boolean,
        val unavailableFeeds: Set<String>,
    )

    internal data class FeedLoadResult<T>(
        val data: T,
        val resolved: Boolean,
        val explicitlyCleared: Boolean = false,
        /** The feed's own `generated` value, when the publisher declared one. */
        val generatedAt: String? = null,
        /** The report-queue digest the publisher generated this feed from. */
        val inputDigest: String? = null,
        /** Why the network fetch failed, when it did, even if the bundled snapshot then filled in. */
        val failure: Throwable? = null,
    ) {
        fun observe(
            feed: String,
            applied: Boolean,
            empty: Boolean,
            replay: Boolean = false,
        ) = FeedObservation(
            feed = feed,
            resolved = resolved,
            applied = applied,
            empty = empty,
            generatedAt = generatedAt,
            inputDigest = inputDigest,
            // The bundled snapshot resolves a feed after a failed fetch. It is
            // not evidence the publisher is reachable, current, or cleared.
            fromNetwork = resolved && failure == null,
            // A host that served a file the app then refused was reachable.
            refused = replay || failure is GitHubFeedValidationException,
        )
    }

    /** What one refresh learned about one feed, reduced to what the health record needs. */
    internal data class FeedObservation(
        val feed: String,
        /** The file was read, from the network or the bundled bootstrap. */
        val resolved: Boolean,
        /** Its contents replaced the local data. */
        val applied: Boolean,
        /** It carried no usable entries after sanitising. */
        val empty: Boolean,
        val generatedAt: String? = null,
        val inputDigest: String? = null,
        /** The file came from the network, not the bundled snapshot. */
        val fromNetwork: Boolean = resolved,
        /** The host served it, but it failed its signature check or was older than the copy in use. */
        val refused: Boolean = false,
    )

    /**
     * Reduce one refresh to a health update.
     *
     * A feed that arrived empty without `cleared` is refused so local rows survive,
     * but it was still reachable: it lands in `unavailableFeeds`, which keeps the
     * worker retrying, and not in `unreachableFeeds`, so the publisher is judged by
     * its own `generated` stamp rather than reported as a network failure. An empty
     * feed that was applied can only have been cleared on purpose.
     *
     * Only network reads say anything about the publisher. A feed the bundled
     * snapshot filled in after a failed fetch is unreachable, clears nothing, and
     * leaves the stored stamps alone.
     */
    internal fun healthUpdate(
        observations: List<FeedObservation>,
        now: Long = System.currentTimeMillis(),
    ): HotDataHealthUpdate {
        // A refused file says nothing trustworthy about the publisher, and its
        // stamp must not replace the newer one a replay check compares against.
        val read = observations.filter { it.fromNetwork && !it.refused }
        return HotDataHealthUpdate(
            unavailableFeeds = observations.filterNot { it.resolved && it.applied }.mapTo(mutableSetOf()) { it.feed },
            unreachableFeeds = observations.filterNot { it.fromNetwork || it.refused }.mapTo(mutableSetOf()) { it.feed },
            refusedFeeds = observations.filter { it.refused }.mapTo(mutableSetOf()) { it.feed },
            clearedFeeds = read.filter { it.applied && it.empty }.mapTo(mutableSetOf()) { it.feed },
            resolvedFeeds = read.mapTo(mutableSetOf()) { it.feed },
            feedGeneratedAt = read.metadata { observation -> observation.generatedAt?.let { cappedStamp(it, now) } },
            feedDigests = read.metadata { it.inputDigest },
        )
    }

    private fun List<FeedObservation>.metadata(value: (FeedObservation) -> String?): Map<String, String> =
        mapNotNull { observation ->
            value(observation)?.takeIf { it.isNotBlank() }?.let { observation.feed to it }
        }.toMap()

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

        // No rows can also mean the last hot list was all numbers already in
        // the database, which get no rows of their own. Only a device that has
        // never applied a hot list gets the build-time snapshot, and that
        // snapshot never counts as trending.
        if (dao.getCountBySource(HOT_LIST_SOURCE) == 0 && !repo.hasAppliedHotList()) {
            val bundledHotList = loadBundledHotList(appContext, source)
            val hotNumbers = sanitizeHotNumbers(bundledHotList.data, repo::normalizeNumber)
            if (bundledHotList.resolved && shouldApplyFeed(hotNumbers, bundledHotList.explicitlyCleared)) {
                repo.replaceHotList(hotNumbers, recordTrending = false)
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
            val lastRead = repo.readHotDataHealth().feedGeneratedAt

            // The bundled snapshot is a bootstrap source, never a repair source.
            // replaceHotList is delete-then-insert, so falling back to the
            // build-time asset after a transient fetch failure would delete the
            // freshly synced trending rows and reinstate weeks-old data. Only
            // use it where the corresponding store is still empty.
            val hotListSeen = dao.getCountBySource(HOT_LIST_SOURCE) > 0 || repo.hasAppliedHotList()
            val hotList = loadHotList(appContext, source, hotListSeen)
            val hotNumbers = sanitizeHotNumbers(hotList.data, repo::normalizeNumber)
            val hotListReplay = isReplay(hotList.generatedAt, lastRead[HOT_LIST_FEED])
            val hotListApplied = !hotListReplay && shouldApplyFeed(hotNumbers, hotList.explicitlyCleared)
            if (hotList.resolved && hotListApplied) {
                // A failure means the bundled snapshot stood in for the network.
                repo.replaceHotList(
                    hotNumbers,
                    recordTrending = hotList.failure == null,
                    appliedAt = trendingSince(hotList.generatedAt, System.currentTimeMillis()),
                )
            }

            val hotRanges = loadHotRanges(appContext, source, dependencies.spamHeuristics.hasHotRanges())
            val ranges = sanitizeHotRanges(hotRanges.data)
            val hotRangesReplay = isReplay(hotRanges.generatedAt, lastRead[HOT_RANGES_FEED])
            val hotRangesApplied = !hotRangesReplay && shouldApplyFeed(ranges, hotRanges.explicitlyCleared)
            if (hotRanges.resolved && hotRangesApplied) {
                dependencies.spamHeuristics.updateHotRanges(ranges)
            }

            val spamDomains = loadSpamDomains(appContext, source, dependencies.smsContentAnalyzer.hasSpamDomains())
            val domains = sanitizeSpamDomains(spamDomains.data)
            val spamDomainsReplay = isReplay(spamDomains.generatedAt, lastRead[SPAM_DOMAINS_FEED])
            val spamDomainsApplied = !spamDomainsReplay && shouldApplyFeed(domains, spamDomains.explicitlyCleared)
            if (spamDomains.resolved && spamDomainsApplied) {
                dependencies.smsContentAnalyzer.updateSpamDomains(domains)
            }

            val update =
                healthUpdate(
                    listOf(
                        hotList.observe(HOT_LIST_FEED, hotListApplied, hotNumbers.isEmpty(), hotListReplay),
                        hotRanges.observe(HOT_RANGES_FEED, hotRangesApplied, ranges.isEmpty(), hotRangesReplay),
                        spamDomains.observe(SPAM_DOMAINS_FEED, spamDomainsApplied, domains.isEmpty(), spamDomainsReplay),
                    ),
                )
            val unavailableFeeds = update.unavailableFeeds
            repo.recordHotDataHealth(
                lastGoodTimestamp = System.currentTimeMillis().takeIf { unavailableFeeds.isEmpty() },
                update = update,
            )
            val loads = listOf(hotList, hotRanges, spamDomains)
            when {
                loads.any { HttpClient.isCertificateTrustFailure(it.failure) } -> repo.recordFeedTrust(failed = true)

                // Resolved with no failure means it came from the network, not the bundled snapshot.
                // A mirror serving while GitHub's pins fail still leaves them needing an update.
                loads.any { it.resolved && it.failure == null } -> repo.recordFeedTrust(failed = source.gitHubTrustFailing)
            }
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
            return FeedLoadResult(
                snapshot.data,
                resolved = true,
                explicitlyCleared = snapshot.explicitlyCleared,
                generatedAt = snapshot.generatedAt,
                inputDigest = snapshot.inputDigest,
            )
        }
        if (!shouldUseBundledFallback(false, hasExistingData)) {
            return FeedLoadResult(emptyList(), resolved = false, failure = remote.exceptionOrNull())
        }
        return loadBundledHotList(context, source).copy(failure = remote.exceptionOrNull())
    }

    private suspend fun loadHotRanges(
        context: Context,
        source: HotFeedDataSource,
        hasExistingData: Boolean,
    ): FeedLoadResult<List<String>> {
        val remote = source.fetchHotRangesSnapshot()
        if (remote.isSuccess) {
            val snapshot = remote.getOrThrow()
            return FeedLoadResult(
                snapshot.data,
                resolved = true,
                explicitlyCleared = snapshot.explicitlyCleared,
                generatedAt = snapshot.generatedAt,
                inputDigest = snapshot.inputDigest,
            )
        }
        if (!shouldUseBundledFallback(false, hasExistingData)) {
            return FeedLoadResult(emptyList(), resolved = false, failure = remote.exceptionOrNull())
        }
        return loadBundledHotRanges(context, source).copy(failure = remote.exceptionOrNull())
    }

    private suspend fun loadSpamDomains(
        context: Context,
        source: HotFeedDataSource,
        hasExistingData: Boolean,
    ): FeedLoadResult<List<String>> {
        val remote = source.fetchSpamDomainsSnapshot()
        if (remote.isSuccess) {
            val snapshot = remote.getOrThrow()
            return FeedLoadResult(
                snapshot.data,
                resolved = true,
                explicitlyCleared = snapshot.explicitlyCleared,
                generatedAt = snapshot.generatedAt,
                inputDigest = snapshot.inputDigest,
            )
        }
        if (!shouldUseBundledFallback(false, hasExistingData)) {
            return FeedLoadResult(emptyList(), resolved = false, failure = remote.exceptionOrNull())
        }
        return loadBundledSpamDomains(context, source).copy(failure = remote.exceptionOrNull())
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

    /**
     * When a list's numbers started trending: its own `generated` stamp,
     * never later than [now], or [now] when it has none. The same list read
     * again every 30 minutes keeps its age, so a stalled publisher's numbers
     * stop counting as trending once the hot rows' lifetime has passed.
     */
    internal fun trendingSince(
        generatedAt: String?,
        now: Long,
    ): Long = HotFeedFreshness.publishedAtMillis(generatedAt).takeIf { it > 0L }?.coerceAtMost(now) ?: now

    /**
     * [stamp] as it will be stored: device time when it's later than that, and
     * null when it doesn't parse. A future stamp kept as is would refuse every
     * genuine feed as a replay until that time came, and one that can't be
     * compared would switch the replay check off.
     */
    internal fun cappedStamp(
        stamp: String,
        now: Long,
    ): String? {
        val published = HotFeedFreshness.publishedAtMillis(stamp)
        if (published <= 0L) return null
        return if (published > now) Instant.ofEpochMilli(now).toString() else stamp
    }

    /**
     * A signed feed older than the one last read is a replay: a genuine old
     * copy served by someone who can serve files (a mirror, or anyone once
     * pinning fails), such as a past `cleared: true` that would wipe the
     * device's rows. A signature proves who made a file, not that it's the
     * latest. Both stamps have to parse; without one the feed is judged as
     * before.
     */
    internal fun isReplay(
        generatedAt: String?,
        lastRead: String?,
    ): Boolean {
        val incoming = HotFeedFreshness.publishedAtMillis(generatedAt)
        val previous = HotFeedFreshness.publishedAtMillis(lastRead)
        return incoming > 0L && previous > 0L && incoming < previous
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

    internal const val HOT_LIST_FEED = "hot_list"
    internal const val HOT_RANGES_FEED = "hot_ranges"
    internal const val SPAM_DOMAINS_FEED = "spam_domains"
    private const val HOT_LIST_EVIDENCE_TTL_MS = SpamRepository.HOT_ROW_TTL_MS
}
