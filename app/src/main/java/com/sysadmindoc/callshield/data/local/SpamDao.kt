package com.sysadmindoc.callshield.data.local

import androidx.room.*
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.SpamPrefix
import kotlinx.coroutines.flow.Flow

@Dao
interface SpamDao {
    // Spam numbers
    @Query("SELECT * FROM spam_numbers WHERE number = :number LIMIT 1")
    suspend fun findByNumber(number: String): SpamNumber?

    @Query("SELECT * FROM spam_numbers WHERE :number LIKE number || '%' OR number = :number LIMIT 1")
    suspend fun findByNumberFuzzy(number: String): SpamNumber?

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

    // For rapid-fire detection — recent blocked numbers
    @Query("SELECT * FROM call_log WHERE timestamp > :since ORDER BY timestamp DESC")
    suspend fun getRecentBlockedNumbers(since: Long): List<BlockedCall>
}
