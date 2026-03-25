package com.sysadmindoc.callshield.data.remote

import com.sysadmindoc.callshield.data.model.HotNumber
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
        const val HOT_LIST_PATH = "data/hot_numbers.json"
        const val HOT_RANGES_PATH = "data/hot_ranges.json"
        const val SPAM_DOMAINS_PATH = "data/spam_domains.json"

        fun buildRawUrl(owner: String, repo: String, branch: String = "master"): String =
            "https://raw.githubusercontent.com/$owner/$repo/$branch/$DATA_PATH"

        fun buildHotListUrl(owner: String, repo: String, branch: String = "master"): String =
            "https://raw.githubusercontent.com/$owner/$repo/$branch/$HOT_LIST_PATH"

        fun buildHotRangesUrl(owner: String, repo: String, branch: String = "master"): String =
            "https://raw.githubusercontent.com/$owner/$repo/$branch/$HOT_RANGES_PATH"

        fun buildSpamDomainsUrl(owner: String, repo: String, branch: String = "master"): String =
            "https://raw.githubusercontent.com/$owner/$repo/$branch/$SPAM_DOMAINS_PATH"
    }

    suspend fun fetchSpamDatabase(
        owner: String = DEFAULT_REPO_OWNER,
        repo: String = DEFAULT_REPO_NAME
    ): Result<SpamDatabase> = withContext(Dispatchers.IO) {
        try {
            val url = buildRawUrl(owner, repo)
            val request = Request.Builder()
                .url(url)
                .header("Cache-Control", "no-store, max-age=0")
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

    /**
     * Fetch hot_numbers.json — the list of numbers trending in the last 24h.
     * Returns a list of number strings (E.164 format) to block immediately.
     * Lightweight: typically <50KB vs the multi-MB main database.
     */
    suspend fun fetchHotList(
        owner: String = DEFAULT_REPO_OWNER,
        repo: String = DEFAULT_REPO_NAME
    ): Result<List<HotNumber>> = withContext(Dispatchers.IO) {
        try {
            val url = buildHotListUrl(owner, repo)
            val request = Request.Builder()
                .url(url)
                .header("Cache-Control", "no-store, max-age=0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }

            val body = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty response"))

            // Parse each entry individually with targeted regex
            val numberRegex = """"number"\s*:\s*"([^"]+)"""".toRegex()
            val typeRegex = """"type"\s*:\s*"([^"]+)"""".toRegex()
            val descRegex = """"description"\s*:\s*"([^"]*?)"""".toRegex()

            // Split by opening brace to get each object, then parse fields
            val hotNumbers = body.split("""{""").drop(1).mapNotNull { block ->
                val number = numberRegex.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
                val type = typeRegex.find(block)?.groupValues?.get(1) ?: "robocall"
                val desc = descRegex.find(block)?.groupValues?.get(1) ?: "Trending community report"
                HotNumber(number = number, type = type, description = desc)
            }

            Result.success(hotNumbers)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch hot_ranges.json — NPA-NXX prefixes currently running active campaigns.
     * Returns list of 6-digit NPA-NXX strings (e.g. "415523").
     * Non-fatal: returns empty list on any failure so it never blocks a sync.
     */
    suspend fun fetchHotRanges(
        owner: String = DEFAULT_REPO_OWNER,
        repo: String = DEFAULT_REPO_NAME
    ): List<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(buildHotRangesUrl(owner, repo))
                .header("Cache-Control", "no-store, max-age=0")
                .build()
            val body = client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                resp.body?.string() ?: return@withContext emptyList()
            }
            Regex(""""npanxx"\s*:\s*"(\d{6})"""")
                .findAll(body).map { it.groupValues[1] }.toList()
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Fetch spam_domains.json — community-reported phishing/spam domains.
     * Returns list of root domain strings (e.g. "phish-site.xyz").
     * Non-fatal: returns empty list on any failure.
     */
    suspend fun fetchSpamDomains(
        owner: String = DEFAULT_REPO_OWNER,
        repo: String = DEFAULT_REPO_NAME
    ): List<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(buildSpamDomainsUrl(owner, repo))
                .header("Cache-Control", "no-store, max-age=0")
                .build()
            val body = client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                resp.body?.string() ?: return@withContext emptyList()
            }
            // Parse the "domains": ["a","b",...] array
            val arrayContent = Regex(""""domains"\s*:\s*\[([^\]]*)]""")
                .find(body)?.groupValues?.get(1) ?: return@withContext emptyList()
            Regex(""""([^"]+)"""").findAll(arrayContent)
                .map { it.groupValues[1] }.filter { it.isNotBlank() }.toList()
        } catch (_: Exception) { emptyList() }
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
