package com.sysadmindoc.callshield.data.local

import androidx.room.*
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.SpamPrefix
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

    @Query("DELETE FROM spam_numbers WHERE source = :source")
    suspend fun deleteBySource(source: String)

    // Spam prefixes
    @Query("SELECT * FROM spam_prefixes")
    suspend fun getAllPrefixes(): List<SpamPrefix>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrefixes(prefixes: List<SpamPrefix>)

    @Query("DELETE FROM spam_prefixes")
    suspend fun deleteAllPrefixes()

    // Call log
    @Insert
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

    @Query("DELETE FROM call_log")
    suspend fun clearCallLog()

    @Delete
    suspend fun deleteBlockedCall(call: BlockedCall)

    @Query("SELECT * FROM call_log WHERE timestamp > :since ORDER BY timestamp DESC")
    suspend fun getRecentBlockedNumbers(since: Long): List<BlockedCall>

    // Feature 10: Frequency tracking — count how many times a number appears in log
    @Query("SELECT COUNT(*) FROM call_log WHERE number = :number")
    suspend fun getNumberFrequency(number: String): Int

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
    @Query("SELECT * FROM whitelist ORDER BY addedTimestamp DESC")
    fun getAllWhitelist(): Flow<List<WhitelistEntry>>

    @Query("SELECT * FROM whitelist WHERE number = :number LIMIT 1")
    suspend fun findWhitelistEntry(number: String): WhitelistEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWhitelistEntry(entry: WhitelistEntry)

    @Delete
    suspend fun deleteWhitelistEntry(entry: WhitelistEntry)
}
