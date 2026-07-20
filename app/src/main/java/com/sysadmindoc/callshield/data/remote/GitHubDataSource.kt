package com.sysadmindoc.callshield.data.remote

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.sysadmindoc.callshield.data.model.HotNumber
import com.sysadmindoc.callshield.data.model.SpamDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.util.concurrent.TimeUnit

internal enum class GitHubFeedFailureReason {
    OVERSIZE,
    ROW_LIMIT,
    MISSING_SCHEMA_FIELD,
    INVALID_SCHEMA,
}

internal class GitHubFeedValidationException(
    val reason: GitHubFeedFailureReason,
    message: String,
) : IllegalArgumentException(message)

private fun failFeedValidation(
    reason: GitHubFeedFailureReason,
    message: String,
): Nothing = throw GitHubFeedValidationException(reason, message)

private fun requireFeed(
    condition: Boolean,
    reason: GitHubFeedFailureReason,
    message: () -> String,
) {
    if (!condition) {
        failFeedValidation(reason, message())
    }
}

class GitHubDataSource :
    SpamDataSource,
    HotFeedDataSource {
    // Derived client with longer timeouts for large database downloads;
    // shares the connection pool with other callers via HttpClient.shared.
    private val client =
        HttpClient.shared
            .newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    private val moshi =
        Moshi
            .Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    private val hotListEnvelopeAdapter = moshi.adapter(HotListPayload::class.java)
    private val hotListArrayAdapter =
        moshi.adapter<List<HotListEntry>>(
            Types.newParameterizedType(List::class.java, HotListEntry::class.java),
        )
    private val hotRangesEnvelopeAdapter = moshi.adapter(HotRangesPayload::class.java)
    private val hotRangesArrayAdapter =
        moshi.adapter<List<String>>(
            Types.newParameterizedType(List::class.java, String::class.java),
        )
    private val spamDomainsEnvelopeAdapter = moshi.adapter(SpamDomainsPayload::class.java)
    private val spamDomainsArrayAdapter =
        moshi.adapter<List<String>>(
            Types.newParameterizedType(List::class.java, String::class.java),
        )

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

        internal const val MAX_SPAM_DATABASE_BYTES = 16L * 1024L * 1024L
        internal const val MAX_HOT_LIST_BYTES = 1L * 1024L * 1024L
        internal const val MAX_HOT_RANGES_BYTES = 512L * 1024L
        internal const val MAX_SPAM_DOMAINS_BYTES = 2L * 1024L * 1024L
        internal const val MAX_MODEL_WEIGHTS_BYTES = 1L * 1024L * 1024L

        internal const val MAX_SPAM_DATABASE_NUMBERS = 250_000
        internal const val MAX_SPAM_DATABASE_PREFIXES = 100_000
        internal const val MAX_HOT_LIST_ROWS = 5_000
        internal const val MAX_HOT_RANGE_ROWS = 20_000
        internal const val MAX_SPAM_DOMAIN_ROWS = 50_000

        private const val GITHUB_API_BASE = "https://api.github.com/repos"
        private const val USER_AGENT = "CallShield/1.0"
        private const val MAX_GITHUB_API_BYTES = 256L * 1024L
        private const val READ_CHUNK_BYTES = 8192L
        private val FALLBACK_BRANCHES = listOf("main", "master")
        private val RAW_FEED_SPECS =
            mapOf(
                DATA_PATH to RawFeedSpec("spam database", MAX_SPAM_DATABASE_BYTES),
                HOT_LIST_PATH to RawFeedSpec("hot list", MAX_HOT_LIST_BYTES),
                HOT_RANGES_PATH to RawFeedSpec("hot ranges", MAX_HOT_RANGES_BYTES),
                SPAM_DOMAINS_PATH to RawFeedSpec("spam domains", MAX_SPAM_DOMAINS_BYTES),
                MODEL_WEIGHTS_PATH to RawFeedSpec("model weights", MAX_MODEL_WEIGHTS_BYTES),
            )

        fun buildRawUrl(
            owner: String,
            repo: String,
            branch: String,
            path: String = DATA_PATH,
        ): String = "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"

        fun readBundledAsset(
            context: Context,
            assetName: String,
        ): Result<String> =
            runCatching {
                context.assets
                    .open(assetName)
                    .bufferedReader()
                    .use { it.readText() }
            }

        internal fun validateRawFeedBody(
            path: String,
            body: String,
        ): String {
            val spec = rawFeedSpec(path)
            val byteCount = body.toByteArray(Charsets.UTF_8).size.toLong()
            requireFeed(byteCount <= spec.maxBytes, GitHubFeedFailureReason.OVERSIZE) {
                "${spec.label} feed exceeded ${spec.maxBytes} byte cap"
            }
            if (path == MODEL_WEIGHTS_PATH) {
                validateModelWeightsEnvelope(body)
            }
            return body
        }

        internal fun rawFeedMaxBytes(path: String): Long = rawFeedSpec(path).maxBytes

        internal fun rawFeedLabel(path: String): String = rawFeedSpec(path).label

        private fun rawFeedSpec(path: String): RawFeedSpec = RAW_FEED_SPECS[path] ?: RawFeedSpec(path, MAX_GITHUB_API_BYTES)

        private fun validateModelWeightsEnvelope(body: String) {
            val trimmed = body.trimStart()
            requireFeed(trimmed.startsWith("{"), GitHubFeedFailureReason.INVALID_SCHEMA) {
                "model weights feed must be a JSON object"
            }
            requireFeed(""""version""" in body, GitHubFeedFailureReason.MISSING_SCHEMA_FIELD) {
                "model weights feed is missing version"
            }
        }
    }

    override suspend fun fetchSpamDatabase(
        owner: String,
        repo: String,
    ): Result<SpamDatabase> =
        withContext(Dispatchers.IO) {
            val result = fetchRawText(DATA_PATH, owner, repo)
            if (result.isFailure) {
                return@withContext Result.failure(result.exceptionOrNull()!!)
            }
            parseSpamDatabaseJson(result.getOrThrow())
        }

    override suspend fun fetchHotList(
        owner: String,
        repo: String,
    ): Result<List<HotNumber>> =
        withContext(Dispatchers.IO) {
            val result = fetchRawText(HOT_LIST_PATH, owner, repo)
            if (result.isFailure) {
                return@withContext Result.failure(result.exceptionOrNull()!!)
            }
            Result.success(parseHotListJson(result.getOrThrow()))
        }

    override suspend fun fetchHotRanges(
        owner: String,
        repo: String,
    ): Result<List<String>> =
        withContext(Dispatchers.IO) {
            val result = fetchRawText(HOT_RANGES_PATH, owner, repo)
            if (result.isFailure) {
                return@withContext Result.failure(result.exceptionOrNull()!!)
            }
            Result.success(parseHotRangesJson(result.getOrThrow()))
        }

    override suspend fun fetchSpamDomains(
        owner: String,
        repo: String,
    ): Result<List<String>> =
        withContext(Dispatchers.IO) {
            val result = fetchRawText(SPAM_DOMAINS_PATH, owner, repo)
            if (result.isFailure) {
                return@withContext Result.failure(result.exceptionOrNull()!!)
            }
            Result.success(parseSpamDomainsJson(result.getOrThrow()))
        }

    suspend fun fetchModelWeightsJson(
        owner: String = DEFAULT_REPO_OWNER,
        repo: String = DEFAULT_REPO_NAME,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            fetchRawText(MODEL_WEIGHTS_PATH, owner, repo)
        }

    override suspend fun checkForUpdate(
        owner: String,
        repo: String,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            var lastError: Exception? = null

            for (branch in resolveCandidateBranches(owner, repo)) {
                try {
                    val request =
                        Request
                            .Builder()
                            .url("$GITHUB_API_BASE/$owner/$repo/commits?path=$DATA_PATH&sha=$branch&per_page=1")
                            .header("Accept", "application/vnd.github.v3+json")
                            .header("User-Agent", USER_AGENT)
                            .build()

                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            lastError = Exception("HTTP ${response.code}: ${response.message}")
                            return@use
                        }

                        val body =
                            readLimitedBody(response, "GitHub commits API", MAX_GITHUB_API_BYTES)
                                ?.ifBlank { "[]" } ?: "[]"
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

    override fun parseSpamDatabaseJson(body: String): Result<SpamDatabase> =
        runCatching {
            val adapter = moshi.adapter(SpamDatabase::class.java)
            val database = adapter.fromJson(body) ?: throw IllegalStateException("Failed to parse spam database")
            validateSpamDatabase(database)
            database
        }

    override fun parseHotListJson(body: String): List<HotNumber> {
        val trimmedBody = body.trimStart()
        val entries =
            when {
                trimmedBody.startsWith("{") -> {
                    hotListEnvelopeAdapter.fromJson(body)?.numbers
                        ?: throw IllegalStateException("Failed to parse hot list payload")
                }

                trimmedBody.startsWith("[") -> {
                    hotListArrayAdapter.fromJson(body)
                        ?: throw IllegalStateException("Failed to parse hot list array")
                }

                else -> {
                    throw IllegalStateException("Unsupported hot list JSON format")
                }
            }
        requireFeed(entries.size <= MAX_HOT_LIST_ROWS, GitHubFeedFailureReason.ROW_LIMIT) {
            "hot list row count ${entries.size} exceeds cap $MAX_HOT_LIST_ROWS"
        }

        return entries.mapNotNull { entry ->
            val number = entry.number.trim()
            if (number.isBlank()) {
                null
            } else {
                HotNumber(
                    number = number,
                    type = entry.type.trim().ifBlank { "robocall" },
                    description = entry.description.trim().ifBlank { "Trending community report" },
                )
            }
        }
    }

    override fun parseHotRangesJson(body: String): List<String> {
        val trimmedBody = body.trimStart()
        val ranges =
            when {
                trimmedBody.startsWith("{") -> {
                    hotRangesEnvelopeAdapter
                        .fromJson(body)
                        ?.ranges
                        .orEmpty()
                        .map { it.npanxx }
                }

                trimmedBody.startsWith("[") -> {
                    hotRangesArrayAdapter.fromJson(body)
                        ?: throw IllegalStateException("Failed to parse hot ranges array")
                }

                else -> {
                    throw IllegalStateException("Unsupported hot ranges JSON format")
                }
            }
        requireFeed(ranges.size <= MAX_HOT_RANGE_ROWS, GitHubFeedFailureReason.ROW_LIMIT) {
            "hot ranges row count ${ranges.size} exceeds cap $MAX_HOT_RANGE_ROWS"
        }
        return ranges
    }

    override fun parseSpamDomainsJson(body: String): List<String> {
        val trimmedBody = body.trimStart()
        val domains =
            when {
                trimmedBody.startsWith("{") -> {
                    spamDomainsEnvelopeAdapter
                        .fromJson(body)
                        ?.domains
                        .orEmpty()
                }

                trimmedBody.startsWith("[") -> {
                    spamDomainsArrayAdapter.fromJson(body)
                        ?: throw IllegalStateException("Failed to parse spam domains array")
                }

                else -> {
                    throw IllegalStateException("Unsupported spam domains JSON format")
                }
            }
        requireFeed(domains.size <= MAX_SPAM_DOMAIN_ROWS, GitHubFeedFailureReason.ROW_LIMIT) {
            "spam domains row count ${domains.size} exceeds cap $MAX_SPAM_DOMAIN_ROWS"
        }
        return domains
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private suspend fun fetchRawText(
        path: String,
        owner: String,
        repo: String,
    ): Result<String> {
        var lastError: Exception? = null
        val label = rawFeedLabel(path)
        val maxBytes = rawFeedMaxBytes(path)

        for (branch in resolveCandidateBranches(owner, repo)) {
            try {
                val request =
                    Request
                        .Builder()
                        .url(buildRawUrl(owner, repo, branch, path))
                        .header("Cache-Control", "no-store, max-age=0")
                        .header("User-Agent", USER_AGENT)
                        .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = Exception("HTTP ${response.code}: ${response.message}")
                        return@use
                    }

                    val body = readLimitedBody(response, label, maxBytes)
                    if (body != null) {
                        return Result.success(validateRawFeedBody(path, body))
                    }
                    lastError = Exception("Empty response body")
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        return Result.failure(lastError ?: Exception("Unable to fetch $path"))
    }

    private suspend fun resolveCandidateBranches(
        owner: String,
        repo: String,
    ): List<String> {
        val defaultBranch = fetchDefaultBranch(owner, repo).getOrNull()
        return listOfNotNull(defaultBranch).plus(FALLBACK_BRANCHES).distinct()
    }

    private suspend fun fetchDefaultBranch(
        owner: String,
        repo: String,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val request =
                    Request
                        .Builder()
                        .url("$GITHUB_API_BASE/$owner/$repo")
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("User-Agent", USER_AGENT)
                        .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                    }

                    val body =
                        readLimitedBody(response, "GitHub repository API", MAX_GITHUB_API_BYTES)
                            ?.ifBlank { "{}" } ?: "{}"
                    val match = """"default_branch"\s*:\s*"([^"]+)"""".toRegex().find(body)
                    val branch =
                        match?.groupValues?.get(1)
                            ?: return@withContext Result.failure(Exception("Missing default branch"))
                    Result.success(branch)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun validateSpamDatabase(database: SpamDatabase) {
        requireFeed(database.version > 0, GitHubFeedFailureReason.INVALID_SCHEMA) {
            "spam database version must be positive"
        }
        requireFeed(database.updated.isNotBlank(), GitHubFeedFailureReason.MISSING_SCHEMA_FIELD) {
            "spam database updated timestamp is missing"
        }
        requireFeed(
            database.numbers.size <= MAX_SPAM_DATABASE_NUMBERS,
            GitHubFeedFailureReason.ROW_LIMIT,
        ) {
            "spam database number row count ${database.numbers.size} exceeds cap $MAX_SPAM_DATABASE_NUMBERS"
        }
        requireFeed(
            database.prefixes.size <= MAX_SPAM_DATABASE_PREFIXES,
            GitHubFeedFailureReason.ROW_LIMIT,
        ) {
            "spam database prefix row count ${database.prefixes.size} exceeds cap $MAX_SPAM_DATABASE_PREFIXES"
        }
    }

    private fun readLimitedBody(
        response: Response,
        label: String,
        maxBytes: Long,
    ): String? {
        val body = response.body ?: return null
        val contentLength = body.contentLength()
        requireFeed(
            contentLength <= maxBytes || contentLength == -1L,
            GitHubFeedFailureReason.OVERSIZE,
        ) {
            "$label response declared $contentLength bytes, over $maxBytes byte cap"
        }

        val source = body.source()
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val read = source.read(buffer, READ_CHUNK_BYTES)
            if (read == -1L) break
            total += read
            requireFeed(total <= maxBytes, GitHubFeedFailureReason.OVERSIZE) {
                "$label response exceeded $maxBytes byte cap"
            }
        }
        return buffer.readUtf8()
    }

    private data class RawFeedSpec(
        val label: String,
        val maxBytes: Long,
    )

    private data class HotListPayload(
        val numbers: List<HotListEntry> = emptyList(),
    )

    private data class HotListEntry(
        val number: String = "",
        val type: String = "robocall",
        val description: String = "Trending community report",
    )

    private data class HotRangesPayload(
        val ranges: List<HotRangeEntry> = emptyList(),
    )

    private data class HotRangeEntry(
        val npanxx: String = "",
    )

    private data class SpamDomainsPayload(
        val domains: List<String> = emptyList(),
    )
}
