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
import com.sysadmindoc.callshield.util.filterAsciiDigits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Suppress("TooManyFunctions", "LongParameterList")
class BlocklistRepository(
    private val context: Context,
    private val dao: SpamDao,
    private val settingsRepository: SettingsRepository,
    private val normalizeNumber: (String) -> String,
    private val normalizeLogIdentity: (String) -> String,
    private val invalidateWildcardCache: () -> Unit,
    private val invalidateKeywordCache: () -> Unit,
    private val invalidateHashWildcardCache: () -> Unit,
    private val runInTransaction: suspend (suspend () -> Unit) -> Unit,
) {
    private companion object {
        const val MAX_SCHEDULE_HOUR = 23
        const val MAX_HASH_WILDCARD_PATTERN_LENGTH = 30
        const val MIN_CLEANUP_DAYS = 7
        const val MILLIS_PER_DAY = 86_400_000L
        const val PENDING_LOG_BATCH_LIMIT = 50
        const val PENDING_LOG_RETRY_DELAY_MS = 60_000L
        const val MIN_SEARCH_DIGITS = 4
    }

    /** @return true when the number is blocked afterwards; false when refused (invalid input or a permanent allow wins). */
    suspend fun blockNumber(
        number: String,
        type: String = "unknown",
        description: String = "",
        expiresAt: Long? = null,
    ): Boolean {
        val normalized = normalizeNumber(number)
        if (normalized.isBlank()) return false
        // Atomic check-then-act: without the transaction a process death between
        // the whitelist delete and the block insert would strip the user's allow
        // without adding the block, and a concurrent addToWhitelist could
        // interleave to leave both rows present.
        var blocked = false
        runInTransaction {
            cleanupExpiredTemporaryDecisions()
            val existingWhitelist = dao.findWhitelistEntry(normalized)
            val permanentAllowExists = expiresAt != null && existingWhitelist != null && existingWhitelist.expiresAt == null
            if (!permanentAllowExists) {
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
                blocked = true
            }
        }
        return blocked
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

    /** Re-insert a previously-removed block row verbatim (undo). */
    suspend fun restoreBlockedNumber(number: SpamNumber) = dao.insertNumber(number)

    /** Undo a block by number string (e.g. an accidental blocked-log swipe). */
    suspend fun unblockByNumber(number: String) {
        val normalized = normalizeNumber(number)
        dao.findByNumber(normalized)?.let { unblockNumber(it) }
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
        val normalizedNumber = normalizeLogIdentity(number)
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
                number = normalizeLogIdentity(number),
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
                number = normalizeLogIdentity(call.number),
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

    fun observeSpamCount(): Flow<Int> = dao.observeSpamCount()

    suspend fun clearCallLog() = dao.clearCallLog()

    suspend fun deleteBlockedCall(call: BlockedCall) = dao.deleteBlockedCall(call)

    fun searchNumbers(query: String): Flow<List<SpamNumber>> {
        // Numbers are stored canonical (+E.164). Also search the digit-stripped
        // form so a user-typed "555-123-4567" / "(555) 123-4567" matches the
        // stored "+15551234567". Only when the query is meaningfully phone-like,
        // to avoid a stray digit in a text query widening results.
        val digits = filterAsciiDigits(query)
        val digitsQuery = if (digits.length >= MIN_SEARCH_DIGITS) digits else ""
        return dao.searchNumbers(escapeLikeQuery(query), digitsQuery)
    }

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

    /** @return true when the number is whitelisted afterwards; false when refused (invalid input or a permanent block wins). */
    suspend fun addToWhitelist(
        number: String,
        description: String = "",
        isEmergency: Boolean = false,
        expiresAt: Long? = null,
    ): Boolean {
        val normalized = normalizeNumber(number)
        if (normalized.isBlank()) return false
        // Atomic check-then-act — see blockNumber. Prevents a half-applied trust
        // change on process death and interleaving with a concurrent block.
        var whitelisted = false
        runInTransaction {
            cleanupExpiredTemporaryDecisions()
            val existingSpam = dao.findByNumber(normalized)
            val permanentUserBlock = existingSpam?.isUserBlocked == true && existingSpam.expiresAt == null
            if (!(expiresAt != null && permanentUserBlock)) {
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
                whitelisted = true
            }
        }
        return whitelisted
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
