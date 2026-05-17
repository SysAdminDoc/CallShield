package com.sysadmindoc.callshield.data.repository

import android.content.Context
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.local.SpamDao
import com.sysadmindoc.callshield.data.mergeHotListNumbers
import com.sysadmindoc.callshield.data.model.SpamDatabase
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.SpamPrefix
import com.sysadmindoc.callshield.data.remote.GitHubDataSource
import com.sysadmindoc.callshield.data.remote.SpamDataSource
import com.sysadmindoc.callshield.data.sanitizeDatabaseNumbers
import com.sysadmindoc.callshield.domain.model.SyncResult
import com.sysadmindoc.callshield.ui.widget.CallShieldWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Suppress("LongParameterList")
class SyncRepository(
    private val context: Context,
    private val dao: SpamDao,
    private val remote: SpamDataSource,
    private val settingsRepository: SettingsRepository,
    private val normalizeNumber: (String) -> String,
    private val invalidateAllCaches: () -> Unit,
) {
    private val syncMutex = Mutex()

    /**
     * @param force When true, skips the SHA check and always downloads.
     *              Used for manual sync to guarantee fresh data.
     */
    @Suppress("LongMethod", "TooGenericExceptionCaught")
    suspend fun syncFromGitHub(force: Boolean = false): SyncResult = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            try {
                val currentCount = dao.getSpamCount()
                if (!force) {
                    val currentSha = settingsRepository.readLastDataSha()
                    val remoteResult = remote.checkForUpdate()
                    val newSha = remoteResult.getOrNull()

                    if (newSha != null && newSha == currentSha) {
                        return@withContext SyncResult(success = true, message = "Database is up to date")
                    }
                }

                val result = remote.fetchSpamDatabase()
                if (result.isSuccess) {
                    val database = result.getOrThrow()
                    val newSha = remote.checkForUpdate().getOrNull()
                    val (numberCount, prefixCount) = persistSpamDatabase(
                        database = database,
                        sha = newSha,
                        syncSource = SpamRepository.SYNC_SOURCE_REMOTE,
                    )
                    return@withContext SyncResult(
                        success = true,
                        message = "Synced $numberCount numbers, $prefixCount prefixes",
                    )
                }

                val remoteError = result.exceptionOrNull()?.message ?: "Unknown sync error"
                if (currentCount > 0) {
                    return@withContext SyncResult(
                        success = true,
                        message = "GitHub sync unavailable ($remoteError). " +
                            "Your existing spam database is still active.",
                        warning = true,
                    )
                }

                val bundledDatabase = loadBundledSpamDatabase()
                if (bundledDatabase.isSuccess) {
                    val database = bundledDatabase.getOrThrow()
                    val (numberCount, prefixCount) = persistSpamDatabase(
                        database = database,
                        sha = null,
                        syncSource = SpamRepository.SYNC_SOURCE_BUNDLED,
                    )
                    return@withContext SyncResult(
                        success = true,
                        message = "Loaded bundled protection snapshot with $numberCount numbers and " +
                            "$prefixCount prefixes while GitHub was unavailable.",
                        warning = true,
                    )
                }

                val bundledError = bundledDatabase.exceptionOrNull()?.message
                val message = buildString {
                    append("Sync unavailable (")
                    append(remoteError)
                    append(")")
                    if (!bundledError.isNullOrBlank()) {
                        append(". Bundled fallback failed: ")
                        append(bundledError)
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
                    message = "Error: ${e.message}",
                    shouldRetry = true,
                )
            }
        }
    }

    suspend fun replaceHotList(numbers: List<SpamNumber>) = withContext(Dispatchers.IO) {
        val hotNumbers = numbers
            .filter { it.number.isNotBlank() }
            .distinctBy { it.number }

        val existingByNumber = if (hotNumbers.isEmpty()) {
            emptyMap()
        } else {
            dao.getNumbersByNumbers(hotNumbers.map { it.number })
                .associateBy { it.number }
        }

        val mergedHotNumbers = mergeHotListNumbers(
            hotNumbers = hotNumbers,
            existingByNumber = existingByNumber,
        )

        dao.deleteBySource("hot_list")
        if (mergedHotNumbers.isNotEmpty()) {
            dao.insertNumbers(mergedHotNumbers)
        }
        // Hot list entries are exact number rows. Prefix/rule caches do not change here.
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
        val preservedUserBlocks = dao.getUserBlockedNumbersSync()
            .asSequence()
            .map { it.number }
            .toSet()

        val numbers = sanitizeDatabaseNumbers(
            databaseNumbers = database.numbers,
            normalizeNumber = normalizeNumber,
            preservedUserBlockedNumbers = preservedUserBlocks,
        )
        val prefixes = database.prefixes.mapNotNull { json ->
            val trimmedPrefix = json.prefix.trim()
            if (trimmedPrefix.isBlank()) {
                null
            } else {
                SpamPrefix(
                    prefix = trimmedPrefix,
                    type = json.type.trim().ifBlank { "unknown" },
                    description = json.description.trim(),
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

    private fun shouldRetrySync(message: String): Boolean {
        val permanentFailureCodes = listOf("HTTP 400", "HTTP 401", "HTTP 403", "HTTP 404")
        return permanentFailureCodes.none { code -> message.contains(code) }
    }
}
