package com.sysadmindoc.callshield.data.remote

import com.sysadmindoc.callshield.data.model.HotNumber

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

    fun parseHotListJson(body: String): List<HotNumber>

    fun parseHotRangesJson(body: String): List<String>

    fun parseSpamDomainsJson(body: String): List<String>
}
