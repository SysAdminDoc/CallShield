package com.sysadmindoc.callshield.data.remote

import com.sysadmindoc.callshield.data.model.HotNumber

/**
 * Parsed hot-feed data plus the only signal that permits an empty destructive
 * refresh. Legacy array payloads deliberately leave [explicitlyCleared] false.
 */
data class HotFeedSnapshot<T>(
    val data: T,
    val explicitlyCleared: Boolean = false,
    /**
     * The feed's own ISO-8601 `generated` value, when it declares one. This is
     * what distinguishes a publisher that has stopped producing from a feed the
     * device could not fetch: both leave the local data unchanged, but only the
     * first hands over a readable file whose timestamp has not moved.
     */
    val generatedAt: String? = null,
    /** The report-queue digest the publisher generated this feed from. */
    val inputDigest: String? = null,
)

interface HotFeedDataSource {
    suspend fun fetchHotList(
        owner: String = GitHubDataSource.DEFAULT_REPO_OWNER,
        repo: String = GitHubDataSource.DEFAULT_REPO_NAME,
    ): Result<List<HotNumber>>

    suspend fun fetchHotRanges(
        owner: String = GitHubDataSource.DEFAULT_REPO_OWNER,
        repo: String = GitHubDataSource.DEFAULT_REPO_NAME,
    ): Result<List<String>>

    suspend fun fetchSpamDomains(
        owner: String = GitHubDataSource.DEFAULT_REPO_OWNER,
        repo: String = GitHubDataSource.DEFAULT_REPO_NAME,
    ): Result<List<String>>

    suspend fun fetchHotListSnapshot(
        owner: String = GitHubDataSource.DEFAULT_REPO_OWNER,
        repo: String = GitHubDataSource.DEFAULT_REPO_NAME,
    ): Result<HotFeedSnapshot<List<HotNumber>>> = fetchHotList(owner, repo).map(::HotFeedSnapshot)

    suspend fun fetchHotRangesSnapshot(
        owner: String = GitHubDataSource.DEFAULT_REPO_OWNER,
        repo: String = GitHubDataSource.DEFAULT_REPO_NAME,
    ): Result<HotFeedSnapshot<List<String>>> = fetchHotRanges(owner, repo).map(::HotFeedSnapshot)

    suspend fun fetchSpamDomainsSnapshot(
        owner: String = GitHubDataSource.DEFAULT_REPO_OWNER,
        repo: String = GitHubDataSource.DEFAULT_REPO_NAME,
    ): Result<HotFeedSnapshot<List<String>>> = fetchSpamDomains(owner, repo).map(::HotFeedSnapshot)

    fun parseHotListJson(body: String): List<HotNumber>

    fun parseHotRangesJson(body: String): List<String>

    fun parseSpamDomainsJson(body: String): List<String>

    fun parseHotListSnapshotJson(body: String): HotFeedSnapshot<List<HotNumber>> = HotFeedSnapshot(parseHotListJson(body))

    fun parseHotRangesSnapshotJson(body: String): HotFeedSnapshot<List<String>> = HotFeedSnapshot(parseHotRangesJson(body))

    fun parseSpamDomainsSnapshotJson(body: String): HotFeedSnapshot<List<String>> = HotFeedSnapshot(parseSpamDomainsJson(body))

    /** True while GitHub itself fails certificate verification, even when a mirror is serving the feeds. */
    val gitHubTrustFailing: Boolean get() = false
}
