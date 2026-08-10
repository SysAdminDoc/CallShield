package com.sysadmindoc.callshield.data.local

import androidx.room.*
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.data.model.CampaignObservation
import com.sysadmindoc.callshield.data.model.HashWildcardRule
import com.sysadmindoc.callshield.data.model.NumberCount
import com.sysadmindoc.callshield.data.model.PendingBlockedCallLog
import com.sysadmindoc.callshield.data.model.RestoreJournal
import com.sysadmindoc.callshield.data.model.SmsKeywordRule
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.SpamPrefix
import com.sysadmindoc.callshield.data.model.WhitelistEntry
import com.sysadmindoc.callshield.data.model.WildcardRule
import com.sysadmindoc.callshield.domain.model.BlockReasonCode
import kotlinx.coroutines.flow.Flow

@Dao
interface SpamDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRestoreJournal(journal: RestoreJournal)

    @Query("SELECT * FROM restore_journal WHERE journalId = 1 LIMIT 1")
    suspend fun getRestoreJournal(): RestoreJournal?

    @Query("UPDATE restore_journal SET phase = :phase WHERE journalId = 1")
    suspend fun updateRestoreJournalPhase(phase: String)

    @Query("DELETE FROM restore_journal WHERE journalId = 1")
    suspend fun deleteRestoreJournal()

    // Spam numbers
    @Query("SELECT * FROM spam_numbers WHERE number = :number LIMIT 1")
    suspend fun findByNumber(number: String): SpamNumber?

    @Query("SELECT * FROM spam_numbers ORDER BY reports DESC")
    fun getAllSpamNumbers(): Flow<List<SpamNumber>>

    @Query("SELECT * FROM spam_numbers WHERE isUserBlocked = 1 ORDER BY number")
    fun getUserBlockedNumbers(): Flow<List<SpamNumber>>

    @Query("SELECT COUNT(*) FROM spam_numbers")
    suspend fun getSpamCount(): Int

    @Query("SELECT COUNT(*) FROM spam_numbers")
    fun observeSpamCount(): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM spam_numbers " +
            "WHERE number LIKE :prefix || '%' AND isUserBlocked = 0 " +
            "AND (evidenceExpiresAt IS NULL OR evidenceExpiresAt > :now) LIMIT 1",
    )
    suspend fun countByPrefix(
        prefix: String,
        now: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNumber(number: SpamNumber)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNumbers(numbers: List<SpamNumber>)

    @Delete
    suspend fun deleteNumber(number: SpamNumber)

    // Preserve user-owned blocks even when the backing source refreshes.
    // Example: a user-blocked number that also exists in the GitHub dataset
    // must survive the next sync instead of disappearing.
    @Query("DELETE FROM spam_numbers WHERE source = :source AND isUserBlocked = 0")
    suspend fun deleteBySource(source: String)

    @Query("SELECT COUNT(*) FROM spam_numbers WHERE source = :source")
    suspend fun getCountBySource(source: String): Int

    @Query("SELECT * FROM spam_numbers WHERE source = :source")
    suspend fun getNumbersBySource(source: String): List<SpamNumber>

    @Query("SELECT * FROM spam_numbers WHERE isUserBlocked = 1")
    suspend fun getUserBlockedNumbersSync(): List<SpamNumber>

    @Query("SELECT * FROM spam_numbers WHERE number IN (:numbers)")
    suspend fun getNumbersByNumbers(numbers: List<String>): List<SpamNumber>

    @Query("UPDATE spam_numbers SET isUserBlocked = 0, expiresAt = NULL WHERE isUserBlocked = 1 AND source != 'user'")
    suspend fun clearUserBlockFlagsOnSyncedNumbers()

    @Query("DELETE FROM spam_numbers WHERE isUserBlocked = 1 AND source = 'user'")
    suspend fun deleteUserOwnedBlockedNumbers()

    @Query(
        """
        DELETE FROM spam_numbers
        WHERE isUserBlocked = 1 AND source = 'user' AND expiresAt IS NOT NULL AND expiresAt <= :now
        """,
    )
    suspend fun deleteExpiredUserOwnedBlocks(now: Long): Int

    @Query(
        """
        UPDATE spam_numbers
        SET isUserBlocked = 0, expiresAt = NULL
        WHERE isUserBlocked = 1 AND source != 'user' AND expiresAt IS NOT NULL AND expiresAt <= :now
        """,
    )
    suspend fun clearExpiredSyncedUserBlockFlags(now: Long): Int

    @Transaction
    suspend fun replaceBySource(
        source: String,
        numbers: List<SpamNumber>,
    ) {
        deleteBySource(source)
        if (numbers.isNotEmpty()) insertNumbers(numbers)
    }

    // Spam prefixes
    @Query("SELECT * FROM spam_prefixes WHERE evidenceExpiresAt IS NULL OR evidenceExpiresAt > :now")
    suspend fun getAllPrefixes(now: Long): List<SpamPrefix>

    /** Unfiltered snapshot used when retaining shards that did not change. */
    @Query("SELECT * FROM spam_prefixes")
    suspend fun getAllPrefixesForSync(): List<SpamPrefix>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrefixes(prefixes: List<SpamPrefix>)

    @Query("DELETE FROM spam_prefixes")
    suspend fun deleteAllPrefixes()

    @Transaction
    suspend fun replaceGithubData(
        numbers: List<SpamNumber>,
        prefixes: List<SpamPrefix>,
    ) {
        deleteBySource("github")
        deleteAllPrefixes()
        if (numbers.isNotEmpty()) insertNumbers(numbers)
        if (prefixes.isNotEmpty()) insertPrefixes(prefixes)
    }

    // Call log
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertBlockedCall(call: BlockedCall)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBlockedCallIgnoringDuplicate(call: BlockedCall): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPendingBlockedCallLog(log: PendingBlockedCallLog): Long

    @Query(
        """
        SELECT * FROM pending_blocked_call_logs
        WHERE nextAttemptAt <= :now
        ORDER BY createdAt ASC
        LIMIT :limit
        """,
    )
    suspend fun getReadyPendingBlockedCallLogs(
        now: Long,
        limit: Int,
    ): List<PendingBlockedCallLog>

    @Query("SELECT COUNT(*) FROM pending_blocked_call_logs")
    suspend fun getPendingBlockedCallLogCount(): Int

    @Query("DELETE FROM pending_blocked_call_logs WHERE idempotencyKey = :idempotencyKey")
    suspend fun deletePendingBlockedCallLog(idempotencyKey: String): Int

    @Query(
        """
        UPDATE pending_blocked_call_logs
        SET attempts = attempts + 1, nextAttemptAt = :nextAttemptAt
        WHERE idempotencyKey = :idempotencyKey
        """,
    )
    suspend fun markPendingBlockedCallLogFailed(
        idempotencyKey: String,
        nextAttemptAt: Long,
    )

    @Transaction
    suspend fun consumePendingBlockedCallLog(log: PendingBlockedCallLog): Long {
        val inserted =
            insertBlockedCallIgnoringDuplicate(
                BlockedCall(
                    number = log.number,
                    timestamp = log.timestamp,
                    type = log.type,
                    isCall = log.isCall,
                    smsBody = log.smsBody,
                    matchReason = log.matchReason,
                    confidence = log.confidence,
                    logKey = log.idempotencyKey,
                    ruleId = log.ruleId,
                    reasonCode = log.reasonCode,
                    origid = log.origid,
                ),
            )
        deletePendingBlockedCallLog(log.idempotencyKey)
        return inserted
    }

    @Query("SELECT * FROM call_log ORDER BY timestamp DESC")
    fun getBlockedCalls(): Flow<List<BlockedCall>>

    @Query("SELECT * FROM call_log WHERE isCall = 1 ORDER BY timestamp DESC")
    fun getBlockedCallsOnly(): Flow<List<BlockedCall>>

    @Query("SELECT * FROM call_log WHERE isCall = 0 ORDER BY timestamp DESC")
    fun getBlockedSmsOnly(): Flow<List<BlockedCall>>

    @Query("SELECT COUNT(*) FROM call_log WHERE wasBlocked = 1")
    fun getTotalBlockedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM call_log WHERE wasBlocked = 1 AND timestamp > :since")
    fun getBlockedCountSince(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM call_log WHERE wasBlocked = 1 AND timestamp > :since")
    suspend fun getBlockedCountSinceSync(since: Long): Int

    /**
     * Restore-dedupe identity per row: the logKey when present, otherwise the
     * same `number|timestamp|isCall` fallback the restore preview uses. Kept
     * as a projection so restore never materializes full rows (SMS bodies).
     */
    @Query(
        "SELECT CASE WHEN logKey IS NOT NULL AND logKey != '' THEN logKey " +
            "ELSE number || '|' || timestamp || '|' || CASE WHEN isCall THEN 'true' ELSE 'false' END END " +
            "FROM call_log",
    )
    suspend fun getBlockedCallConflictKeysSync(): List<String>

    @Query("SELECT COUNT(*) FROM call_log WHERE wasBlocked = 1 AND timestamp > :start AND timestamp <= :end")
    fun getBlockedCountBetween(
        start: Long,
        end: Long,
    ): Flow<Int>

    @Query("SELECT COUNT(*) FROM call_log WHERE wasBlocked = 1 AND timestamp > :start AND timestamp <= :end")
    suspend fun getBlockedCountBetweenSync(
        start: Long,
        end: Long,
    ): Int

    @Query("SELECT MAX(timestamp) FROM call_log WHERE wasBlocked = 1")
    suspend fun getLastBlockedTimestamp(): Long?

    @Query("DELETE FROM call_log")
    suspend fun clearCallLog()

    @Delete
    suspend fun deleteBlockedCall(call: BlockedCall)

    @Query("SELECT * FROM call_log WHERE timestamp > :since ORDER BY timestamp DESC")
    suspend fun getRecentBlockedNumbers(since: Long): List<BlockedCall>

    // Local campaign evidence
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCampaignObservation(observation: CampaignObservation)

    @Query(
        "SELECT * FROM campaign_observations " +
            "WHERE prefix = :prefix AND observedAt > :since ORDER BY observedAt ASC",
    )
    suspend fun getCampaignObservations(
        prefix: String,
        since: Long,
    ): List<CampaignObservation>

    @Query("DELETE FROM campaign_observations WHERE observedAt <= :before")
    suspend fun deleteCampaignObservationsBefore(before: Long)

    @Query(
        "DELETE FROM campaign_observations " +
            "WHERE id NOT IN (SELECT id FROM campaign_observations ORDER BY observedAt DESC LIMIT :maxRows)",
    )
    suspend fun trimCampaignObservations(maxRows: Int)

    // Bounded digest aggregates — avoid materializing the full 24h window
    // (including smsBody) in a constrained background process on heavy-spam
    // days. Counts are computed in SQL; the source breakdown reads only the
    // short reasonCode column, never full rows.
    @Query("SELECT COUNT(*) FROM call_log WHERE wasBlocked = 1 AND isCall = 1 AND timestamp > :since")
    suspend fun getBlockedCallCountSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM call_log WHERE wasBlocked = 1 AND isCall = 0 AND timestamp > :since")
    suspend fun getBlockedSmsCountSince(since: Long): Int

    @Query("SELECT reasonCode FROM call_log WHERE wasBlocked = 1 AND timestamp > :since")
    suspend fun getBlockedMatchReasonsSince(since: Long): List<String>

    @Query("SELECT * FROM call_log WHERE reasonCode = :reasonCode ORDER BY timestamp DESC")
    fun getBlockedCallsByReasonCode(reasonCode: BlockReasonCode): Flow<List<BlockedCall>>

    // Feature 10: Frequency tracking — count how many times a number appears in
    // the log within a time window. Unbounded counts caused false positives for
    // legitimate callers with 3+ calls spread over months.
    @Query("SELECT COUNT(*) FROM call_log WHERE number = :number AND isCall = 1 AND timestamp > :since")
    suspend fun getCallFrequencySince(
        number: String,
        since: Long,
    ): Int

    // Wildcard rules (Feature 8)
    @Query("SELECT * FROM wildcard_rules WHERE enabled = 1")
    suspend fun getActiveWildcardRules(): List<WildcardRule>

    @Query("SELECT * FROM wildcard_rules ORDER BY id DESC")
    fun getAllWildcardRules(): Flow<List<WildcardRule>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWildcardRule(rule: WildcardRule)

    @Query("DELETE FROM wildcard_rules")
    suspend fun clearWildcardRules()

    @Delete
    suspend fun deleteWildcardRule(rule: WildcardRule)

    @Query("UPDATE wildcard_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setWildcardRuleEnabled(
        id: Long,
        enabled: Boolean,
    )

    // Hash wildcard rules (length-locked `#` patterns, DB v7+)
    @Query("SELECT * FROM hash_wildcard_rules WHERE enabled = 1")
    suspend fun getActiveHashWildcardRules(): List<HashWildcardRule>

    @Query("SELECT * FROM hash_wildcard_rules ORDER BY addedTimestamp DESC")
    fun getAllHashWildcardRules(): Flow<List<HashWildcardRule>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHashWildcardRule(rule: HashWildcardRule)

    @Query("DELETE FROM hash_wildcard_rules")
    suspend fun clearHashWildcardRules()

    @Delete
    suspend fun deleteHashWildcardRule(rule: HashWildcardRule)

    @Query("UPDATE hash_wildcard_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setHashWildcardRuleEnabled(
        id: Long,
        enabled: Boolean,
    )

    // Search — repository pre-escapes `%`, `_`, and `\` in the user query
    // so typing a literal `%` doesn't silently become a wildcard and a
    // blank search doesn't return the whole table.
    @Query(
        """SELECT * FROM spam_numbers
              WHERE number LIKE '%' || :query || '%' ESCAPE '\'
                 OR description LIKE '%' || :query || '%' ESCAPE '\'
                 OR (:digitsQuery != '' AND number LIKE '%' || :digitsQuery || '%' ESCAPE '\')
              ORDER BY reports DESC LIMIT 100""",
    )
    fun searchNumbers(
        query: String,
        digitsQuery: String,
    ): Flow<List<SpamNumber>>

    // Whitelist
    @Query("SELECT * FROM whitelist ORDER BY isEmergency DESC, addedTimestamp DESC")
    fun getAllWhitelist(): Flow<List<WhitelistEntry>>

    @Query("SELECT * FROM whitelist WHERE isEmergency = 1 ORDER BY addedTimestamp DESC")
    fun getEmergencyContacts(): Flow<List<WhitelistEntry>>

    @Query("SELECT * FROM whitelist WHERE number = :number LIMIT 1")
    suspend fun findWhitelistEntry(number: String): WhitelistEntry?

    @Query("SELECT * FROM whitelist WHERE number = :number AND expiresAt IS NULL LIMIT 1")
    suspend fun findPermanentWhitelistEntry(number: String): WhitelistEntry?

    @Query("SELECT * FROM whitelist WHERE number = :number AND expiresAt IS NOT NULL AND expiresAt > :now LIMIT 1")
    suspend fun findActiveTemporaryWhitelistEntry(
        number: String,
        now: Long,
    ): WhitelistEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWhitelistEntry(entry: WhitelistEntry)

    @Query("DELETE FROM whitelist")
    suspend fun clearWhitelist()

    @Delete
    suspend fun deleteWhitelistEntry(entry: WhitelistEntry)

    @Query("UPDATE whitelist SET isEmergency = :emergency WHERE id = :id")
    suspend fun setWhitelistEmergency(
        id: Long,
        emergency: Boolean,
    )

    @Query("DELETE FROM whitelist WHERE expiresAt IS NOT NULL AND expiresAt <= :now")
    suspend fun deleteExpiredWhitelistEntries(now: Long): Int

    // Auto-cleanup
    @Query("DELETE FROM call_log WHERE timestamp < :before")
    suspend fun deleteLogOlderThan(before: Long)

    // SMS keyword rules
    @Query("SELECT * FROM sms_keyword_rules WHERE enabled = 1")
    suspend fun getActiveKeywordRules(): List<SmsKeywordRule>

    @Query("SELECT * FROM sms_keyword_rules ORDER BY id DESC")
    fun getAllKeywordRules(): Flow<List<SmsKeywordRule>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKeywordRule(rule: SmsKeywordRule)

    @Query("DELETE FROM sms_keyword_rules")
    suspend fun clearKeywordRules()

    @Delete
    suspend fun deleteKeywordRule(rule: SmsKeywordRule)

    @Query("UPDATE sms_keyword_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setKeywordRuleEnabled(
        id: Long,
        enabled: Boolean,
    )

    // Grouped blocked numbers — count per number
    @Query("SELECT number, COUNT(*) as cnt FROM call_log WHERE wasBlocked = 1 GROUP BY number ORDER BY cnt DESC LIMIT :limit")
    suspend fun getGroupedBlockedNumbers(limit: Int = 50): List<NumberCount>

    // Search across log — escaped like above
    @Query(
        """SELECT * FROM call_log
              WHERE number LIKE '%' || :query || '%' ESCAPE '\'
                 OR reasonCode LIKE '%' || :query || '%' ESCAPE '\'
              ORDER BY timestamp DESC LIMIT 100""",
    )
    fun searchLog(query: String): Flow<List<BlockedCall>>
}
