package com.sysadmindoc.callshield.data.local

import androidx.room.*
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.SpamPrefix
import com.sysadmindoc.callshield.data.model.NumberCount
import com.sysadmindoc.callshield.data.model.SmsKeywordRule
import com.sysadmindoc.callshield.data.model.WhitelistEntry
import com.sysadmindoc.callshield.data.model.WildcardRule
import kotlinx.coroutines.flow.Flow

@Dao
interface SpamDao {
    // Spam numbers
    @Query("SELECT * FROM spam_numbers WHERE number = :number LIMIT 1")
    suspend fun findByNumber(number: String): SpamNumber?

    @Query("SELECT * FROM spam_numbers ORDER BY reports DESC")
    fun getAllSpamNumbers(): Flow<List<SpamNumber>>

    @Query("SELECT * FROM spam_numbers WHERE isUserBlocked = 1 ORDER BY number")
    fun getUserBlockedNumbers(): Flow<List<SpamNumber>>

    @Query("SELECT COUNT(*) FROM spam_numbers")
    suspend fun getSpamCount(): Int

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

    @Query("SELECT * FROM spam_numbers WHERE isUserBlocked = 1")
    suspend fun getUserBlockedNumbersSync(): List<SpamNumber>

    @Query("SELECT * FROM spam_numbers WHERE number IN (:numbers)")
    suspend fun getNumbersByNumbers(numbers: List<String>): List<SpamNumber>

    @Transaction
    suspend fun replaceBySource(source: String, numbers: List<SpamNumber>) {
        deleteBySource(source)
        if (numbers.isNotEmpty()) insertNumbers(numbers)
    }

    // Spam prefixes
    @Query("SELECT * FROM spam_prefixes")
    suspend fun getAllPrefixes(): List<SpamPrefix>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrefixes(prefixes: List<SpamPrefix>)

    @Query("DELETE FROM spam_prefixes")
    suspend fun deleteAllPrefixes()

    @Transaction
    suspend fun replaceGithubData(numbers: List<SpamNumber>, prefixes: List<SpamPrefix>) {
        deleteBySource("github")
        deleteAllPrefixes()
        if (numbers.isNotEmpty()) insertNumbers(numbers)
        if (prefixes.isNotEmpty()) insertPrefixes(prefixes)
    }

    // Call log
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertBlockedCall(call: BlockedCall)

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

    @Query("SELECT COUNT(*) FROM call_log WHERE wasBlocked = 1 AND timestamp > :start AND timestamp <= :end")
    fun getBlockedCountBetween(start: Long, end: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM call_log WHERE wasBlocked = 1 AND timestamp > :start AND timestamp <= :end")
    suspend fun getBlockedCountBetweenSync(start: Long, end: Long): Int

    @Query("SELECT MAX(timestamp) FROM call_log WHERE wasBlocked = 1")
    suspend fun getLastBlockedTimestamp(): Long?

    @Query("DELETE FROM call_log")
    suspend fun clearCallLog()

    @Delete
    suspend fun deleteBlockedCall(call: BlockedCall)

    @Query("SELECT * FROM call_log WHERE timestamp > :since ORDER BY timestamp DESC")
    suspend fun getRecentBlockedNumbers(since: Long): List<BlockedCall>

    // Feature 10: Frequency tracking — count how many times a number appears in
    // the log within a time window. Unbounded counts caused false positives for
    // legitimate callers with 3+ calls spread over months.
    @Query("SELECT COUNT(*) FROM call_log WHERE number = :number AND timestamp > :since")
    suspend fun getNumberFrequencySince(number: String, since: Long): Int

    // Wildcard rules (Feature 8)
    @Query("SELECT * FROM wildcard_rules WHERE enabled = 1")
    suspend fun getActiveWildcardRules(): List<WildcardRule>

    @Query("SELECT * FROM wildcard_rules ORDER BY id DESC")
    fun getAllWildcardRules(): Flow<List<WildcardRule>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWildcardRule(rule: WildcardRule)

    @Delete
    suspend fun deleteWildcardRule(rule: WildcardRule)

    @Query("UPDATE wildcard_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setWildcardRuleEnabled(id: Long, enabled: Boolean)

    // Search
    @Query("SELECT * FROM spam_numbers WHERE number LIKE '%' || :query || '%' OR description LIKE '%' || :query || '%' ORDER BY reports DESC LIMIT 100")
    fun searchNumbers(query: String): Flow<List<SpamNumber>>

    // Whitelist
    @Query("SELECT * FROM whitelist ORDER BY isEmergency DESC, addedTimestamp DESC")
    fun getAllWhitelist(): Flow<List<WhitelistEntry>>

    @Query("SELECT * FROM whitelist WHERE isEmergency = 1 ORDER BY addedTimestamp DESC")
    fun getEmergencyContacts(): Flow<List<WhitelistEntry>>

    @Query("SELECT * FROM whitelist WHERE number = :number LIMIT 1")
    suspend fun findWhitelistEntry(number: String): WhitelistEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWhitelistEntry(entry: WhitelistEntry)

    @Delete
    suspend fun deleteWhitelistEntry(entry: WhitelistEntry)

    @Query("UPDATE whitelist SET isEmergency = :emergency WHERE id = :id")
    suspend fun setWhitelistEmergency(id: Long, emergency: Boolean)

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

    @Delete
    suspend fun deleteKeywordRule(rule: SmsKeywordRule)

    @Query("UPDATE sms_keyword_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setKeywordRuleEnabled(id: Long, enabled: Boolean)

    // Grouped blocked numbers — count per number
    @Query("SELECT number, COUNT(*) as cnt FROM call_log WHERE wasBlocked = 1 GROUP BY number ORDER BY cnt DESC LIMIT :limit")
    suspend fun getGroupedBlockedNumbers(limit: Int = 50): List<NumberCount>

    // Search across log
    @Query("SELECT * FROM call_log WHERE number LIKE '%' || :query || '%' OR matchReason LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT 100")
    fun searchLog(query: String): Flow<List<BlockedCall>>
}
