package com.sysadmindoc.callshield.data.repository

import android.content.Context
import androidx.paging.PagingSource
import com.sysadmindoc.callshield.data.EmergencyNumberFloor
import com.sysadmindoc.callshield.data.SpamNumberWhitelistResolution
import com.sysadmindoc.callshield.data.TimeSchedule
import com.sysadmindoc.callshield.data.escapeLikeQuery
import com.sysadmindoc.callshield.data.local.SpamDao
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.data.model.BlockedCallGroup
import com.sysadmindoc.callshield.data.model.HashWildcardRule
import com.sysadmindoc.callshield.data.model.LogAggregate
import com.sysadmindoc.callshield.data.model.PendingBlockedCallLog
import com.sysadmindoc.callshield.data.model.SmsKeywordRule
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.SpamPrefix
import com.sysadmindoc.callshield.data.model.WhitelistEntry
import com.sysadmindoc.callshield.data.model.WildcardRule
import com.sysadmindoc.callshield.data.resolveSpamNumberForWhitelist
import com.sysadmindoc.callshield.domain.model.BlockReasonCode
import com.sysadmindoc.callshield.service.NotificationHelper
import com.sysadmindoc.callshield.ui.widget.CallShieldWidget
import com.sysadmindoc.callshield.util.filterAsciiDigits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Both screening paths see one text within seconds; a minute covers a slow listener. */
internal const val TEXT_DUPLICATE_WINDOW_MS = 60_000L

@Suppress("TooManyFunctions", "LongParameterList")
class BlocklistRepository(
    private val context: Context,
    private val dao: SpamDao,
    private val settingsRepository: SettingsRepository,
    private val normalizeNumber: (String) -> String,
    private val normalizeLogIdentity: (String) -> String,
    /**
     * Every stored spelling of a canonical number, canonical first. Screening
     * matches all of them, so an edit that only touched one form could leave
     * an older allow beating a newer block, or a removed block still in force.
     */
    private val equivalentForms: (String) -> List<String> = { listOf(it) },
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

        // One SQL variable each, and older Android allows 999; the published hot
        // list stops at 500.
        const val MAX_TRENDING_FILTER = 900
    }

    /** @return true when the number is blocked afterwards; false when refused (invalid input or a permanent allow wins). */
    suspend fun blockNumber(
        number: String,
        type: String = "unknown",
        description: String = "",
        expiresAt: Long? = null,
        // Bulk importers run their own single upfront cleanup — repeating the
        // 3 UPDATE/DELETE sweeps per row turns a 100k-row import into ~300k
        // extra statements inside the write lock.
        cleanupExpired: Boolean = true,
    ): Boolean {
        val normalized = normalizeNumber(number)
        if (normalized.isBlank()) return false
        if (EmergencyNumberFloor.isProtected(normalized)) return false
        // Atomic check-then-act: without the transaction a process death between
        // the whitelist delete and the block insert would strip the user's allow
        // without adding the block, and a concurrent addToWhitelist could
        // interleave to leave both rows present.
        var blocked = false
        runInTransaction {
            if (cleanupExpired) cleanupExpiredTemporaryDecisions()
            val existingWhitelist = equivalentForms(normalized).mapNotNull { dao.findWhitelistEntry(it) }
            val permanentAllowExists = expiresAt != null && existingWhitelist.any { it.expiresAt == null }
            if (!permanentAllowExists) {
                existingWhitelist.forEach { dao.deleteWhitelistEntry(it) }
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
    suspend fun restoreBlockedNumber(number: SpamNumber): Boolean {
        if (EmergencyNumberFloor.isProtected(number.number) && number.isUserBlocked) return false
        dao.insertNumber(number)
        return true
    }

    /** Remove the user's block of a number, in every spelling it could be saved under. */
    suspend fun unblockByNumber(number: String) {
        val normalized = normalizeNumber(number)
        if (normalized.isBlank()) return
        equivalentForms(normalized)
            .mapNotNull { dao.findByNumber(it) }
            .filter { it.isUserBlocked }
            .forEach { unblockNumber(it) }
    }

    /** What one block replaced: the row it created or changed, and the allows it removed. */
    data class BlockUndo(
        val number: String,
        val previous: SpamNumber?,
        val removedAllows: List<WhitelistEntry>,
    )

    /**
     * Block the way [blockNumber] does and return what the block replaced, so
     * an Undo puts back exactly that. Removing the block by number instead
     * would also clear the user's own earlier block in another spelling, drop
     * a note they'd saved on it, and leave the allows it removed deleted.
     */
    suspend fun blockNumberUndoable(
        number: String,
        type: String = "unknown",
        description: String = "",
    ): BlockUndo? {
        val normalized = normalizeNumber(number)
        if (normalized.isBlank()) return null
        var undo: BlockUndo? = null
        runInTransaction {
            val previous = dao.findByNumber(normalized)
            val now = System.currentTimeMillis()
            val allows =
                equivalentForms(normalized)
                    .mapNotNull { dao.findWhitelistEntry(it) }
                    .filter { it.expiresAt == null || it.expiresAt > now }
            if (blockNumber(number, type, description)) undo = BlockUndo(normalized, previous, allows)
        }
        return undo
    }

    /** Put back what [blockNumberUndoable] replaced. */
    suspend fun undoBlock(undo: BlockUndo) {
        runInTransaction {
            val previous = undo.previous
            if (previous != null) {
                dao.insertNumber(previous)
            } else {
                dao.findByNumber(undo.number)?.takeIf { it.isUserBlocked }?.let { unblockNumber(it) }
            }
            undo.removedAllows.forEach { dao.insertWhitelistEntry(it) }
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
        ruleId: Long? = null,
        pipelineDiagnostic: String? = null,
        origid: String? = null,
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
                    ruleId = ruleId,
                    reasonCode = BlockReasonCode.fromMatchSource(matchReason),
                    pipelineDiagnostic = pipelineDiagnostic,
                    origid = origid,
                ),
            )
        if (inserted == -1L) return
        CallShieldWidget.refreshAll(context)
        NotificationHelper.notifyBlocked(context, number, matchReason, isCall, smsBody)
    }

    private val textLogLock = Mutex()

    /**
     * Logs a flagged text once. Google Messages and Samsung Messages are read
     * by both the SMS receiver and the notification listener, so one text
     * arrives twice, by paths that see it differently: a notification
     * truncates the body, number formats differ, and the listener's reason
     * carries an rcs_ prefix. So the key is the canonical sender inside
     * [TEXT_DUPLICATE_WINDOW_MS], never the body. The lock stops two
     * sightings that arrive together from both passing the check.
     *
     * @return true when this sighting was logged, false when it repeated one.
     */
    suspend fun logFlaggedText(
        number: String,
        smsBody: String?,
        matchReason: String,
        confidence: Int,
        ruleId: Long? = null,
        pipelineDiagnostic: String? = null,
        timestamp: Long = System.currentTimeMillis(),
    ): Boolean =
        textLogLock.withLock {
            val sender = normalizeLogIdentity(number)
            if (dao.countFlaggedTextsSince(sender, timestamp - TEXT_DUPLICATE_WINDOW_MS) > 0) return@withLock false
            logBlockedCall(
                number = number,
                isCall = false,
                smsBody = smsBody,
                matchReason = matchReason,
                confidence = confidence,
                timestamp = timestamp,
                ruleId = ruleId,
                pipelineDiagnostic = pipelineDiagnostic,
            )
            true
        }

    /**
     * Retain an allowed safety-floor decision for auditability without
     * presenting it as a block or sending a blocked notification.
     */
    suspend fun logScreeningExemption(
        number: String,
        isCall: Boolean = false,
        smsBody: String? = null,
        matchReason: String,
        type: String = "safety_floor",
        confidence: Int = 100,
        timestamp: Long = System.currentTimeMillis(),
        ruleId: Long? = null,
        pipelineDiagnostic: String? = null,
    ) {
        val normalizedNumber = normalizeLogIdentity(number)
        val inserted =
            dao.insertBlockedCallIgnoringDuplicate(
                BlockedCall(
                    number = normalizedNumber,
                    timestamp = timestamp,
                    type = type,
                    wasBlocked = false,
                    isCall = isCall,
                    smsBody = smsBody,
                    matchReason = matchReason,
                    confidence = confidence,
                    ruleId = ruleId,
                    reasonCode = BlockReasonCode.fromMatchSource(matchReason),
                    pipelineDiagnostic = pipelineDiagnostic,
                ),
            )
        if (inserted != -1L) CallShieldWidget.refreshAll(context)
    }

    /**
     * Retain a fail-open decision whose checker chain was incomplete. This is
     * deliberately a non-blocked log row: the user should see that protection
     * degraded without mistaking the diagnostic for a spam verdict.
     */
    suspend fun logScreeningDiagnostic(
        number: String,
        isCall: Boolean = true,
        smsBody: String? = null,
        pipelineDiagnostic: String,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        val normalizedNumber = normalizeLogIdentity(number)
        val inserted =
            dao.insertBlockedCallIgnoringDuplicate(
                BlockedCall(
                    number = normalizedNumber,
                    timestamp = timestamp,
                    type = "screening_diagnostic",
                    wasBlocked = false,
                    isCall = isCall,
                    smsBody = smsBody,
                    matchReason = BlockReasonCode.PIPELINE_DIAGNOSTIC.wireValue,
                    confidence = 0,
                    logKey = "screening-diagnostic|$normalizedNumber|$timestamp|${pipelineDiagnostic.hashCode()}",
                    reasonCode = BlockReasonCode.PIPELINE_DIAGNOSTIC,
                    pipelineDiagnostic = pipelineDiagnostic,
                ),
            )
        if (inserted != -1L) CallShieldWidget.refreshAll(context)
    }

    suspend fun enqueuePendingBlockedCallLog(
        idempotencyKey: String,
        number: String,
        isCall: Boolean = true,
        smsBody: String? = null,
        matchReason: String = "",
        confidence: Int = 100,
        timestamp: Long = System.currentTimeMillis(),
        ruleId: Long? = null,
        pipelineDiagnostic: String? = null,
        origid: String? = null,
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
                ruleId = ruleId,
                reasonCode = BlockReasonCode.fromMatchSource(matchReason),
                pipelineDiagnostic = pipelineDiagnostic,
                origid = origid,
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
                ruleId = call.ruleId,
                reasonCode = call.reasonCode,
                pipelineDiagnostic = call.pipelineDiagnostic,
                origid = call.origid,
            ),
        )

    fun getBlockedCalls(): Flow<List<BlockedCall>> = dao.getBlockedCalls()

    suspend fun readBlockedCallsBatch(
        beforeTimestamp: Long,
        beforeId: Long,
        limit: Int,
    ): List<BlockedCall> = dao.readBlockedCallsBatch(beforeTimestamp, beforeId, limit)

    fun observeBlockedCallsForNumber(number: String): Flow<List<BlockedCall>> = dao.observeBlockedCallsForNumber(number)

    fun observeRecentLog(limit: Int): Flow<List<BlockedCall>> = dao.observeRecentLog(limit)

    fun pageBlockedCalls(
        isCall: Int?,
        reasonCode: String?,
    ): PagingSource<Int, BlockedCall> = dao.pageBlockedCalls(isCall, reasonCode)

    fun pageGroupedBlockedCalls(
        isCall: Int?,
        reasonCode: String?,
    ): PagingSource<Int, BlockedCallGroup> = dao.pageGroupedBlockedCalls(isCall, reasonCode)

    fun observeLogReasonCodes(): Flow<List<String>> = dao.observeLogReasonCodes()

    fun observeLogCount(): Flow<Int> = dao.observeLogCount()

    fun observeLogCallCount(): Flow<Int> = dao.observeLogCallCount()

    fun observeLogSmsCount(): Flow<Int> = dao.observeLogSmsCount()

    fun observeLogReasonCounts(): Flow<List<LogAggregate>> = dao.observeLogReasonCounts()

    fun observeLogHourCounts(): Flow<List<LogAggregate>> = dao.observeLogHourCounts()

    fun observeLogDayCounts(since: Long): Flow<List<LogAggregate>> = dao.observeLogDayCounts(since)

    fun observeLogNumberCounts(limit: Int): Flow<List<LogAggregate>> = dao.observeLogNumberCounts(limit)

    fun observeLogAreaCodeCounts(limit: Int): Flow<List<LogAggregate>> = dao.observeLogAreaCodeCounts(limit)

    fun observeLogCountBetween(
        start: Long,
        end: Long,
    ): Flow<Int> = dao.observeLogCountBetween(start, end)

    fun getBlockedCallsByReasonCode(reasonCode: BlockReasonCode): Flow<List<BlockedCall>> = dao.getBlockedCallsByReasonCode(reasonCode)

    fun getBlockedCallsOnly(): Flow<List<BlockedCall>> = dao.getBlockedCallsOnly()

    fun getBlockedSmsOnly(): Flow<List<BlockedCall>> = dao.getBlockedSmsOnly()

    fun getTotalBlockedCount(): Flow<Int> = dao.getTotalBlockedCount()

    fun getBlockedCountSince(since: Long): Flow<Int> = dao.getBlockedCountSince(since)

    fun getBlockedCountSinceByType(
        since: Long,
        isCall: Boolean,
    ): Flow<Int> = dao.getBlockedCountSinceByType(since, isCall)

    fun getBlockedCountBetween(
        start: Long,
        end: Long,
    ): Flow<Int> = dao.getBlockedCountBetween(start, end)

    fun getAllSpamNumbers(): Flow<List<SpamNumber>> = dao.getAllSpamNumbers()

    fun observeNumber(number: String): Flow<SpamNumber?> = dao.observeNumber(number)

    fun pageSpamNumbers(
        type: String,
        source: String,
        trending: Collection<String> = emptyList(),
    ): PagingSource<Int, SpamNumber> =
        // Sorted first, so a list over the limit keeps the same numbers whatever order its set iterates in.
        dao.pageSpamNumbers(type, source, trending.sorted().take(MAX_TRENDING_FILTER))

    fun getUserBlockedNumbers(): Flow<List<SpamNumber>> =
        dao.getUserBlockedNumbers().map { rows ->
            val now = System.currentTimeMillis()
            rows.mapNotNull { it.activeDecision(now) }.filter { it.isUserBlocked }
        }

    suspend fun getSpamCount(): Int = dao.getSpamCount()

    fun observeSpamCount(): Flow<Int> = dao.observeSpamCount()

    suspend fun clearCallLog() = dao.clearCallLog()

    suspend fun deleteBlockedCall(call: BlockedCall) = dao.deleteBlockedCall(call)

    fun pageSearchNumbers(query: String): PagingSource<Int, SpamNumber> {
        val (escaped, digitsQuery) = searchArguments(query)
        return dao.pageSearchNumbers(escaped, digitsQuery)
    }

    fun observeSearchCount(query: String): Flow<Int> {
        val (escaped, digitsQuery) = searchArguments(query)
        return dao.observeSearchCount(escaped, digitsQuery)
    }

    private fun searchArguments(query: String): Pair<String, String> {
        // Numbers are stored canonical (+E.164). Also search the digit-stripped
        // form so a user-typed "555-123-4567" / "(555) 123-4567" matches the
        // stored "+15551234567". Only when the query is meaningfully phone-like,
        // to avoid a stray digit in a text query widening results.
        val digits = filterAsciiDigits(query)
        val digitsQuery = if (digits.length >= MIN_SEARCH_DIGITS) digits else ""
        return escapeLikeQuery(query) to digitsQuery
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
            val existingSpam = equivalentForms(normalized).mapNotNull { dao.findByNumber(it) }
            val permanentUserBlock = existingSpam.any { it.isUserBlocked && it.expiresAt == null }
            if (!(expiresAt != null && permanentUserBlock)) {
                existingSpam
                    .filter { expiresAt == null || it.expiresAt != null }
                    .forEach { row ->
                        when (val resolution = resolveSpamNumberForWhitelist(row)) {
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

    fun observeAllPrefixes(): Flow<List<SpamPrefix>> = dao.observeAllPrefixes()

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
