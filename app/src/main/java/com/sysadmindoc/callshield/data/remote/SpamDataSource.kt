package com.sysadmindoc.callshield.data.remote

import com.sysadmindoc.callshield.data.model.SpamDatabase
import com.sysadmindoc.callshield.data.model.SpamDatabaseShard
import com.sysadmindoc.callshield.data.model.SpamShardManifest

interface SpamDataSource {
    suspend fun fetchSpamDatabase(
        owner: String = GitHubDataSource.DEFAULT_REPO_OWNER,
        repo: String = GitHubDataSource.DEFAULT_REPO_NAME,
    ): Result<SpamDatabase>

    suspend fun checkForUpdate(
        owner: String = GitHubDataSource.DEFAULT_REPO_OWNER,
        repo: String = GitHubDataSource.DEFAULT_REPO_NAME,
    ): Result<String>

    fun parseSpamDatabaseJson(body: String): Result<SpamDatabase>

    /** Optional content-addressed feed API; legacy data sources can use the monolith fallback. */
    suspend fun fetchSpamShardManifest(
        owner: String = GitHubDataSource.DEFAULT_REPO_OWNER,
        repo: String = GitHubDataSource.DEFAULT_REPO_NAME,
    ): Result<SpamShardManifest> = Result.failure(UnsupportedOperationException("Content-addressed spam shards are unavailable"))

    suspend fun fetchSpamShardJson(
        path: String,
        owner: String = GitHubDataSource.DEFAULT_REPO_OWNER,
        repo: String = GitHubDataSource.DEFAULT_REPO_NAME,
    ): Result<String> = Result.failure(UnsupportedOperationException("Content-addressed spam shards are unavailable"))

    fun parseSpamShardManifestJson(body: String): Result<SpamShardManifest> = Result.failure(UnsupportedOperationException("Content-addressed spam shards are unavailable"))

    fun parseSpamShardJson(body: String): Result<SpamDatabaseShard> = Result.failure(UnsupportedOperationException("Content-addressed spam shards are unavailable"))
}
