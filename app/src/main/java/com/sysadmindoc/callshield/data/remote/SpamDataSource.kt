package com.sysadmindoc.callshield.data.remote

import com.sysadmindoc.callshield.data.model.SpamDatabase

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
}
