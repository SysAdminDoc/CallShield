package com.sysadmindoc.callshield.data.remote

import android.content.Context
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.sysadmindoc.callshield.data.AppUpdateRelease
import com.sysadmindoc.callshield.data.model.HotNumber
import com.sysadmindoc.callshield.data.model.SpamDatabase
import com.sysadmindoc.callshield.data.model.SpamDatabaseShard
import com.sysadmindoc.callshield.data.model.SpamShardManifest
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

private val SHARD_ID_REGEX = Regex("[0-9a-f]{2}")
private val SHA256_REGEX = Regex("[0-9a-f]{64}")
private val SHARD_PATH_REGEX = Regex("data/spam_number_shards/[0-9a-f]{2}\\.json")

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
    private val latestReleaseAdapter = moshi.adapter(GitHubReleasePayload::class.java)

    // Cache of resolved default branch per "owner/repo" → (branch, resolvedAtMs).
    private val defaultBranchLock = Any()
    private val defaultBranchCache = mutableMapOf<String, Pair<String, Long>>()

    companion object {
        const val DEFAULT_REPO_OWNER = "SysAdminDoc"
        const val DEFAULT_REPO_NAME = "CallShield"

        const val DATA_PATH = "data/spam_numbers.json"
        const val SHARD_MANIFEST_PATH = "data/spam_numbers.manifest.json"
        const val SHARD_DIRECTORY_PATH = "data/spam_number_shards/"
        const val HOT_LIST_PATH = "data/hot_numbers.json"
        const val HOT_RANGES_PATH = "data/hot_ranges.json"
        const val SPAM_DOMAINS_PATH = "data/spam_domains.json"
        const val MODEL_WEIGHTS_PATH = "data/spam_model_weights.json"

        const val BUNDLED_DATABASE_ASSET = "spam_numbers.json"
        const val BUNDLED_SHARD_MANIFEST_ASSET = "spam_numbers.manifest.json"
        const val BUNDLED_SHARD_DIRECTORY_ASSET = "spam_number_shards"
        const val BUNDLED_HOT_LIST_ASSET = "hot_numbers.json"
        const val BUNDLED_HOT_RANGES_ASSET = "hot_ranges.json"
        const val BUNDLED_SPAM_DOMAINS_ASSET = "spam_domains.json"
        const val BUNDLED_MODEL_WEIGHTS_ASSET = "spam_model_weights.json"

        internal const val MAX_SPAM_DATABASE_BYTES = 16L * 1024L * 1024L
        internal const val MAX_SPAM_SHARD_BYTES = 1L * 1024L * 1024L
        internal const val MAX_HOT_LIST_BYTES = 1L * 1024L * 1024L
        internal const val MAX_HOT_RANGES_BYTES = 512L * 1024L
        internal const val MAX_SPAM_DOMAINS_BYTES = 2L * 1024L * 1024L
        internal const val MAX_MODEL_WEIGHTS_BYTES = 1L * 1024L * 1024L

        internal const val MAX_SPAM_DATABASE_NUMBERS = 250_000
        internal const val MAX_SPAM_DATABASE_PREFIXES = 100_000
        internal const val MAX_SPAM_SHARDS = 256
        internal const val MAX_HOT_LIST_ROWS = 5_000
        internal const val MAX_HOT_RANGE_ROWS = 20_000
        internal const val MAX_SPAM_DOMAIN_ROWS = 50_000

        private const val GITHUB_API_BASE = "https://api.github.com/repos"
        private const val USER_AGENT = "CallShield/1.0"
        private const val MAX_GITHUB_API_BYTES = 256L * 1024L
        private const val READ_CHUNK_BYTES = 8192L

        /** How long a resolved default branch stays cached before re-querying. */
        private const val DEFAULT_BRANCH_TTL_MS = 6L * 60L * 60L * 1000L // 6 hours
        private val FALLBACK_BRANCHES = listOf("main", "master")
        private val RAW_FEED_SPECS =
            mapOf(
                DATA_PATH to RawFeedSpec("spam database", MAX_SPAM_DATABASE_BYTES),
                SHARD_MANIFEST_PATH to RawFeedSpec("spam database shard manifest", MAX_GITHUB_API_BYTES),
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

        fun readBundledAssetBytes(
            context: Context,
            assetName: String,
        ): Result<ByteArray> =
            runCatching {
                context.assets.open(assetName).use { it.readBytes() }
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

        private fun rawFeedSpec(path: String): RawFeedSpec =
            RAW_FEED_SPECS[path]
                ?: if (path.startsWith(SHARD_DIRECTORY_PATH) && path.endsWith(".json")) {
                    RawFeedSpec("spam database shard", MAX_SPAM_SHARD_BYTES)
                } else {
                    RawFeedSpec(path, MAX_GITHUB_API_BYTES)
                }

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

    override suspend fun fetchSpamShardManifest(
        owner: String,
        repo: String,
    ): Result<SpamShardManifest> =
        withContext(Dispatchers.IO) {
            val result = fetchRawText(SHARD_MANIFEST_PATH, owner, repo)
            if (result.isFailure) {
                return@withContext Result.failure(result.exceptionOrNull()!!)
            }
            parseSpamShardManifestJson(result.getOrThrow())
        }

    override suspend fun fetchSpamShardJson(
        path: String,
        owner: String,
        repo: String,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            if (!path.startsWith(SHARD_DIRECTORY_PATH) || !SHARD_PATH_REGEX.matches(path)) {
                return@withContext Result.failure(IllegalArgumentException("Invalid spam shard path"))
            }
            fetchRawText(path, owner, repo)
        }

    override suspend fun fetchHotList(
        owner: String,
        repo: String,
    ): Result<List<HotNumber>> = fetchHotListSnapshot(owner, repo).map { it.data }

    override suspend fun fetchHotListSnapshot(
        owner: String,
        repo: String,
    ): Result<HotFeedSnapshot<List<HotNumber>>> =
        withContext(Dispatchers.IO) {
            val result = fetchRawText(HOT_LIST_PATH, owner, repo)
            if (result.isFailure) {
                return@withContext Result.failure(result.exceptionOrNull()!!)
            }
            Result.success(parseHotListSnapshotJson(result.getOrThrow()))
        }

    override suspend fun fetchHotRanges(
        owner: String,
        repo: String,
    ): Result<List<String>> = fetchHotRangesSnapshot(owner, repo).map { it.data }

    override suspend fun fetchHotRangesSnapshot(
        owner: String,
        repo: String,
    ): Result<HotFeedSnapshot<List<String>>> =
        withContext(Dispatchers.IO) {
            val result = fetchRawText(HOT_RANGES_PATH, owner, repo)
            if (result.isFailure) {
                return@withContext Result.failure(result.exceptionOrNull()!!)
            }
            Result.success(parseHotRangesSnapshotJson(result.getOrThrow()))
        }

    override suspend fun fetchSpamDomains(
        owner: String,
        repo: String,
    ): Result<List<String>> = fetchSpamDomainsSnapshot(owner, repo).map { it.data }

    override suspend fun fetchSpamDomainsSnapshot(
        owner: String,
        repo: String,
    ): Result<HotFeedSnapshot<List<String>>> =
        withContext(Dispatchers.IO) {
            val result = fetchRawText(SPAM_DOMAINS_PATH, owner, repo)
            if (result.isFailure) {
                return@withContext Result.failure(result.exceptionOrNull()!!)
            }
            Result.success(parseSpamDomainsSnapshotJson(result.getOrThrow()))
        }

    suspend fun fetchModelWeightsJson(
        owner: String = DEFAULT_REPO_OWNER,
        repo: String = DEFAULT_REPO_NAME,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            fetchRawText(MODEL_WEIGHTS_PATH, owner, repo)
        }

    suspend fun fetchLatestRelease(
        owner: String = DEFAULT_REPO_OWNER,
        repo: String = DEFAULT_REPO_NAME,
    ): Result<AppUpdateRelease> =
        withContext(Dispatchers.IO) {
            try {
                val request =
                    Request
                        .Builder()
                        .url("$GITHUB_API_BASE/$owner/$repo/releases/latest")
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("User-Agent", USER_AGENT)
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                    }
                    val body = readLimitedBody(response, "GitHub releases API", MAX_GITHUB_API_BYTES) ?: "{}"
                    parseLatestReleaseJson(body)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    internal fun parseLatestReleaseJson(body: String): Result<AppUpdateRelease> =
        runCatching {
            val payload = latestReleaseAdapter.fromJson(body) ?: error("Failed to parse GitHub release")
            val tagName = payload.tagName.trim().ifBlank { error("GitHub release has no tag") }
            val htmlUrl =
                payload.htmlUrl
                    .trim()
                    .takeIf { it.startsWith("https://github.com/") }
                    ?: error("GitHub release has no safe release URL")
            val checksumUrl =
                payload.assets
                    .firstOrNull { asset ->
                        asset.name.contains("sha256", ignoreCase = true) || asset.name.endsWith(".sha", ignoreCase = true)
                    }?.browserDownloadUrl
                    ?.trim()
                    ?.takeIf { it.startsWith("https://github.com/") }
            AppUpdateRelease(tagName = tagName, htmlUrl = htmlUrl, checksumUrl = checksumUrl)
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
            val database = adapter.fromJson(body) ?: error("Failed to parse spam database")
            validateSpamDatabase(database)
            database
        }

    override fun parseSpamShardManifestJson(body: String): Result<SpamShardManifest> =
        runCatching {
            val adapter = moshi.adapter(SpamShardManifest::class.java)
            val manifest = adapter.fromJson(body) ?: error("Failed to parse spam shard manifest")
            validateSpamShardManifest(manifest)
            manifest
        }

    override fun parseSpamShardJson(body: String): Result<SpamDatabaseShard> =
        runCatching {
            val adapter = moshi.adapter(SpamDatabaseShard::class.java)
            val shard = adapter.fromJson(body) ?: error("Failed to parse spam database shard")
            validateSpamDatabaseShard(shard)
            shard
        }

    override fun parseHotListJson(body: String): List<HotNumber> = parseHotListSnapshotJson(body).data

    override fun parseHotListSnapshotJson(body: String): HotFeedSnapshot<List<HotNumber>> {
        val trimmedBody = body.trimStart()
        val (entries, explicitlyCleared) =
            when {
                trimmedBody.startsWith("{") -> {
                    val payload = hotListEnvelopeAdapter.fromJson(body) ?: error("Failed to parse hot list payload")
                    payload.numbers to payload.cleared
                }

                trimmedBody.startsWith("[") -> {
                    (hotListArrayAdapter.fromJson(body) ?: error("Failed to parse hot list array")) to false
                }

                else -> {
                    error("Unsupported hot list JSON format")
                }
            }
        requireFeed(entries.size <= MAX_HOT_LIST_ROWS, GitHubFeedFailureReason.ROW_LIMIT) {
            "hot list row count ${entries.size} exceeds cap $MAX_HOT_LIST_ROWS"
        }

        return HotFeedSnapshot(
            data =
                entries.mapNotNull { entry ->
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
                },
            explicitlyCleared = explicitlyCleared,
        )
    }

    override fun parseHotRangesJson(body: String): List<String> = parseHotRangesSnapshotJson(body).data

    override fun parseHotRangesSnapshotJson(body: String): HotFeedSnapshot<List<String>> {
        val trimmedBody = body.trimStart()
        val (ranges, explicitlyCleared) =
            when {
                trimmedBody.startsWith("{") -> {
                    val payload = hotRangesEnvelopeAdapter.fromJson(body) ?: error("Failed to parse hot ranges payload")
                    payload.ranges.map { it.npanxx } to payload.cleared
                }

                trimmedBody.startsWith("[") -> {
                    (hotRangesArrayAdapter.fromJson(body) ?: error("Failed to parse hot ranges array")) to false
                }

                else -> {
                    error("Unsupported hot ranges JSON format")
                }
            }
        requireFeed(ranges.size <= MAX_HOT_RANGE_ROWS, GitHubFeedFailureReason.ROW_LIMIT) {
            "hot ranges row count ${ranges.size} exceeds cap $MAX_HOT_RANGE_ROWS"
        }
        return HotFeedSnapshot(ranges, explicitlyCleared)
    }

    override fun parseSpamDomainsJson(body: String): List<String> = parseSpamDomainsSnapshotJson(body).data

    override fun parseSpamDomainsSnapshotJson(body: String): HotFeedSnapshot<List<String>> {
        val trimmedBody = body.trimStart()
        val (domains, explicitlyCleared) =
            when {
                trimmedBody.startsWith("{") -> {
                    val payload = spamDomainsEnvelopeAdapter.fromJson(body) ?: error("Failed to parse spam domains payload")
                    payload.domains to payload.cleared
                }

                trimmedBody.startsWith("[") -> {
                    (spamDomainsArrayAdapter.fromJson(body) ?: error("Failed to parse spam domains array")) to false
                }

                else -> {
                    error("Unsupported spam domains JSON format")
                }
            }
        requireFeed(domains.size <= MAX_SPAM_DOMAIN_ROWS, GitHubFeedFailureReason.ROW_LIMIT) {
            "spam domains row count ${domains.size} exceeds cap $MAX_SPAM_DOMAIN_ROWS"
        }
        return HotFeedSnapshot(
            data = domains.map { it.trim() }.filter { it.isNotBlank() },
            explicitlyCleared = explicitlyCleared,
        )
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
        val defaultBranch = cachedDefaultBranch(owner, repo)
        return listOfNotNull(defaultBranch).plus(FALLBACK_BRANCHES).distinct()
    }

    /**
     * Resolve the repo's default branch, caching the result per (owner/repo)
     * for [DEFAULT_BRANCH_TTL_MS]. Every raw-feed fetch used to issue a fresh
     * unauthenticated `GET /repos/{owner}/{repo}`; a single hot-list refresh
     * (3 feeds) plus model-weight sync could burn 4+ of GitHub's 60 req/hr
     * unauthenticated budget, after which all feeds silently fall back to
     * bundled data. Caching collapses that to one call per TTL window while
     * still picking up a branch rename within a few hours.
     */
    private suspend fun cachedDefaultBranch(
        owner: String,
        repo: String,
    ): String? {
        val key = "$owner/$repo"
        val now = System.currentTimeMillis()
        synchronized(defaultBranchLock) {
            val cached = defaultBranchCache[key]
            if (cached != null && now - cached.second < DEFAULT_BRANCH_TTL_MS) {
                return cached.first
            }
        }
        val resolved = fetchDefaultBranch(owner, repo).getOrNull() ?: return null
        synchronized(defaultBranchLock) {
            defaultBranchCache[key] = resolved to now
        }
        return resolved
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

    private fun validateSpamShardManifest(manifest: SpamShardManifest) {
        requireFeed(manifest.formatVersion == 1, GitHubFeedFailureReason.INVALID_SCHEMA) {
            "unsupported spam shard manifest format ${manifest.formatVersion}"
        }
        requireFeed(manifest.version > 0, GitHubFeedFailureReason.INVALID_SCHEMA) {
            "spam shard manifest version must be positive"
        }
        requireFeed(manifest.updated.isNotBlank(), GitHubFeedFailureReason.MISSING_SCHEMA_FIELD) {
            "spam shard manifest updated timestamp is missing"
        }
        requireFeed(manifest.legacyPath == DATA_PATH, GitHubFeedFailureReason.INVALID_SCHEMA) {
            "spam shard manifest changed the legacy database path"
        }
        requireFeed(manifest.shardDirectory == SHARD_DIRECTORY_PATH.removeSuffix("/"), GitHubFeedFailureReason.INVALID_SCHEMA) {
            "spam shard manifest has an invalid shard directory"
        }
        requireFeed(manifest.shardCount == MAX_SPAM_SHARDS, GitHubFeedFailureReason.INVALID_SCHEMA) {
            "spam shard manifest shard count ${manifest.shardCount} does not match $MAX_SPAM_SHARDS"
        }
        requireFeed(manifest.shards.size <= MAX_SPAM_SHARDS, GitHubFeedFailureReason.ROW_LIMIT) {
            "spam shard manifest has too many shard descriptors"
        }

        val ids = manifest.shards.map { it.id }
        requireFeed(ids.size == ids.toSet().size, GitHubFeedFailureReason.INVALID_SCHEMA) {
            "spam shard manifest contains duplicate shard ids"
        }
        val totalNumbers = manifest.shards.sumOf { it.numbers.toLong() }
        val totalPrefixes = manifest.shards.sumOf { it.prefixes.toLong() }
        requireFeed(totalNumbers <= MAX_SPAM_DATABASE_NUMBERS, GitHubFeedFailureReason.ROW_LIMIT) {
            "spam shard manifest number row count $totalNumbers exceeds cap $MAX_SPAM_DATABASE_NUMBERS"
        }
        requireFeed(totalPrefixes <= MAX_SPAM_DATABASE_PREFIXES, GitHubFeedFailureReason.ROW_LIMIT) {
            "spam shard manifest prefix row count $totalPrefixes exceeds cap $MAX_SPAM_DATABASE_PREFIXES"
        }

        manifest.shards.forEach { descriptor ->
            requireFeed(SHARD_ID_REGEX.matches(descriptor.id), GitHubFeedFailureReason.INVALID_SCHEMA) {
                "spam shard id ${descriptor.id} is not two lowercase hexadecimal characters"
            }
            requireFeed(descriptor.path == "$SHARD_DIRECTORY_PATH${descriptor.id}.json", GitHubFeedFailureReason.INVALID_SCHEMA) {
                "spam shard ${descriptor.id} has an invalid path"
            }
            requireFeed(SHA256_REGEX.matches(descriptor.sha256), GitHubFeedFailureReason.INVALID_SCHEMA) {
                "spam shard ${descriptor.id} has an invalid content hash"
            }
            requireFeed(descriptor.bytes in 1..MAX_SPAM_SHARD_BYTES, GitHubFeedFailureReason.OVERSIZE) {
                "spam shard ${descriptor.id} has an invalid byte count"
            }
            requireFeed(descriptor.numbers >= 0 && descriptor.prefixes >= 0, GitHubFeedFailureReason.INVALID_SCHEMA) {
                "spam shard ${descriptor.id} has negative row counts"
            }
        }
    }

    private fun validateSpamDatabaseShard(shard: SpamDatabaseShard) {
        requireFeed(SHARD_ID_REGEX.matches(shard.shardId), GitHubFeedFailureReason.INVALID_SCHEMA) {
            "spam database shard id is invalid"
        }
        requireFeed(shard.numbers.size <= MAX_SPAM_DATABASE_NUMBERS, GitHubFeedFailureReason.ROW_LIMIT) {
            "spam database shard number row count exceeds cap $MAX_SPAM_DATABASE_NUMBERS"
        }
        requireFeed(shard.prefixes.size <= MAX_SPAM_DATABASE_PREFIXES, GitHubFeedFailureReason.ROW_LIMIT) {
            "spam database shard prefix row count exceeds cap $MAX_SPAM_DATABASE_PREFIXES"
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

    private data class GitHubReleasePayload(
        @Json(name = "tag_name") val tagName: String = "",
        @Json(name = "html_url") val htmlUrl: String = "",
        val assets: List<GitHubReleaseAsset> = emptyList(),
    )

    private data class GitHubReleaseAsset(
        val name: String = "",
        @Json(name = "browser_download_url") val browserDownloadUrl: String = "",
    )

    private data class HotListPayload(
        val numbers: List<HotListEntry> = emptyList(),
        val cleared: Boolean = false,
    )

    private data class HotListEntry(
        val number: String = "",
        val type: String = "robocall",
        val description: String = "Trending community report",
    )

    private data class HotRangesPayload(
        val ranges: List<HotRangeEntry> = emptyList(),
        val cleared: Boolean = false,
    )

    private data class HotRangeEntry(
        val npanxx: String = "",
    )

    private data class SpamDomainsPayload(
        val domains: List<String> = emptyList(),
        val cleared: Boolean = false,
    )
}
