package com.sysadmindoc.callshield.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import com.sysadmindoc.callshield.data.checker.CheckContext
import com.sysadmindoc.callshield.data.checker.CheckerDependencies
import com.sysadmindoc.callshield.data.checker.CheckerPipeline
import com.sysadmindoc.callshield.data.checker.IChecker
import com.sysadmindoc.callshield.data.checker.PipelineTrace
import com.sysadmindoc.callshield.data.checker.SpamCheckers
import com.sysadmindoc.callshield.data.local.SpamDao
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.data.model.HashWildcardRule
import com.sysadmindoc.callshield.data.model.SmsKeywordRule
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.SpamPrefix
import com.sysadmindoc.callshield.data.model.WhitelistEntry
import com.sysadmindoc.callshield.data.model.WildcardRule
import com.sysadmindoc.callshield.data.normalizePhoneNumber
import com.sysadmindoc.callshield.data.toSpamCheckResult
import com.sysadmindoc.callshield.domain.model.SpamCheckResult

@Suppress("TooManyFunctions", "ReturnCount")
class SpamRepositoryImpl(
    private val context: Context,
    private val dao: SpamDao,
    private val settingsRepository: SettingsRepository,
    private val checkerDependencies: CheckerDependencies = CheckerDependencies(),
) {
    // isSpam() is the critical real-time path. Loading all prefixes,
    // wildcard rules, and keyword rules from Room on every call adds
    // avoidable I/O latency, so writes invalidate these process caches.
    @Volatile private var cachedPrefixes: List<SpamPrefix>? = null
    @Volatile private var cachedWildcardRules: List<WildcardRule>? = null
    @Volatile private var cachedKeywordRules: List<SmsKeywordRule>? = null
    @Volatile private var cachedHashWildcardRules: List<HashWildcardRule>? = null

    internal fun invalidatePrefixCache() {
        cachedPrefixes = null
    }

    internal fun invalidateWildcardCache() {
        cachedWildcardRules = null
    }

    internal fun invalidateKeywordCache() {
        cachedKeywordRules = null
    }

    internal fun invalidateHashWildcardCache() {
        cachedHashWildcardRules = null
    }

    internal fun invalidateAllCaches() {
        cachedPrefixes = null
        cachedWildcardRules = null
        cachedKeywordRules = null
        cachedHashWildcardRules = null
    }

    internal suspend fun findWhitelistEntryInternal(normalized: String): WhitelistEntry? =
        dao.findWhitelistEntry(normalized)

    internal suspend fun findByNumberInternal(normalized: String): SpamNumber? =
        dao.findByNumber(normalized)

    internal suspend fun hasDbPrefixMatch(normalized: String): Boolean {
        if (normalized.length < 9) return false
        val prefix = normalized.dropLast(2)
        return dao.countByPrefix(prefix) > 0
    }

    internal suspend fun getPrefixesCachedInternal(): List<SpamPrefix> =
        cachedPrefixes ?: dao.getAllPrefixes().also { cachedPrefixes = it }

    internal suspend fun getActiveWildcardsCachedInternal(): List<WildcardRule> =
        cachedWildcardRules ?: dao.getActiveWildcardRules().also { cachedWildcardRules = it }

    internal suspend fun getActiveKeywordsCachedInternal(): List<SmsKeywordRule> =
        cachedKeywordRules ?: dao.getActiveKeywordRules().also { cachedKeywordRules = it }

    internal suspend fun getActiveHashWildcardsCachedInternal(): List<HashWildcardRule> =
        cachedHashWildcardRules ?: dao.getActiveHashWildcardRules().also { cachedHashWildcardRules = it }

    internal suspend fun getNumberFrequencySinceInternal(number: String, since: Long): Int =
        dao.getNumberFrequencySince(number, since)

    internal suspend fun getRecentBlockedNumbersInternal(since: Long): List<BlockedCall> =
        dao.getRecentBlockedNumbers(since)

    private val callChain: List<IChecker> by lazy {
        SpamCheckers.buildCallChain(this, context, checkerDependencies)
    }
    private val smsExtensions: List<IChecker> by lazy {
        SpamCheckers.buildSmsExtensions(this, context, checkerDependencies)
    }

    suspend fun isSpam(
        number: String,
        smsBody: String? = null,
        realtimeCall: Boolean = true,
        prefsSnapshot: Preferences? = null,
        verificationStatus: Int? = null,
    ): SpamCheckResult {
        val normalized = normalizeNumber(number)
        if (normalized.isBlank()) return SpamCheckResult(false)

        val prefs = prefsSnapshot ?: settingsRepository.readPrefsSnapshot()
        val ctx = CheckContext(
            appContext = context,
            number = normalized,
            smsBody = smsBody,
            realtimeCall = realtimeCall,
            prefs = prefs,
            verificationStatus = verificationStatus,
        )

        val verdict = CheckerPipeline.run(callChain, ctx)
            ?: return SpamCheckResult(false)

        return verdict.toSpamCheckResult()
    }

    suspend fun isSpamSms(
        number: String,
        body: String,
        realtimeCall: Boolean = true,
        prefsSnapshot: Preferences? = null,
    ): SpamCheckResult {
        val prefs = prefsSnapshot ?: settingsRepository.readPrefsSnapshot()
        val numberResult = isSpam(number, smsBody = body, realtimeCall = realtimeCall, prefsSnapshot = prefs)
        if (numberResult.isSpam) return numberResult

        val normalized = normalizeNumber(number)
        if (normalized.isBlank()) return SpamCheckResult(false)
        val ctx = CheckContext(
            appContext = context,
            number = normalized,
            smsBody = body,
            realtimeCall = realtimeCall,
            prefs = prefs,
        )
        val verdict = CheckerPipeline.run(smsExtensions, ctx)
            ?: return SpamCheckResult(false)

        return verdict.toSpamCheckResult()
    }

    fun normalizeNumber(number: String): String = normalizePhoneNumber(number)

    suspend fun traceRules(number: String): PipelineTrace {
        val normalized = normalizeNumber(number)
        if (normalized.isBlank()) return PipelineTrace(emptyList(), false)
        val prefs = settingsRepository.readPrefsSnapshot()
        val ctx = CheckContext(
            appContext = context,
            number = normalized,
            realtimeCall = false,
            prefs = prefs,
        )
        return CheckerPipeline.traceAll(callChain, ctx)
    }
}
