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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal enum class GitHubFeedFailureReason {
    OVERSIZE,
    ROW_LIMIT,
    MISSING_SCHEMA_FIELD,
    INVALID_SCHEMA,

    /** The feed's `.sig` was missing or didn't verify under a trusted key. */
    SIGNATURE,
}

internal class GitHubFeedValidationException(
    val reason: GitHubFeedFailureReason,
    message: String,
) : IllegalArgumentException(message)

/** A feed host answered with an HTTP error. SyncRepository reads the "HTTP <code>" message to decide whether to retry. */
internal class GitHubFeedHttpException(
    val code: Int,
    reason: String,
) : Exception("HTTP $code: $reason")

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

/**
 * What GitHubDataSource has learned about GitHub lately. HotDataSync,
 * SyncRepository and the model sync each build their own GitHubDataSource,
 * so production instances share one of these: an outage one of them saw, a
 * branch lookup that failed, or a pin failure holds for the others. Tests
 * get their own, so they can't leak state into each other.
 */
internal class GitHubFetchState {
    val lock = Any()

    // Resolved default branch per "owner/repo" -> (branch, resolvedAtMs).
    val defaultBranchCache = mutableMapOf<String, Pair<String, Long>>()
    val defaultBranchFailedAt = mutableMapOf<String, Long>()

    @Volatile
    var gitHubUnreachableAt: Long? = null

    @Volatile
    var gitHubTrustFailing: Boolean = false

    companion object {
        val shared = GitHubFetchState()
    }
}

class GitHubDataSource internal constructor(
    /** Tests serve canned responses through this instead of the network. */
    testInterceptor: Interceptor?,
    /** Tests move time on to end a GitHub outage window. */
    private val clock: () -> Long = System::currentTimeMillis,
    /** [GitHubFetchState.shared] in production; tests get their own. */
    internal val state: GitHubFetchState = GitHubFetchState(),
) : SpamDataSource,
    HotFeedDataSource {
    constructor() : this(null, System::currentTimeMillis, GitHubFetchState.shared)

    // Derived client with longer timeouts for large database downloads;
    // shares the connection pool with other callers via HttpClient.shared.
    // No whole-call timeout: the 14.5 MB legacy database needs about five
    // minutes at 48 KB/s, and a live but slow download must be allowed to
    // finish. The read timeout already ends one that stops sending.
    private val client =
        HttpClient.shared
            .newBuilder()
            .apply { testInterceptor?.let(::addInterceptor) }
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

    private val defaultBranchLock get() = state.lock
    private val defaultBranchCache get() = state.defaultBranchCache
    private val defaultBranchFailedAt get() = state.defaultBranchFailedAt

    // When GitHub last couldn't be connected to at all. For GITHUB_OUTAGE_MS
    // after that, fetches ask the mirror first.
    private var gitHubUnreachableAt: Long?
        get() = state.gitHubUnreachableAt
        set(value) {
            state.gitHubUnreachableAt = value
        }

    // Set when GitHub fails certificate verification and cleared when GitHub
    // serves a file. A mirror serving meanwhile leaves it set: the pins still
    // need an app update, and the update notice has to hear about it.
    override var gitHubTrustFailing: Boolean
        get() = state.gitHubTrustFailing
        private set(value) {
            state.gitHubTrustFailing = value
        }

    // Which files the mirror served the last time each was fetched. A database
    // the mirror served can be hours older than GitHub's newest commit, so
    // SyncRepository doesn't file it under that commit's id. Kept per file
    // because this instance is shared: a hot-list fetch running beside a
    // sync mustn't answer for the database.
    private val servedByMirror = ConcurrentHashMap<String, Boolean>()

    override fun lastServedByMirror(path: String): Boolean = servedByMirror[path] == true

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
        private const val HTTP_NOT_FOUND = 404

        /** How long a resolved default branch stays cached before re-querying. */
        private const val DEFAULT_BRANCH_TTL_MS = 6L * 60L * 60L * 1000L // 6 hours
        private val FALLBACK_BRANCHES = listOf("main", "master")

        /** How long a failed default-branch lookup stands before GitHub's API is asked again. */
        private const val DEFAULT_BRANCH_RETRY_MS = 10L * 60L * 1000L

        /** How long the mirror goes first after GitHub couldn't be reached at all. */
        internal const val GITHUB_OUTAGE_MS = 10L * 60L * 1000L

        private val COMMIT_SHA_REGEX = Regex("[0-9a-f]{40}(?:[0-9a-f]{24})?")
        private const val MAX_COMMIT_SHA_BYTES = 128L
        private val RAW_FEED_SPECS =
            mapOf(
                DATA_PATH to RawFeedSpec("spam database", MAX_SPAM_DATABASE_BYTES),
                SHARD_MANIFEST_PATH to RawFeedSpec("spam database shard manifest", MAX_GITHUB_API_BYTES),
                HOT_LIST_PATH to RawFeedSpec("hot list", MAX_HOT_LIST_BYTES),
                HOT_RANGES_PATH to RawFeedSpec("hot ranges", MAX_HOT_RANGES_BYTES),
                SPAM_DOMAINS_PATH to RawFeedSpec("spam domains", MAX_SPAM_DOMAINS_BYTES),
                MODEL_WEIGHTS_PATH to RawFeedSpec("model weights", MAX_MODEL_WEIGHTS_BYTES),
            )

        /**
         * Feeds refused without a valid detached signature ([FeedSignature]).
         * Shards need none: the signed manifest carries each shard's SHA-256.
         * scripts/feed_signing.py signs the same files; its test checks the lists agree.
         */
        internal val SIGNED_FEED_PATHS =
            setOf(DATA_PATH, SHARD_MANIFEST_PATH, HOT_LIST_PATH, HOT_RANGES_PATH, SPAM_DOMAINS_PATH, MODEL_WEIGHTS_PATH)

        /** Throws unless a signed feed arrived with a signature the app accepts. */
        internal fun requireFeedSignature(
            path: String,
            body: String,
            signatureText: String?,
        ) {
            if (path !in SIGNED_FEED_PATHS) return
            val label = rawFeedLabel(path)
            requireFeed(signatureText != null, GitHubFeedFailureReason.SIGNATURE) { "$label feed has no signature" }
            requireFeed(
                FeedSignature.verifies(body.toByteArray(Charsets.UTF_8), signatureText.orEmpty()),
                GitHubFeedFailureReason.SIGNATURE,
            ) { "$label feed signature doesn't verify" }
        }

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
            // A signed file that isn't this feed is refused, not thrown out of the refresh.
            try {
                Result.success(parseHotListSnapshotJson(result.getOrThrow()))
            } catch (refused: GitHubFeedValidationException) {
                Result.failure(refused)
            }
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
            // A signed file that isn't this feed is refused, not thrown out of the refresh.
            try {
                Result.success(parseHotRangesSnapshotJson(result.getOrThrow()))
            } catch (refused: GitHubFeedValidationException) {
                Result.failure(refused)
            }
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
            // A signed file that isn't this feed is refused, not thrown out of the refresh.
            try {
                Result.success(parseSpamDomainsSnapshotJson(result.getOrThrow()))
            } catch (refused: GitHubFeedValidationException) {
                Result.failure(refused)
            }
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
        var generatedAt: String? = null
        var inputDigest: String? = null
        val (entries, explicitlyCleared) =
            when {
                trimmedBody.startsWith("{") -> {
                    val payload = hotListEnvelopeAdapter.fromJson(body) ?: error("Failed to parse hot list payload")
                    val numbers = payload.numbers ?: failFeedValidation(GitHubFeedFailureReason.MISSING_SCHEMA_FIELD, "hot list has no numbers")
                    generatedAt = payload.generated
                    inputDigest = payload.inputReportDigest
                    numbers to payload.cleared
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
            generatedAt = generatedAt,
            inputDigest = inputDigest,
        )
    }

    override fun parseHotRangesJson(body: String): List<String> = parseHotRangesSnapshotJson(body).data

    override fun parseHotRangesSnapshotJson(body: String): HotFeedSnapshot<List<String>> {
        val trimmedBody = body.trimStart()
        var generatedAt: String? = null
        var inputDigest: String? = null
        val (ranges, explicitlyCleared) =
            when {
                trimmedBody.startsWith("{") -> {
                    val payload = hotRangesEnvelopeAdapter.fromJson(body) ?: error("Failed to parse hot ranges payload")
                    val ranges = payload.ranges ?: failFeedValidation(GitHubFeedFailureReason.MISSING_SCHEMA_FIELD, "hot ranges has no ranges")
                    generatedAt = payload.generated
                    inputDigest = payload.inputReportDigest
                    ranges.map { it.npanxx } to payload.cleared
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
        return HotFeedSnapshot(ranges, explicitlyCleared, generatedAt, inputDigest)
    }

    override fun parseSpamDomainsJson(body: String): List<String> = parseSpamDomainsSnapshotJson(body).data

    override fun parseSpamDomainsSnapshotJson(body: String): HotFeedSnapshot<List<String>> {
        val trimmedBody = body.trimStart()
        var generatedAt: String? = null
        var inputDigest: String? = null
        val (domains, explicitlyCleared) =
            when {
                trimmedBody.startsWith("{") -> {
                    val payload = spamDomainsEnvelopeAdapter.fromJson(body) ?: error("Failed to parse spam domains payload")
                    val domains = payload.domains ?: failFeedValidation(GitHubFeedFailureReason.MISSING_SCHEMA_FIELD, "spam domains has no domains")
                    generatedAt = payload.generated
                    inputDigest = payload.inputReportDigest
                    domains to payload.cleared
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
            generatedAt = generatedAt,
            inputDigest = inputDigest,
        )
    }

    /**
     * Fetches the manifest and its signature from [baseUrl] the way a sync
     * would, so a mirror that serves nothing, an error page for every path,
     * or unsigned copies is caught before it's saved rather than at the
     * next GitHub outage. Only the manifest pair is probed: it validates
     * the signature and JSON shape, which is enough to confirm the mirror
     * serves real, signed data. Individual feeds are verified at sync time.
     */
    override suspend fun probeMirror(baseUrl: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val base = FeedMirror.normalize(baseUrl) ?: return@withContext Result.failure(IllegalArgumentException("Not a usable mirror address"))
            val url = base + SHARD_MANIFEST_PATH
            try {
                val body = fetchVerifiedOnce(SHARD_MANIFEST_PATH, url, "$url.sig") ?: return@withContext Result.failure(Exception("Empty response body"))
                parseSpamShardManifestJson(body).map { }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private suspend fun fetchRawText(
        path: String,
        owner: String,
        repo: String,
    ): Result<String> {
        var lastError: Exception? = null
        for (source in feedSources(path, owner, repo)) {
            try {
                fetchVerified(path, source)?.let {
                    servedByMirror[path] = source.mirror
                    if (!source.mirror) {
                        gitHubUnreachableAt = null
                        gitHubTrustFailing = false
                    }
                    return Result.success(it)
                }
                lastError = moreTelling(lastError, Exception("Empty response body"), source.mirror)
            } catch (e: Exception) {
                if (!source.mirror && isUnreachable(e)) gitHubUnreachableAt = clock()
                if (!source.mirror && HttpClient.isCertificateTrustFailure(e)) gitHubTrustFailing = true
                lastError = moreTelling(lastError, e, source.mirror)
            }
        }
        return Result.failure(lastError ?: Exception("Unable to fetch $path"))
    }

    /**
     * Where [path] can come from, in order: each candidate GitHub branch, then
     * the user's [FeedMirror] when one is set. Every source carries its own
     * signature URL on the same host and branch, so a copy refused on one
     * source can't borrow another source's signature.
     *
     * For [GITHUB_OUTAGE_MS] after GitHub couldn't be reached at all, the
     * mirror goes first and the branch lookup is skipped. Behind a GitHub
     * block every GitHub request costs a full timeout, three of them per file,
     * so a sync of 256 shards would never end. GitHub stays in the list, after
     * the mirror, in case the mirror fails too.
     */
    private suspend fun feedSources(
        path: String,
        owner: String,
        repo: String,
    ): List<FeedSource> {
        FeedMirror.awaitLoaded()
        val mirror = FeedMirror.urlFor(path)?.let { FeedSource(it, "$it.sig", mirror = true) }
        if (mirror != null && gitHubRecentlyUnreachable()) {
            return listOf(mirror) + gitHubSources(path, owner, repo, knownBranches(owner, repo))
        }
        return gitHubSources(path, owner, repo, resolveCandidateBranches(owner, repo)) + listOfNotNull(mirror)
    }

    private fun gitHubSources(
        path: String,
        owner: String,
        repo: String,
        branches: List<String>,
    ): List<FeedSource> =
        branches.map { branch ->
            FeedSource(
                url = buildRawUrl(owner, repo, branch, path),
                signatureUrl = buildRawUrl(owner, repo, branch, "$path.sig"),
                pin = GitHubPin(owner, repo, branch),
            )
        }

    private fun gitHubRecentlyUnreachable(): Boolean = gitHubUnreachableAt?.let { clock() - it < GITHUB_OUTAGE_MS } == true

    /** A GitHub download that got no answer at all, as opposed to one GitHub refused or failed. */
    private fun isUnreachable(error: Exception): Boolean =
        error is ConnectException ||
            error is NoRouteToHostException ||
            error is UnknownHostException ||
            // A connect that timed out, not a read that stalled after the body
            // started: a slow download from a live GitHub isn't an outage.
            (error is SocketTimeoutException && error.message.orEmpty().contains("connect", ignoreCase = true))

    /**
     * [path] from [source], size-checked and signature-checked, or null for
     * an empty body. GitHub's CDN caches a file and its signature separately,
     * for minutes, and leaves the query string out of its cache key, so right
     * after a publish it can pair the new body with the old signature and no
     * `?` gets past that. A signature refusal from a GitHub branch is tried
     * once more at the commit the branch points to: that path never changes,
     * so its body and signature come from one publish. The refusal stands if
     * that copy fails too or the commit can't be resolved. A mirror gets no
     * second try, since its cache turns over on its own schedule.
     */
    private fun fetchVerified(
        path: String,
        source: FeedSource,
    ): String? =
        try {
            fetchVerifiedOnce(path, source.url, source.signatureUrl)
        } catch (refused: GitHubFeedValidationException) {
            if (refused.reason != GitHubFeedFailureReason.SIGNATURE) throw refused
            val pin = source.pin ?: throw refused
            val commit = resolveCommit(pin) ?: throw refused
            try {
                fetchVerifiedOnce(
                    path,
                    buildRawUrl(pin.owner, pin.repo, commit, path),
                    buildRawUrl(pin.owner, pin.repo, commit, "$path.sig"),
                )
            } catch (_: Exception) {
                throw refused
            }
        }

    /** The commit [pin]'s branch points to, or null when GitHub won't say. One small API call, made only after a refusal. */
    private fun resolveCommit(pin: GitHubPin): String? =
        try {
            val request =
                Request
                    .Builder()
                    .url("$GITHUB_API_BASE/${pin.owner}/${pin.repo}/commits/${pin.branch}")
                    .header("Accept", "application/vnd.github.sha")
                    .header("User-Agent", USER_AGENT)
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                readLimitedBody(response, "GitHub commit API", MAX_COMMIT_SHA_BYTES)
                    ?.trim()
                    ?.takeIf(COMMIT_SHA_REGEX::matches)
            }
        } catch (_: IOException) {
            null
        } catch (_: GitHubFeedValidationException) {
            null
        }

    private fun fetchVerifiedOnce(
        path: String,
        url: String,
        signatureUrl: String,
    ): String? =
        client.newCall(rawRequest(url)).execute().use { response ->
            if (!response.isSuccessful) throw GitHubFeedHttpException(response.code, response.message)
            readLimitedBody(response, rawFeedLabel(path), rawFeedMaxBytes(path))?.let { body ->
                signatureChecked(path, validateRawFeedBody(path, body), signatureUrl)
            }
        }

    /** [body], once a signed feed's signature from [signatureUrl] verifies. */
    private fun signatureChecked(
        path: String,
        body: String,
        signatureUrl: String,
    ): String {
        if (path in SIGNED_FEED_PATHS) requireFeedSignature(path, body, fetchSignature(path, signatureUrl))
        return body
    }

    /**
     * The failure to report once [next] follows [previous]. A source that
     * served the file but failed validation (a bad signature, an oversized
     * body) says more than a later source answering 404, and the callers treat
     * the two differently. A later 404 never hides an earlier failure either:
     * the fallback branch that doesn't exist says nothing about why the real
     * one failed, and SyncRepository stops retrying on a 404. A mirror's
     * failure never hides GitHub's unless the
     * mirror served a file that was refused: SyncRepository decides whether to
     * retry from the status code, and the certificate notice fires on a trust
     * failure, and both are about GitHub.
     */
    private fun moreTelling(
        previous: Exception?,
        next: Exception,
        nextFromMirror: Boolean,
    ): Exception {
        val nextRefused = next is GitHubFeedValidationException
        val previousRefused = previous is GitHubFeedValidationException
        return when {
            previous == null -> next
            nextFromMirror -> if (nextRefused && !previousRefused && !HttpClient.isCertificateTrustFailure(previous)) next else previous
            previousRefused && !nextRefused -> previous
            next is GitHubFeedHttpException && next.code == HTTP_NOT_FOUND -> previous
            else -> next
        }
    }

    private fun fetchSignature(
        path: String,
        signatureUrl: String,
    ): String? =
        client.newCall(rawRequest(signatureUrl)).execute().use { response ->
            when {
                response.isSuccessful -> {
                    readLimitedBody(response, "${rawFeedLabel(path)} signature", FeedSignature.MAX_SIGNATURE_BYTES)
                }

                // Only a missing file means unsigned. A busy or failing host says
                // nothing about the feed, and reads as a network failure instead.
                response.code == HTTP_NOT_FOUND -> {
                    null
                }

                else -> {
                    throw GitHubFeedHttpException(response.code, response.message)
                }
            }
        }

    private fun rawRequest(url: String): Request =
        Request
            .Builder()
            .url(url)
            .header("Cache-Control", "no-store, max-age=0")
            .header("User-Agent", USER_AGENT)
            .build()

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
        val now = clock()
        synchronized(defaultBranchLock) {
            val cached = defaultBranchCache[key]
            if (cached != null && now - cached.second < DEFAULT_BRANCH_TTL_MS) {
                return cached.first
            }
            // A lookup that just failed isn't repeated for every file in a
            // sync: behind a GitHub block each attempt costs a full timeout.
            val failedAt = defaultBranchFailedAt[key]
            if (failedAt != null && now - failedAt < DEFAULT_BRANCH_RETRY_MS) return cached?.first
        }
        val resolved = fetchDefaultBranch(owner, repo).getOrNull()
        synchronized(defaultBranchLock) {
            if (resolved == null) {
                defaultBranchFailedAt[key] = now
                return defaultBranchCache[key]?.first
            }
            defaultBranchCache[key] = resolved to now
            defaultBranchFailedAt.remove(key)
        }
        return resolved
    }

    /** The candidate branches without asking the API: the cached default, then the fallbacks. */
    private fun knownBranches(
        owner: String,
        repo: String,
    ): List<String> {
        val cached = synchronized(defaultBranchLock) { defaultBranchCache["$owner/$repo"]?.first }
        return listOfNotNull(cached).plus(FALLBACK_BRANCHES).distinct()
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

    /** One place a feed can come from, with its detached signature's URL on the same host and branch. */
    private data class FeedSource(
        val url: String,
        val signatureUrl: String,
        val mirror: Boolean = false,
        /** The GitHub branch a source reads, for the retry at its commit. Null for the mirror. */
        val pin: GitHubPin? = null,
    )

    private data class GitHubPin(
        val owner: String,
        val repo: String,
        val branch: String,
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

    // Each feed's items key is nullable so a file without it is refused rather
    // than read as empty. Signatures cover bytes, not paths, so another signed
    // feed, or the model, served at this path must not parse as this one.
    private data class HotListPayload(
        val numbers: List<HotListEntry>? = null,
        val cleared: Boolean = false,
        val generated: String? = null,
        @Json(name = "input_report_digest") val inputReportDigest: String? = null,
    )

    private data class HotListEntry(
        val number: String = "",
        val type: String = "robocall",
        val description: String = "Trending community report",
    )

    private data class HotRangesPayload(
        val ranges: List<HotRangeEntry>? = null,
        val cleared: Boolean = false,
        val generated: String? = null,
        @Json(name = "input_report_digest") val inputReportDigest: String? = null,
    )

    private data class HotRangeEntry(
        val npanxx: String = "",
    )

    private data class SpamDomainsPayload(
        val domains: List<String>? = null,
        val cleared: Boolean = false,
        val generated: String? = null,
        @Json(name = "input_report_digest") val inputReportDigest: String? = null,
    )
}
