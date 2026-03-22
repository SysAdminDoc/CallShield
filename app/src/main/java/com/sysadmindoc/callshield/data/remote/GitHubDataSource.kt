package com.sysadmindoc.callshield.data.remote

import com.sysadmindoc.callshield.data.model.SpamDatabase
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class GitHubDataSource {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    companion object {
        // Users fork the repo and update this URL, or use the default
        const val DEFAULT_REPO_OWNER = "SysAdminDoc"
        const val DEFAULT_REPO_NAME = "CallShield"
        const val DATA_PATH = "data/spam_numbers.json"

        fun buildRawUrl(owner: String, repo: String, branch: String = "main"): String {
            return "https://raw.githubusercontent.com/$owner/$repo/$branch/$DATA_PATH"
        }
    }

    suspend fun fetchSpamDatabase(
        owner: String = DEFAULT_REPO_OWNER,
        repo: String = DEFAULT_REPO_NAME
    ): Result<SpamDatabase> = withContext(Dispatchers.IO) {
        try {
            val url = buildRawUrl(owner, repo)
            val request = Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }

            val body = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty response body"))

            val adapter = moshi.adapter(SpamDatabase::class.java)
            val database = adapter.fromJson(body)
                ?: return@withContext Result.failure(Exception("Failed to parse spam database"))

            Result.success(database)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun checkForUpdate(
        owner: String = DEFAULT_REPO_OWNER,
        repo: String = DEFAULT_REPO_NAME
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Use GitHub API (no auth needed for public repos, 60 req/hr)
            val url = "https://api.github.com/repos/$owner/$repo/commits?path=$DATA_PATH&per_page=1"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }

            val body = response.body?.string() ?: "[]"
            // Extract SHA from first commit - simple string parse to avoid extra deps
            val shaRegex = """"sha"\s*:\s*"([a-f0-9]+)"""".toRegex()
            val match = shaRegex.find(body)
            val sha = match?.groupValues?.get(1)
                ?: return@withContext Result.failure(Exception("No commits found"))

            Result.success(sha)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
