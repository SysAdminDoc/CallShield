package com.sysadmindoc.callshield.data.repository

import android.content.Context
import com.sysadmindoc.callshield.data.SpamNumberWhitelistResolution
import com.sysadmindoc.callshield.data.TimeSchedule
import com.sysadmindoc.callshield.data.escapeLikeQuery
import com.sysadmindoc.callshield.data.local.SpamDao
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.data.model.HashWildcardRule
import com.sysadmindoc.callshield.data.model.PendingBlockedCallLog
import com.sysadmindoc.callshield.data.model.SmsKeywordRule
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.WhitelistEntry
import com.sysadmindoc.callshield.data.model.WildcardRule
import com.sysadmindoc.callshield.data.resolveSpamNumberForWhitelist
import com.sysadmindoc.callshield.service.NotificationHelper
import com.sysadmindoc.callshield.ui.widget.CallShieldWidget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Suppress("TooManyFunctions", "LongParameterList")
class BlocklistRepository(
    private val context: Context,
    private val dao: SpamDao,
    private val settingsRepository: SettingsRepository,
    private val normalizeNumber: (String) -> String,
    private val invalidateWildcardCache: () -> Unit,
    private val invalidateKeywordCache: () -> Unit,
    private val invalidateHashWildcardCache: () -> Unit,
) {
    private companion object {
        const val MAX_SCHEDULE_HOUR = 23
        const val MAX_HASH_WILDCARD_PATTERN_LENGTH = 30
        const val MIN_CLEANUP_DAYS = 7
        const val MILLIS_PER_DAY = 86_400_000L
        const val PENDING_LOG_BATCH_LIMIT = 50
        const val PENDING_LOG_RETRY_DELAY_MS = 60_000L
    }

    suspend fun blockNumber(
        number: String,
        type: String = "unknown",
        description: String = "",
        expiresAt: Long? = null,
    ) {
        val normalized = normalizeNumber(number)
        if (normalized.isBlank()) return
        cleanupExpiredTemporaryDecisions()
        val existingWhitelist = dao.findWhitelistEntry(normalized)
        val permanentAllowExists = expiresAt != null && existingWhitelist != null && existingWhitelist.expiresAt == null
        if (permanentAllowExists) return

        existingWhitelist?.let { dao.deleteWhitelistEntry(it) }
        when (val existing = dao.findByNumber(normalized)) {
            null -> {
                dao.insertNumber(
                    SpamNumber(
                        number = normalized,
                        type = type.trim().ifBlank { "unknown" },
                        description = description.trim(),
                        source = "user",
                        isUserBlocked = true,
                        expiresAt = expiresAt,
                    ),
                )
            }

            else -> {
                val permanentBlockExists = expiresAt != null && existing.isUserBlocked && existing.expiresAt == null
                if (!permanentBlockExists) {
                    dao.insertNumber(
                        existing.copy(
                            type = type.trim().ifBlank { existing.type },
                            description = description.trim().ifBlank { existing.description },
                            isUserBlocked = true,
                            expiresAt = expiresAt,
                        ),
                    )
                }
            }
        }
    }

    suspend fun temporaryBlockNumber(
        number: String,
        expiresAt: Long,
        type: String = "unknown",
        description: String = "",
    ) = blockNumber(number, type, description, expiresAt)

    suspend fun temporaryAllowNumber(
        number: String,
        expiresAt: Long,
        description: String = "",
    ) = addToWhitelist(number, description, isEmergency = false, expiresAt = expiresAt)

    suspend fun unblockNumber(number: SpamNumber) {
        if (number.source == "user") {
            dao.deleteNumber(number)
        } else {
            dao.insertNumber(number.copy(isUserBlocked = false, expiresAt = null))
        }
    }

    fun getAllWildcardRules(): Flow<List<WildcardRule>> = dao.getAllWildcardRules()

    suspend fun addWildcardRule(
        pattern: String,
        isRegex: Boolean = false,
        description: String = "",
        schedule: TimeSchedule = TimeSchedule(),
    ) {
        val trimmedPattern = pattern.trim()
        if (trimmedPattern.isBlank()) return
        dao.insertWildcardRule(
            WildcardRule(
                pattern = trimmedPattern,
                isRegex = isRegex,
                description = description.trim(),
                scheduleDays = schedule.daysMask,
                scheduleStartHour = schedule.startHour.coerceIn(0, MAX_SCHEDULE_HOUR),
                scheduleEndHour = schedule.endHour.coerceIn(0, MAX_SCHEDULE_HOUR),
            ),
        )
        invalidateWildcardCache()
    }

    suspend fun deleteWildcardRule(rule: WildcardRule) {
        dao.deleteWildcardRule(rule)
        invalidateWildcardCache()
    }

    suspend fun toggleWildcardRule(
        id: Long,
        enabled: Boolean,
    ) {
        dao.setWildcardRuleEnabled(id, enabled)
        invalidateWildcardCache()
    }

    fun getAllHashWildcardRules(): Flow<List<HashWildcardRule>> = dao.getAllHashWildcardRules()

    suspend fun addHashWildcardRule(
        pattern: String,
        description: String = "",
        schedule: TimeSchedule = TimeSchedule(),
    ): Boolean {
        val trimmed = pattern.trim()
        if (trimmed.isBlank() || trimmed.length > MAX_HASH_WILDCARD_PATTERN_LENGTH) return false
        dao.insertHashWildcardRule(
            HashWildcardRule(
                pattern = trimmed,
                description = description.trim(),
                scheduleDays = schedule.daysMask,
                scheduleStartHour = schedule.startHour.coerceIn(0, MAX_SCHEDULE_HOUR),
                scheduleEndHour = schedule.endHour.coerceIn(0, MAX_SCHEDULE_HOUR),
            ),
        )
        invalidateHashWildcardCache()
        return true
    }

    suspend fun deleteHashWildcardRule(rule: HashWildcardRule) {
        dao.deleteHashWildcardRule(rule)
        invalidateHashWildcardCache()
    }

    suspend fun toggleHashWildcardRule(
        id: Long,
        enabled: Boolean,
    ) {
        dao.setHashWildcardRuleEnabled(id, enabled)
        invalidateHashWildcardCache()
    }

    suspend fun logBlockedCall(
        number: String,
        isCall: Boolean = true,
        smsBody: String? = null,
        matchReason: String = "",
        confidence: Int = 100,
        timestamp: Long = System.currentTimeMillis(),
        logKey: String? = null,
    ) {
        val normalizedNumber = normalizeNumber(number)
        val inserted =
            dao.insertBlockedCallIgnoringDuplicate(
                BlockedCall(
                    number = normalizedNumber,
                    timestamp = timestamp,
                    isCall = isCall,
                    smsBody = smsBody,
                    matchReason = matchReason,
                    confidence = confidence,
                    logKey = logKey,
                ),
            )
        if (inserted == -1L) return
        CallShieldWidget.refreshAll(context)
        NotificationHelper.notifyBlocked(context, number, matchReason, isCall, smsBody)
    }

    suspend fun enqueuePendingBlockedCallLog(
        idempotencyKey: String,
        number: String,
        isCall: Boolean = true,
        smsBody: String? = null,
        matchReason: String = "",
        confidence: Int = 100,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        dao.insertPendingBlockedCallLog(
            PendingBlockedCallLog(
                idempotencyKey = idempotencyKey,
                number = normalizeNumber(number),
                timestamp = timestamp,
                isCall = isCall,
                smsBody = smsBody,
                matchReason = matchReason,
                confidence = confidence,
            ),
        )
    }

    suspend fun flushPendingBlockedCallLogs(
        now: Long = System.currentTimeMillis(),
        limit: Int = PENDING_LOG_BATCH_LIMIT,
    ): Int {
        var consumed = 0
        val pendingLogs = dao.getReadyPendingBlockedCallLogs(now, limit)
        for (pendingLog in pendingLogs) {
            try {
                val inserted = dao.consumePendingBlockedCallLog(pendingLog)
                if (inserted != -1L) {
                    CallShieldWidget.refreshAll(context)
                    NotificationHelper.notifyBlocked(
                        context = context,
                        number = pendingLog.number,
                        reason = pendingLog.matchReason,
                        isCall = pendingLog.isCall,
                        smsBody = pendingLog.smsBody,
                    )
                }
                consumed++
            } catch (_: Exception) {
                dao.markPendingBlockedCallLogFailed(
                    idempotencyKey = pendingLog.idempotencyKey,
                    nextAttemptAt = now + PENDING_LOG_RETRY_DELAY_MS,
                )
            }
        }
        return consumed
    }

    suspend fun getPendingBlockedCallLogCount(): Int = dao.getPendingBlockedCallLogCount()

    suspend fun insertBlockedCall(call: BlockedCall) =
        dao.insertBlockedCall(
            BlockedCall(
                id = call.id,
                number = normalizeNumber(call.number),
                timestamp = call.timestamp,
                type = call.type,
                wasBlocked = call.wasBlocked,
                isCall = call.isCall,
                smsBody = call.smsBody,
                matchReason = call.matchReason,
                confidence = call.confidence,
                logKey = call.logKey,
            ),
        )

    fun getBlockedCalls(): Flow<List<BlockedCall>> = dao.getBlockedCalls()

    fun getBlockedCallsOnly(): Flow<List<BlockedCall>> = dao.getBlockedCallsOnly()

    fun getBlockedSmsOnly(): Flow<List<BlockedCall>> = dao.getBlockedSmsOnly()

    fun getTotalBlockedCount(): Flow<Int> = dao.getTotalBlockedCount()

    fun getBlockedCountSince(since: Long): Flow<Int> = dao.getBlockedCountSince(since)

    fun getBlockedCountBetween(
        start: Long,
        end: Long,
    ): Flow<Int> = dao.getBlockedCountBetween(start, end)

    fun getAllSpamNumbers(): Flow<List<SpamNumber>> = dao.getAllSpamNumbers()

    fun getUserBlockedNumbers(): Flow<List<SpamNumber>> =
        dao.getUserBlockedNumbers().map { rows ->
            val now = System.currentTimeMillis()
            rows.mapNotNull { it.activeDecision(now) }.filter { it.isUserBlocked }
        }

    suspend fun getSpamCount(): Int = dao.getSpamCount()

    suspend fun clearCallLog() = dao.clearCallLog()

    suspend fun deleteBlockedCall(call: BlockedCall) = dao.deleteBlockedCall(call)

    fun searchNumbers(query: String): Flow<List<SpamNumber>> = dao.searchNumbers(escapeLikeQuery(query))

    fun getAllWhitelist(): Flow<List<WhitelistEntry>> =
        dao.getAllWhitelist().map { rows ->
            val now = System.currentTimeMillis()
            rows.filterNot { it.isExpired(now) }
        }

    fun getEmergencyContacts(): Flow<List<WhitelistEntry>> =
        dao.getEmergencyContacts().map { rows ->
            val now = System.currentTimeMillis()
            rows.filterNot { it.isExpired(now) }
        }

    suspend fun addToWhitelist(
        number: String,
        description: String = "",
        isEmergency: Boolean = false,
        expiresAt: Long? = null,
    ) {
        val normalized = normalizeNumber(number)
        if (normalized.isBlank()) return
        cleanupExpiredTemporaryDecisions()
        val existingSpam = dao.findByNumber(normalized)
        val permanentUserBlock = existingSpam?.isUserBlocked == true && existingSpam.expiresAt == null
        if (expiresAt != null && permanentUserBlock) return
        if (expiresAt == null || existingSpam?.expiresAt != null) {
            when (val resolution = resolveSpamNumberForWhitelist(existingSpam)) {
                SpamNumberWhitelistResolution.None -> Unit
                is SpamNumberWhitelistResolution.Update -> dao.insertNumber(resolution.number)
                is SpamNumberWhitelistResolution.Delete -> dao.deleteNumber(resolution.number)
            }
        }
        dao.insertWhitelistEntry(
            WhitelistEntry(
                number = normalized,
                description = description.trim(),
                isEmergency = isEmergency && expiresAt == null,
                expiresAt = expiresAt,
            ),
        )
    }

    suspend fun removeFromWhitelist(entry: WhitelistEntry) = dao.deleteWhitelistEntry(entry)

    suspend fun setWhitelistEmergency(
        id: Long,
        emergency: Boolean,
    ) = dao.setWhitelistEmergency(id, emergency)

    suspend fun cleanupOldLogs() {
        cleanupExpiredTemporaryDecisions()
        if (settingsRepository.autoCleanupEnabled.first()) {
            val days = settingsRepository.cleanupDays.first().coerceAtLeast(MIN_CLEANUP_DAYS)
            val cutoff = System.currentTimeMillis() - days * MILLIS_PER_DAY
            dao.deleteLogOlderThan(cutoff)
        }
    }

    suspend fun cleanupExpiredTemporaryDecisions(
        now: Long = System.currentTimeMillis(),
    ): Int {
        val removedUserBlocks = dao.deleteExpiredUserOwnedBlocks(now)
        val clearedSyncedFlags = dao.clearExpiredSyncedUserBlockFlags(now)
        val removedWhitelistRows = dao.deleteExpiredWhitelistEntries(now)
        return removedUserBlocks + clearedSyncedFlags + removedWhitelistRows
    }

    fun getAllKeywordRules(): Flow<List<SmsKeywordRule>> = dao.getAllKeywordRules()

    suspend fun addKeywordRule(
        keyword: String,
        caseSensitive: Boolean = false,
        description: String = "",
        schedule: TimeSchedule = TimeSchedule(),
    ) {
        val trimmedKeyword = keyword.trim()
        if (trimmedKeyword.isBlank()) return
        dao.insertKeywordRule(
            SmsKeywordRule(
                keyword = trimmedKeyword,
                caseSensitive = caseSensitive,
                description = description.trim(),
                scheduleDays = schedule.daysMask,
                scheduleStartHour = schedule.startHour.coerceIn(0, MAX_SCHEDULE_HOUR),
                scheduleEndHour = schedule.endHour.coerceIn(0, MAX_SCHEDULE_HOUR),
            ),
        )
        invalidateKeywordCache()
    }

    suspend fun deleteKeywordRule(rule: SmsKeywordRule) {
        dao.deleteKeywordRule(rule)
        invalidateKeywordCache()
    }

    suspend fun toggleKeywordRule(
        id: Long,
        enabled: Boolean,
    ) {
        dao.setKeywordRuleEnabled(id, enabled)
        invalidateKeywordCache()
    }

    fun searchLog(query: String): Flow<List<BlockedCall>> = dao.searchLog(escapeLikeQuery(query))
}
