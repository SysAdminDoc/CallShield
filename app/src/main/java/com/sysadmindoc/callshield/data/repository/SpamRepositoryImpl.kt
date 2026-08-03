package com.sysadmindoc.callshield.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import com.sysadmindoc.callshield.data.CategoryCallPolicy
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
import com.sysadmindoc.callshield.data.toSpamCheckResult
import com.sysadmindoc.callshield.domain.model.CallerIdentity
import com.sysadmindoc.callshield.domain.model.SpamCheckResult

@Suppress("TooManyFunctions", "ReturnCount")
class SpamRepositoryImpl(
    private val context: Context,
    private val dao: SpamDao,
    private val settingsRepository: SettingsRepository,
    private val checkerDependencies: CheckerDependencies = CheckerDependencies(),
    private val normalizePhone: (String) -> String,
    private val normalizeSenderIdentity: (String) -> String,
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

    internal suspend fun findWhitelistEntryInternal(normalized: String): WhitelistEntry? = dao.findPermanentWhitelistEntry(normalized)

    internal suspend fun findTemporaryWhitelistEntryInternal(normalized: String): WhitelistEntry? = dao.findActiveTemporaryWhitelistEntry(normalized, System.currentTimeMillis())

    internal suspend fun findByNumberInternal(normalized: String): SpamNumber? = dao.findByNumber(normalized)?.activeDecision()

    internal suspend fun hasDbPrefixMatch(normalized: String): Boolean {
        if (normalized.length < 9) return false
        val prefix = normalized.dropLast(2)
        return dao.countByPrefix(prefix) > 0
    }

    internal suspend fun getPrefixesCachedInternal(): List<SpamPrefix> = cachedPrefixes ?: dao.getAllPrefixes().also { cachedPrefixes = it }

    internal suspend fun getActiveWildcardsCachedInternal(): List<WildcardRule> = cachedWildcardRules ?: dao.getActiveWildcardRules().also { cachedWildcardRules = it }

    internal suspend fun getActiveKeywordsCachedInternal(): List<SmsKeywordRule> = cachedKeywordRules ?: dao.getActiveKeywordRules().also { cachedKeywordRules = it }

    internal suspend fun getActiveHashWildcardsCachedInternal(): List<HashWildcardRule> = cachedHashWildcardRules ?: dao.getActiveHashWildcardRules().also { cachedHashWildcardRules = it }

    internal suspend fun getCallFrequencySinceInternal(
        number: String,
        since: Long,
    ): Int = dao.getCallFrequencySince(number, since)

    internal suspend fun getRecentBlockedNumbersInternal(since: Long): List<BlockedCall> = dao.getRecentBlockedNumbers(since)

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
        callerIdentity: CallerIdentity? = null,
        smsContextTrusted: Boolean = false,
    ): SpamCheckResult {
        val normalized = normalizePhone(number)
        if (normalized.isBlank()) return SpamCheckResult(false)

        val prefs = prefsSnapshot ?: settingsRepository.readPrefsSnapshot()
        val ctx =
            CheckContext(
                appContext = context,
                number = normalized,
                smsBody = smsBody,
                realtimeCall = realtimeCall,
                prefs = prefs,
                verificationStatus = callerIdentity?.verificationStatus,
                callerName = callerIdentity?.presentedName,
                smsContextTrusted = smsContextTrusted,
            )

        val verdict =
            CheckerPipeline.run(callChain, ctx)
                ?: return SpamCheckResult(false)

        val result = verdict.toSpamCheckResult()
        return if (smsBody == null) CategoryCallPolicy.apply(result, prefs) else result
    }

    suspend fun isSpamSms(
        number: String,
        body: String,
        realtimeCall: Boolean = true,
        prefsSnapshot: Preferences? = null,
    ): SpamCheckResult {
        val prefs = prefsSnapshot ?: settingsRepository.readPrefsSnapshot()
        val canonicalPhone = normalizePhone(number)
        val smsContextTrusted =
            canonicalPhone.isNotBlank() &&
                checkerDependencies.smsContextChecker.isTrustedSender(context, canonicalPhone)
        var trustedAllowSource: String? = null
        if (canonicalPhone.isNotBlank()) {
            val numberResult =
                isSpam(
                    canonicalPhone,
                    smsBody = body,
                    realtimeCall = realtimeCall,
                    prefsSnapshot = prefs,
                    smsContextTrusted = smsContextTrusted,
                )
            if (numberResult.isSpam) return numberResult
            // Carry a user-intent allow into the SMS extension chain so the
            // behavioral burst/content checkers yield to it (whitelisted /
            // contact / temporarily-allowed senders must not be blocked by
            // sms_burst or sms_content). Keyword rules still inspect them.
            if (numberResult.matchSource in USER_TRUSTED_ALLOW_SOURCES) {
                trustedAllowSource = numberResult.matchSource
            }
        }

        val normalized = normalizeSenderIdentity(number)
        if (normalized.isBlank()) return SpamCheckResult(false)
        val ctx =
            CheckContext(
                appContext = context,
                number = normalized,
                smsBody = body,
                realtimeCall = realtimeCall,
                prefs = prefs,
                trustedAllowSource = trustedAllowSource,
                smsContextTrusted = smsContextTrusted,
            )
        val verdict =
            CheckerPipeline.run(smsExtensions, ctx)
                ?: return SpamCheckResult(false)

        return verdict.toSpamCheckResult()
    }

    fun normalizeNumber(number: String): String = normalizePhone(number)

    suspend fun traceRules(number: String): PipelineTrace {
        val normalized = normalizeNumber(number)
        if (normalized.isBlank()) return PipelineTrace(emptyList(), false)
        val prefs = settingsRepository.readPrefsSnapshot()
        val ctx =
            CheckContext(
                appContext = context,
                number = normalized,
                realtimeCall = false,
                prefs = prefs,
            )
        return CheckerPipeline.traceAll(callChain, ctx)
    }

    companion object {
        /**
         * Allow matchSources that represent an explicit user-intent trust and
         * so must suppress the behavioral SMS burst/content checkers. Keyword
         * rules (SMS_KEYWORD) deliberately still inspect these senders.
         */
        private val USER_TRUSTED_ALLOW_SOURCES =
            setOf("manual_whitelist", "emergency_contact", "contact_whitelist", "temporary_allow")
    }
}
