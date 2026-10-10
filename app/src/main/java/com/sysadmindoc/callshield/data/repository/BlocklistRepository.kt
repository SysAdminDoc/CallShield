package com.sysadmindoc.callshield.data.repository

import android.content.Context
import androidx.paging.PagingSource
import com.sysadmindoc.callshield.data.EmergencyNumberFloor
import com.sysadmindoc.callshield.data.PhoneIdentityCanonicalizer
import com.sysadmindoc.callshield.data.SmsContentAnalyzer
import com.sysadmindoc.callshield.data.SpamNumberWhitelistResolution
import com.sysadmindoc.callshield.data.TimeSchedule
import com.sysadmindoc.callshield.data.escapeLikeQuery
import com.sysadmindoc.callshield.data.local.SpamDao
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.data.model.BlockedCallGroup
import com.sysadmindoc.callshield.data.model.FlaggedTextNumber
import com.sysadmindoc.callshield.data.model.HashWildcardRule
import com.sysadmindoc.callshield.data.model.LogAggregate
import com.sysadmindoc.callshield.data.model.NumberSighting
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

private val WHITESPACE_RUN = Regex("\\s+")

/**
 * Whether two sightings of a flagged text can be the same message. Only the
 * other path's copy can be cut short or empty: the notification listener
 * sees a body the notification may truncate or hide (an encrypted RCS
 * placeholder), so across paths a missing body, or one the other starts
 * with, matches. Two sightings on the same path match only when their text
 * does, so "Hi" and a later "Hi, your parcel is held" stay two texts.
 */
internal fun sameFlaggedText(
    first: String?,
    second: String?,
    samePath: Boolean = false,
): Boolean {
    fun comparable(body: String?) =
        body
            .orEmpty()
            .trim()
            .removeSuffix("…")
            .removeSuffix("...")
            .trim()
            .replace(WHITESPACE_RUN, " ")
    val a = comparable(first)
    val b = comparable(second)
    if (samePath) return a == b
    return a.isEmpty() || b.isEmpty() || a.startsWith(b) || b.startsWith(a)
}

/** The notification listener logs its sightings under an rcs_ reason. */
private fun fromListener(matchReason: String) = matchReason.startsWith("rcs_")

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
        const val FLAGGED_TEXT_NUMBER_TTL_MS = 30L * MILLIS_PER_DAY
        const val MAX_FLAGGED_TEXT_NUMBERS = 500

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

    /** A number's block and allow rows in every spelling, as they were before a change. */
    data class DecisionSnapshot(
        val number: String,
        val blocked: List<SpamNumber>,
        val allowed: List<WhitelistEntry>,
    )

    private suspend fun snapshotDecision(normalized: String): DecisionSnapshot {
        val forms = equivalentForms(normalized)
        return DecisionSnapshot(
            number = normalized,
            blocked = forms.mapNotNull { dao.findByNumber(it) },
            allowed = forms.mapNotNull { dao.findWhitelistEntry(it) },
        )
    }

    /**
     * A temporary block or allow that returns what it replaced, or null when it
     * changed nothing (a permanent rule of the other kind wins, or the number
     * can't be blocked).
     */
    suspend fun temporaryDecisionUndoable(
        number: String,
        allow: Boolean,
        expiresAt: Long,
        type: String = "unknown",
        description: String = "",
    ): DecisionSnapshot? {
        val normalized = normalizeNumber(number)
        if (normalized.isBlank()) return null
        var snapshot: DecisionSnapshot? = null
        runInTransaction {
            val before = snapshotDecision(normalized)
            val changed =
                if (allow) {
                    temporaryAllowNumber(number, expiresAt, description)
                } else {
                    temporaryBlockNumber(number, expiresAt, type, description)
                }
            if (changed) snapshot = before
        }
        return snapshot
    }

    /** Put a number's rows back the way [temporaryDecisionUndoable] found them. */
    suspend fun restoreDecision(snapshot: DecisionSnapshot) {
        runInTransaction {
            equivalentForms(snapshot.number).forEach { form ->
                val blockedBefore = snapshot.blocked.firstOrNull { it.number == form }
                val blockedNow = dao.findByNumber(form)
                when {
                    blockedBefore != null -> dao.insertNumber(blockedBefore)
                    blockedNow != null && blockedNow.isUserBlocked -> unblockNumber(blockedNow)
                }
                val allowedBefore = snapshot.allowed.firstOrNull { it.number == form }
                val allowedNow = dao.findWhitelistEntry(form)
                when {
                    allowedBefore != null -> dao.insertWhitelistEntry(allowedBefore)
                    allowedNow != null -> dao.deleteWhitelistEntry(allowedNow)
                }
            }
        }
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

    /** What adding a wildcard rule replaced: the rule that had the same pattern, if any. */
    data class WildcardUndo(
        val pattern: String,
        val previous: WildcardRule?,
        /** False when an always-on rule for the pattern was already there; nothing was written. */
        val changed: Boolean = true,
    )

    /** Add a rule the way [addWildcardRule] does and return what it replaced. */
    suspend fun addWildcardRuleUndoable(
        pattern: String,
        isRegex: Boolean = false,
        description: String = "",
    ): WildcardUndo? {
        val trimmedPattern = pattern.trim()
        if (trimmedPattern.isBlank()) return null
        var undo: WildcardUndo? = null
        runInTransaction {
            val existing = dao.findWildcardRule(trimmedPattern)
            // A repeat tap keeps the first rule, so the first Undo still
            // removes it instead of putting back a copy of itself.
            val alreadyBlocks = existing != null && existing.enabled && existing.isRegex == isRegex && existing.scheduleDays == 0
            undo = WildcardUndo(trimmedPattern, existing, changed = !alreadyBlocks)
            if (!alreadyBlocks) addWildcardRule(trimmedPattern, isRegex, description)
        }
        return undo
    }

    /** Put back the rule [addWildcardRuleUndoable] replaced, or remove the one it added. */
    suspend fun undoWildcardRule(undo: WildcardUndo) {
        runInTransaction {
            val previous = undo.previous
            if (previous != null) {
                dao.insertWildcardRule(previous)
            } else {
                dao.findWildcardRule(undo.pattern)?.let { dao.deleteWildcardRule(it) }
            }
        }
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
        NotificationHelper.notifyBlocked(context, number, matchReason, isCall, smsBody, databaseRowSource(number, matchReason))
    }

    /**
     * The source of the row behind a database or prefix-expansion block,
     * found the way that check finds it, so the alert's Not spam can tell the
     * shared database from the user's list subscriptions, whose rows match
     * the same way. A prefix match that found shared rows reports one of them.
     * Null for any other block.
     */
    private suspend fun databaseRowSource(
        number: String,
        matchReason: String,
    ): String? {
        val now = System.currentTimeMillis()
        val forms = equivalentForms(normalizeNumber(number))
        return when (NotificationHelper.notSpamReasonCode(matchReason)) {
            BlockReasonCode.DATABASE -> {
                forms
                    .firstNotNullOfOrNull { form -> dao.findByNumber(form)?.activeDecision(now)?.takeUnless { it.isUserBlocked } }
                    ?.source
            }

            BlockReasonCode.DB_PREFIX_EXPANSION -> {
                val sources = forms.mapNotNull { SpamRepositoryImpl.dbExpansionPrefix(it) }.flatMap { dao.sourcesByPrefix(it, now) }
                sources.firstOrNull { NotificationHelper.isSharedDatabaseRow(it) } ?: sources.firstOrNull()
            }

            else -> {
                null
            }
        }
    }

    private val textLogLock = Mutex()

    /**
     * Logs a flagged text once. Google Messages and Samsung Messages are read
     * by both the SMS receiver and the notification listener, so one text
     * arrives twice, by paths that see it differently: a notification
     * truncates the body or carries none, number formats differ, and the
     * listener's reason carries an rcs_ prefix. So a sighting repeats one
     * logged from the same canonical sender inside [TEXT_DUPLICATE_WINDOW_MS]
     * when their bodies can be the same text ([sameFlaggedText]); a second,
     * different text from that sender gets its own row. The lock stops two
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
            // Every sighting, repeats too: the notification copy can be cut
            // short before the number and still be the one that got logged.
            rememberCallbackNumbersFrom(sender, smsBody, timestamp)
            val recent = dao.flaggedTextsSince(sender, timestamp - TEXT_DUPLICATE_WINDOW_MS)
            val listener = fromListener(matchReason)
            val repeat = recent.any { sameFlaggedText(it.body, smsBody, samePath = fromListener(it.matchReason) == listener) }
            if (repeat) return@withLock false
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

    /** Keeps the numbers a flagged text that gets no log row of its own asks the reader to call. */
    suspend fun rememberCallbackNumbers(
        sender: String,
        smsBody: String?,
        timestamp: Long = System.currentTimeMillis(),
    ) = rememberCallbackNumbersFrom(normalizeLogIdentity(sender), smsBody, timestamp)

    /**
     * Forgets the numbers from [sender]'s flagged texts, and the number itself
     * in any of its [forms] as a callback number, once the user says it isn't spam.
     */
    suspend fun forgetFlaggedTextNumbers(
        sender: String,
        forms: List<String>,
    ) = dao.deleteFlaggedTextNumbers(normalizeLogIdentity(sender), forms)

    /** Keeps the numbers a flagged text asks the reader to call, for [FLAGGED_TEXT_NUMBER_TTL_MS]. */
    private suspend fun rememberCallbackNumbersFrom(
        sender: String,
        smsBody: String?,
        timestamp: Long,
    ) {
        if (smsBody.isNullOrBlank()) return
        val canonicalizer = PhoneIdentityCanonicalizer.cachedFromContext(context)
        val nanpHome = PhoneIdentityCanonicalizer.readsBareDigitsAsNanp(canonicalizer.homeRegionIso)
        val numbers = SmsContentAnalyzer.extractCallbackNumbers(smsBody, nanpHome, canonicalizer::nationalToE164)
        if (numbers.isEmpty()) return
        dao.upsertFlaggedTextNumbers(numbers.map { FlaggedTextNumber(number = it, sender = sender, seenAt = timestamp) })
        dao.deleteFlaggedTextNumbersBefore(timestamp - FLAGGED_TEXT_NUMBER_TTL_MS)
        dao.trimFlaggedTextNumbers(MAX_FLAGGED_TEXT_NUMBERS)
    }

    /** When one of [forms] last appeared in a flagged text, within [FLAGGED_TEXT_NUMBER_TTL_MS] of [now], or null. */
    suspend fun lastFlaggedTextSighting(
        forms: List<String>,
        now: Long,
    ): Long? = forms.takeIf { it.isNotEmpty() }?.let { dao.lastFlaggedTextSighting(it, now - FLAGGED_TEXT_NUMBER_TTL_MS) }

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
                        rowSource = databaseRowSource(pendingLog.number, pendingLog.matchReason),
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

    fun observeLogNanpSightingsSince(
        since: Long,
        limit: Int,
    ): Flow<List<NumberSighting>> = dao.observeLogNanpSightingsSince(since, limit)

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

    /** Clear the log and return the rows it held, so an Undo can put them back. */
    suspend fun clearCallLogUndoable(): List<BlockedCall> {
        var cleared = emptyList<BlockedCall>()
        runInTransaction {
            cleared = dao.getAllCallLogOnce()
            dao.clearCallLog()
        }
        return cleared
    }

    suspend fun restoreCallLog(calls: List<BlockedCall>) {
        if (calls.isNotEmpty()) dao.insertBlockedCalls(calls)
    }

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

    /**
     * @param rangeDigits the number block a permanent entry covers (see
     *   [WhitelistEntry.rangeDigits]); null keeps what an existing permanent
     *   entry for the number already covers.
     * @return true when the number is whitelisted afterwards; false when refused (invalid input or a permanent block wins).
     */
    suspend fun addToWhitelist(
        number: String,
        description: String = "",
        isEmergency: Boolean = false,
        expiresAt: Long? = null,
        rangeDigits: Int? = null,
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
                val range = rangeDigits ?: dao.findWhitelistEntry(normalized)?.takeIf { it.expiresAt == null }?.rangeDigits ?: 0
                val entry =
                    WhitelistEntry(
                        number = normalized,
                        description = description.trim(),
                        isEmergency = isEmergency && expiresAt == null,
                        expiresAt = expiresAt,
                    )
                dao.insertWhitelistEntry(entry.copy(rangeDigits = if (entry.canCover(range)) range else 0))
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

    /** @return false when the entry is gone or can't cover a block that size (temporary, or too short a number). */
    suspend fun setWhitelistRange(
        id: Long,
        rangeDigits: Int,
    ): Boolean {
        var applied = false
        runInTransaction {
            val entry = dao.findWhitelistEntryById(id)
            if (entry != null && entry.canCover(rangeDigits)) {
                dao.setWhitelistRange(id, rangeDigits)
                applied = true
            }
        }
        return applied
    }

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
