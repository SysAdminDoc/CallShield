package com.sysadmindoc.callshield.data.remote

import android.content.Context
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
        const val DEFAULT_REPO_OWNER = "SysAdminDoc"
        const val DEFAULT_REPO_NAME = "CallShield"

        const val DATA_PATH = "data/spam_numbers.json"
        const val HOT_LIST_PATH = "data/hot_numbers.json"
        const val HOT_RANGES_PATH = "data/hot_ranges.json"
        const val SPAM_DOMAINS_PATH = "data/spam_domains.json"
        const val MODEL_WEIGHTS_PATH = "data/spam_model_weights.json"

        const val BUNDLED_DATABASE_ASSET = "spam_numbers.json"
        const val BUNDLED_HOT_LIST_ASSET = "hot_numbers.json"
        const val BUNDLED_HOT_RANGES_ASSET = "hot_ranges.json"
        const val BUNDLED_SPAM_DOMAINS_ASSET = "spam_domains.json"
        const val BUNDLED_MODEL_WEIGHTS_ASSET = "spam_model_weights.json"

        private const val GITHUB_API_BASE = "https://api.github.com/repos"
        private const val USER_AGENT = "CallShield/1.0"
        private val FALLBACK_BRANCHES = listOf("main", "master")

        fun buildRawUrl(owner: String, repo: String, branch: String, path: String = DATA_PATH): String =
            "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"

        fun readBundledAsset(context: Context, assetName: String): Result<String> = runCatching {
            context.assets.open(assetName).bufferedReader().use { it.readText() }
        }
    }

    suspend fun fetchSpamDatabase(
        owner: String = DEFAULT_REPO_OWNER,
        repo: String = DEFAULT_REPO_NAME
    ): Result<SpamDatabase> = withContext(Dispatchers.IO) {
        val result = fetchRawText(DATA_PATH, owner, repo)
        if (result.isFailure) {
            return@withContext Result.failure(result.exceptionOrNull()!!)
        }
        parseSpamDatabaseJson(result.getOrThrow())
    }

    suspend fun fetchHotList(
        owner: String = DEFAULT_REPO_OWNER,
        repo: String = DEFAULT_REPO_NAME
    ): Result<List<HotNumber>> = withContext(Dispatchers.IO) {
        val result = fetchRawText(HOT_LIST_PATH, owner, repo)
        if (result.isFailure) {
            return@withContext Result.failure(result.exceptionOrNull()!!)
        }
        Result.success(parseHotListJson(result.getOrThrow()))
    }

    suspend fun fetchHotRanges(
        owner: String = DEFAULT_REPO_OWNER,
        repo: String = DEFAULT_REPO_NAME
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val result = fetchRawText(HOT_RANGES_PATH, owner, repo)
        if (result.isFailure) {
            return@withContext Result.failure(result.exceptionOrNull()!!)
        }
        Result.success(parseHotRangesJson(result.getOrThrow()))
    }

    suspend fun fetchSpamDomains(
        owner: String = DEFAULT_REPO_OWNER,
        repo: String = DEFAULT_REPO_NAME
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val result = fetchRawText(SPAM_DOMAINS_PATH, owner, repo)
        if (result.isFailure) {
            return@withContext Result.failure(result.exceptionOrNull()!!)
        }
        Result.success(parseSpamDomainsJson(result.getOrThrow()))
    }

    suspend fun checkForUpdate(
        owner: String = DEFAULT_REPO_OWNER,
        repo: String = DEFAULT_REPO_NAME
    ): Result<String> = withContext(Dispatchers.IO) {
        var lastError: Exception? = null

        for (branch in resolveCandidateBranches(owner, repo)) {
            try {
                val request = Request.Builder()
                    .url("$GITHUB_API_BASE/$owner/$repo/commits?path=$DATA_PATH&sha=$branch&per_page=1")
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", USER_AGENT)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = Exception("HTTP ${response.code}: ${response.message}")
                        return@use
                    }

                    val body = response.body?.string() ?: "[]"
                    val shaRegex = """"sha"\s*:\s*"([a-f0-9]+)"""".toRegex()
                    val sha = shaRegex.find(body)?.groupValues?.get(1)
                    if (sha != null) {
                        return@withContext Result.success(sha)
                    }
                    lastError = Exception("No commits found")
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        Result.failure(lastError ?: Exception("Unable to resolve repository update status"))
    }

    fun parseSpamDatabaseJson(body: String): Result<SpamDatabase> = runCatching {
        val adapter = moshi.adapter(SpamDatabase::class.java)
        adapter.fromJson(body) ?: throw IllegalStateException("Failed to parse spam database")
    }

    fun parseHotListJson(body: String): List<HotNumber> {
        val numberRegex = """"number"\s*:\s*"([^"]+)"""".toRegex()
        val typeRegex = """"type"\s*:\s*"([^"]+)"""".toRegex()
        val descRegex = """"description"\s*:\s*"([^"]*?)"""".toRegex()

        return body.split("{").drop(1).mapNotNull { block ->
            val number = numberRegex.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val type = typeRegex.find(block)?.groupValues?.get(1) ?: "robocall"
            val desc = descRegex.find(block)?.groupValues?.get(1) ?: "Trending community report"
            HotNumber(number = number, type = type, description = desc)
        }
    }

    fun parseHotRangesJson(body: String): List<String> =
        Regex(""""npanxx"\s*:\s*"(\d{6})"""")
            .findAll(body)
            .map { it.groupValues[1] }
            .toList()

    fun parseSpamDomainsJson(body: String): List<String> {
        val arrayContent = Regex(""""domains"\s*:\s*\[([^\]]*)]""")
            .find(body)
            ?.groupValues
            ?.get(1)
            ?: return emptyList()

        return Regex(""""([^"]+)"""")
            .findAll(arrayContent)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .toList()
    }

    private suspend fun fetchRawText(path: String, owner: String, repo: String): Result<String> {
        var lastError: Exception? = null

        for (branch in resolveCandidateBranches(owner, repo)) {
            try {
                val request = Request.Builder()
                    .url(buildRawUrl(owner, repo, branch, path))
                    .header("Cache-Control", "no-store, max-age=0")
                    .header("User-Agent", USER_AGENT)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = Exception("HTTP ${response.code}: ${response.message}")
                        return@use
                    }

                    val body = response.body?.string()
                    if (body != null) {
                        return Result.success(body)
                    }
                    lastError = Exception("Empty response body")
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        return Result.failure(lastError ?: Exception("Unable to fetch $path"))
    }

    private suspend fun resolveCandidateBranches(owner: String, repo: String): List<String> {
        val defaultBranch = fetchDefaultBranch(owner, repo).getOrNull()
        return listOfNotNull(defaultBranch).plus(FALLBACK_BRANCHES).distinct()
    }

    private suspend fun fetchDefaultBranch(owner: String, repo: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$GITHUB_API_BASE/$owner/$repo")
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", USER_AGENT)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }

                val body = response.body?.string() ?: "{}"
                val match = """"default_branch"\s*:\s*"([^"]+)"""".toRegex().find(body)
                val branch = match?.groupValues?.get(1)
                    ?: return@withContext Result.failure(Exception("Missing default branch"))
                Result.success(branch)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
