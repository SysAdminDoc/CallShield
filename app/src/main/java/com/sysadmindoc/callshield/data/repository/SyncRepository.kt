package com.sysadmindoc.callshield.data.repository

import android.content.Context
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.ExternalBlocklistParser
import com.sysadmindoc.callshield.data.ParsedExternalBlocklist
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.local.SpamDao
import com.sysadmindoc.callshield.data.mergeHotListNumbers
import com.sysadmindoc.callshield.data.model.ExternalBlocklistImportResult
import com.sysadmindoc.callshield.data.model.ExternalBlocklistPreview
import com.sysadmindoc.callshield.data.model.ExternalBlocklistSubscription
import com.sysadmindoc.callshield.data.model.SpamDatabase
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.SpamPrefix
import com.sysadmindoc.callshield.data.model.SourceEvidenceJson
import com.sysadmindoc.callshield.data.SourceEvidenceCodec
import com.sysadmindoc.callshield.data.remote.ExternalBlocklistDataSource
import com.sysadmindoc.callshield.data.remote.GitHubDataSource
import com.sysadmindoc.callshield.data.remote.OkHttpExternalBlocklistDataSource
import com.sysadmindoc.callshield.data.remote.SpamDataSource
import com.sysadmindoc.callshield.data.sanitizeDatabaseNumbers
import com.sysadmindoc.callshield.domain.model.SyncResult
import com.sysadmindoc.callshield.ui.widget.CallShieldWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Suppress("LongParameterList", "TooManyFunctions")
class SyncRepository(
    private val context: Context,
    private val dao: SpamDao,
    private val remote: SpamDataSource,
    private val settingsRepository: SettingsRepository,
    private val normalizeNumber: (String) -> String,
    private val invalidateAllCaches: () -> Unit,
    private val externalBlocklistDataSource: ExternalBlocklistDataSource = OkHttpExternalBlocklistDataSource(),
) {
    private val syncMutex = Mutex()

    /**
     * @param force When true, skips the SHA check and always downloads.
     *              Used for manual sync to guarantee fresh data.
     */
    @Suppress("LongMethod", "TooGenericExceptionCaught")
    suspend fun syncFromGitHub(force: Boolean = false): SyncResult =
        withContext(Dispatchers.IO) {
            syncMutex.withLock {
                try {
                    val currentCount = dao.getSpamCount()
                    // Resolve the remote SHA BEFORE downloading. Resolving it
                    // after meant a data commit landing mid-sync recorded a SHA
                    // the download never contained — every later non-forced
                    // sync then compared equal and reported "up to date" while
                    // local data stayed one update stale. Recording the
                    // pre-fetch SHA fails the other way: at worst the next
                    // check sees a mismatch and re-downloads, which self-heals.
                    val preFetchSha = remote.checkForUpdate().getOrNull()
                    if (!force) {
                        val currentSha = settingsRepository.readLastDataSha()
                        if (preFetchSha != null && preFetchSha == currentSha) {
                            return@withContext SyncResult(
                                success = true,
                                message = context.getString(R.string.sync_database_up_to_date),
                            )
                        }
                    }

                    val result = remote.fetchSpamDatabase()
                    if (result.isSuccess) {
                        val database = result.getOrThrow()
                        val newSha = preFetchSha
                        val (numberCount, prefixCount) =
                            persistSpamDatabase(
                                database = database,
                                sha = newSha,
                                syncSource = SpamRepository.SYNC_SOURCE_REMOTE,
                            )
                        return@withContext SyncResult(
                            success = true,
                            message = context.getString(R.string.sync_success_counts, numberCount, prefixCount),
                        )
                    }

                    val remoteError =
                        result.exceptionOrNull()?.message
                            ?: context.getString(R.string.sync_unknown_error)
                    if (currentCount > 0) {
                        return@withContext SyncResult(
                            success = true,
                            message = context.getString(R.string.sync_remote_unavailable_existing, remoteError),
                            warning = true,
                        )
                    }

                    val bundledDatabase = loadBundledSpamDatabase()
                    if (bundledDatabase.isSuccess) {
                        val database = bundledDatabase.getOrThrow()
                        val (numberCount, prefixCount) =
                            persistSpamDatabase(
                                database = database,
                                sha = null,
                                syncSource = SpamRepository.SYNC_SOURCE_BUNDLED,
                            )
                        return@withContext SyncResult(
                            success = true,
                            message =
                                context.getString(
                                    R.string.sync_bundled_snapshot_loaded,
                                    numberCount,
                                    prefixCount,
                                ),
                            warning = true,
                        )
                    }

                    val bundledError = bundledDatabase.exceptionOrNull()?.message
                    val message =
                        buildString {
                            append(context.getString(R.string.sync_unavailable_prefix, remoteError))
                            if (!bundledError.isNullOrBlank()) {
                                append(context.getString(R.string.sync_bundled_fallback_failed, bundledError))
                            }
                        }

                    SyncResult(
                        success = false,
                        message = message,
                        shouldRetry = shouldRetrySync(remoteError),
                    )
                } catch (e: Exception) {
                    SyncResult(
                        success = false,
                        message = context.getString(R.string.sync_error, e.message ?: ""),
                        shouldRetry = true,
                    )
                }
            }
        }

    suspend fun replaceHotList(numbers: List<SpamNumber>) =
        withContext(Dispatchers.IO) {
            val hotNumbers =
                numbers
                    .filter { it.number.isNotBlank() }
                    .distinctBy { it.number }

            val existingByNumber =
                if (hotNumbers.isEmpty()) {
                    emptyMap()
                } else {
                    val existingRows = dao.getNumbersByNumbers(hotNumbers.map { it.number })
                    existingRows.associateBy { it.number }
                }

            val mergedHotNumbers =
                mergeHotListNumbers(
                    hotNumbers = hotNumbers,
                    existingByNumber = existingByNumber,
                )

            // Atomic delete + insert via the DAO's @Transaction helper. A bare
            // deleteBySource()/insertNumbers() pair left a window where a
            // concurrent screening lookup (HotListSyncWorker runs every 30 min)
            // could miss a hot-list number between the two statements.
            dao.replaceBySource("hot_list", mergedHotNumbers)
            // Hot list entries are exact number rows. Prefix/rule caches do not change here.
        }

    suspend fun previewExternalBlocklistSubscription(
        url: String,
        label: String = "",
    ): ExternalBlocklistImportResult =
        withContext(Dispatchers.IO) {
            syncMutex.withLock {
                runExternalBlocklistOperation {
                    val parsed = fetchAndParseExternalBlocklist(url, label)
                    val preview = buildExternalBlocklistPreview(parsed)
                    ExternalBlocklistImportResult(
                        success = true,
                        message = externalPreviewMessage(preview),
                        preview = preview,
                    )
                }
            }
        }

    suspend fun applyExternalBlocklistSubscription(
        url: String,
        label: String = "",
    ): ExternalBlocklistImportResult =
        withContext(Dispatchers.IO) {
            syncMutex.withLock {
                runExternalBlocklistOperation {
                    val parsed = fetchAndParseExternalBlocklist(url, label)
                    commitExternalBlocklist(parsed)
                }
            }
        }

    suspend fun setExternalBlocklistSubscriptionEnabled(
        id: String,
        enabled: Boolean,
    ): ExternalBlocklistImportResult =
        withContext(Dispatchers.IO) {
            syncMutex.withLock {
                runExternalBlocklistOperation {
                    val subscriptions = settingsRepository.readExternalBlocklistSubscriptions()
                    val subscription =
                        subscriptions.firstOrNull { it.id == id }
                            ?: return@runExternalBlocklistOperation ExternalBlocklistImportResult(
                                success = false,
                                message = context.getString(R.string.external_blocklist_not_found),
                            )
                    if (enabled) {
                        val parsed = fetchAndParseExternalBlocklist(subscription.url, subscription.label)
                        commitExternalBlocklist(parsed)
                    } else {
                        val removed = disableExternalBlocklist(subscription, subscriptions)
                        ExternalBlocklistImportResult(
                            success = true,
                            message = context.getString(R.string.external_blocklist_disabled, subscription.label, removed),
                            subscription =
                                subscription.copy(
                                    enabled = false,
                                    lastRemoved = removed,
                                    lastError = "",
                                ),
                        )
                    }
                }
            }
        }

    suspend fun removeExternalBlocklistSubscription(id: String): ExternalBlocklistImportResult =
        withContext(Dispatchers.IO) {
            syncMutex.withLock {
                runExternalBlocklistOperation {
                    val subscriptions = settingsRepository.readExternalBlocklistSubscriptions()
                    val subscription =
                        subscriptions.firstOrNull { it.id == id }
                            ?: return@runExternalBlocklistOperation ExternalBlocklistImportResult(
                                success = false,
                                message = context.getString(R.string.external_blocklist_not_found),
                            )
                    val before = dao.getCountBySource(subscription.source)
                    dao.deleteBySource(subscription.source)
                    settingsRepository.saveExternalBlocklistSubscriptions(subscriptions.filterNot { it.id == id })
                    invalidateAllCaches()
                    CallShieldWidget.refreshAll(context)
                    ExternalBlocklistImportResult(
                        success = true,
                        message = context.getString(R.string.external_blocklist_removed, subscription.label, before),
                        subscription = subscription.copy(enabled = false, lastRemoved = before),
                    )
                }
            }
        }

    private suspend fun loadBundledSpamDatabase(): Result<SpamDatabase> {
        val asset = GitHubDataSource.readBundledAsset(context, GitHubDataSource.BUNDLED_DATABASE_ASSET)
        if (asset.isFailure) {
            return Result.failure(asset.exceptionOrNull()!!)
        }
        return remote.parseSpamDatabaseJson(asset.getOrThrow())
    }

    private suspend fun persistSpamDatabase(
        database: SpamDatabase,
        sha: String?,
        syncSource: String,
    ): Pair<Int, Int> {
        val now = System.currentTimeMillis()
        val preservedUserRows = dao.getUserBlockedNumbersSync()
        val preservedUserBlocks =
            preservedUserRows
                .mapNotNull { row ->
                    val activeRow = row.activeDecision(now)
                    if (activeRow?.isUserBlocked == true) {
                        activeRow.number to activeRow.expiresAt
                    } else {
                        null
                    }
                }.toMap()

        val numbers =
            sanitizeDatabaseNumbers(
                databaseNumbers = database.numbers,
                normalizeNumber = normalizeNumber,
                preservedUserBlockedNumbers = preservedUserBlocks,
            )
        val prefixes =
            database.prefixes.mapNotNull { json ->
                val trimmedPrefix = json.prefix.trim()
                // PrefixChecker is a startsWith hard block at priority 6000,
                // so a malformed feed row like "+" or "+1" would block every
                // (NANP) caller. Require "+" plus at least 3 digits — the
                // shortest legitimate rows are whole country codes ("+232").
                if (!VALID_PREFIX_REGEX.matches(trimmedPrefix)) {
                    null
                } else {
                    val evidence =
                        json.evidence.ifEmpty {
                            listOf(
                                SourceEvidenceJson(
                                    sourceId = "github_database",
                                    evidenceType = "aggregate_database",
                                    license = "CallShield database terms",
                                    attribution = "CallShield maintained spam database",
                                    confidenceTier = "unverified",
                                    parserVersion = "legacy-v1",
                                ),
                            )
                        }
                    SpamPrefix(
                        prefix = trimmedPrefix,
                        type = json.type.trim().ifBlank { "unknown" },
                        description = json.description.trim(),
                        evidenceJson = SourceEvidenceCodec.encode(evidence),
                        evidenceExpiresAt = evidence.mapNotNull { it.expiresAtEpochMs }.minOrNull(),
                    )
                }
            }

        dao.replaceGithubData(numbers, prefixes)
        invalidateAllCaches()

        settingsRepository.recordSyncSuccess(
            sha = sha,
            syncSource = syncSource,
            databaseVersion = database.version,
        )

        CallShieldWidget.refreshAll(context)
        return numbers.size to prefixes.size
    }

    private suspend fun fetchAndParseExternalBlocklist(
        url: String,
        label: String,
    ): ParsedExternalBlocklist =
        externalBlocklistDataSource
            .fetchText(url)
            .map { body ->
                ExternalBlocklistParser.parse(
                    rawUrl = url,
                    rawLabel = label,
                    body = body,
                    normalizeNumber = normalizeNumber,
                )
            }.getOrThrow()

    private suspend fun buildExternalBlocklistPreview(parsed: ParsedExternalBlocklist): ExternalBlocklistPreview {
        val currentRows = dao.getNumbersBySource(parsed.source)
        val currentKeys = currentRows.map { ExternalBlocklistParser.canonicalNumberKey(it.number) }.toSet()
        val candidates = resolveExternalBlocklistCandidates(parsed)
        val nextKeys = candidates.accepted.map { ExternalBlocklistParser.canonicalNumberKey(it.number) }.toSet()
        return ExternalBlocklistPreview(
            id = parsed.id,
            label = parsed.label,
            url = parsed.url,
            source = parsed.source,
            format = parsed.format,
            numberCount = candidates.accepted.size,
            added = (nextKeys - currentKeys).size,
            removed = (currentKeys - nextKeys).size,
            unchanged = currentKeys.intersect(nextKeys).size,
            skippedRows = parsed.skippedRows,
            blockedByOtherSources = candidates.blockedByOtherSources,
        )
    }

    private suspend fun commitExternalBlocklist(parsed: ParsedExternalBlocklist): ExternalBlocklistImportResult {
        val preview = buildExternalBlocklistPreview(parsed)
        val candidates = resolveExternalBlocklistCandidates(parsed)
        dao.replaceBySource(parsed.source, candidates.accepted)
        invalidateAllCaches()

        val subscription =
            ExternalBlocklistSubscription(
                id = parsed.id,
                label = parsed.label,
                url = parsed.url,
                enabled = true,
                lastSyncedAt = System.currentTimeMillis(),
                lastNumberCount = candidates.accepted.size,
                lastAdded = preview.added,
                lastRemoved = preview.removed,
                lastError = "",
            )
        upsertExternalBlocklistSubscription(subscription)
        CallShieldWidget.refreshAll(context)
        return ExternalBlocklistImportResult(
            success = true,
            message = externalAppliedMessage(preview),
            preview = preview,
            subscription = subscription,
        )
    }

    private suspend fun resolveExternalBlocklistCandidates(parsed: ParsedExternalBlocklist): ExternalCandidateSet {
        val requestedNumbers = parsed.numbers.distinctBy { ExternalBlocklistParser.canonicalNumberKey(it.number) }
        if (requestedNumbers.isEmpty()) return ExternalCandidateSet(emptyList(), 0)
        val existingByKey =
            requestedNumbers
                .map { it.number }
                .chunked(EXTERNAL_BLOCKLIST_LOOKUP_CHUNK_SIZE)
                .flatMap { chunk -> dao.getNumbersByNumbers(chunk) }
                .associateBy { ExternalBlocklistParser.canonicalNumberKey(it.number) }
        var blockedByOtherSources = 0
        val accepted =
            requestedNumbers.mapNotNull { candidate ->
                val key = ExternalBlocklistParser.canonicalNumberKey(candidate.number)
                val existing = existingByKey[key]
                when {
                    existing == null -> {
                        candidate
                    }

                    existing.source == parsed.source -> {
                        candidate.copy(
                            id = existing.id,
                            isUserBlocked = existing.isUserBlocked,
                            // Preserve an active temporary-block expiry (mirrors
                            // the GitHub-sync and hot-list merge paths). Dropping
                            // it would silently convert a "block for N hours" into
                            // a permanent block on the next feed refresh.
                            expiresAt = existing.expiresAt,
                        )
                    }

                    else -> {
                        blockedByOtherSources++
                        null
                    }
                }
            }
        return ExternalCandidateSet(accepted, blockedByOtherSources)
    }

    private suspend fun upsertExternalBlocklistSubscription(subscription: ExternalBlocklistSubscription) {
        val subscriptions = settingsRepository.readExternalBlocklistSubscriptions()
        settingsRepository.saveExternalBlocklistSubscriptions(
            subscriptions.filterNot { it.id == subscription.id } + subscription,
        )
    }

    private suspend fun disableExternalBlocklist(
        subscription: ExternalBlocklistSubscription,
        subscriptions: List<ExternalBlocklistSubscription>,
    ): Int {
        val before = dao.getCountBySource(subscription.source)
        dao.deleteBySource(subscription.source)
        settingsRepository.saveExternalBlocklistSubscriptions(
            subscriptions.map {
                if (it.id == subscription.id) {
                    it.copy(
                        enabled = false,
                        lastSyncedAt = System.currentTimeMillis(),
                        lastNumberCount = 0,
                        lastAdded = 0,
                        lastRemoved = before,
                        lastError = "",
                    )
                } else {
                    it
                }
            },
        )
        invalidateAllCaches()
        CallShieldWidget.refreshAll(context)
        return before
    }

    private fun externalPreviewMessage(preview: ExternalBlocklistPreview): String =
        context.getString(
            R.string.external_blocklist_previewed,
            preview.label,
            preview.numberCount,
            preview.added,
            preview.removed,
            preview.blockedByOtherSources,
        )

    private fun externalAppliedMessage(preview: ExternalBlocklistPreview): String =
        context.getString(
            R.string.external_blocklist_applied,
            preview.label,
            preview.numberCount,
            preview.added,
            preview.removed,
        )

    @Suppress("TooGenericExceptionCaught")
    private inline fun runExternalBlocklistOperation(
        block: () -> ExternalBlocklistImportResult,
    ): ExternalBlocklistImportResult =
        try {
            block()
        } catch (e: Exception) {
            // Same hygiene as the v1.7.26 restore/import pass: raw e.message
            // embeds the full user URL (OkHttp) or SQLite constraint names —
            // log the cause for diagnostics, show a localized generic reason.
            android.util.Log.w("SyncRepository", "External blocklist operation failed", e)
            ExternalBlocklistImportResult(
                success = false,
                message = context.getString(R.string.external_blocklist_failed_generic),
            )
        }

    private fun shouldRetrySync(message: String): Boolean {
        val permanentFailureCodes = listOf("HTTP 400", "HTTP 401", "HTTP 403", "HTTP 404")
        return permanentFailureCodes.none { code -> message.contains(code) }
    }

    private data class ExternalCandidateSet(
        val accepted: List<SpamNumber>,
        val blockedByOtherSources: Int,
    )
}

private const val EXTERNAL_BLOCKLIST_LOOKUP_CHUNK_SIZE = 500

/** "+", then 3-15 digits: whole-country-code rows are the shortest legitimate prefixes. */
private val VALID_PREFIX_REGEX = Regex("""\+[0-9]{3,15}""")
