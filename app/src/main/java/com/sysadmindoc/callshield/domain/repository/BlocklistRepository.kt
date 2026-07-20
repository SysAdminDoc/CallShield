package com.sysadmindoc.callshield.domain.repository

import com.sysadmindoc.callshield.data.TimeSchedule
import com.sysadmindoc.callshield.data.model.HashWildcardRule
import com.sysadmindoc.callshield.data.model.SmsKeywordRule
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.WhitelistEntry
import com.sysadmindoc.callshield.data.model.WildcardRule

@Suppress("TooManyFunctions")
interface BlocklistRepository {
    suspend fun blockNumber(
        number: String,
        type: String,
        description: String,
    )

    suspend fun temporaryBlockNumber(
        number: String,
        expiresAt: Long,
        type: String,
        description: String,
    )

    suspend fun temporaryAllowNumber(
        number: String,
        expiresAt: Long,
        description: String,
    )

    suspend fun unblockNumber(number: SpamNumber)

    suspend fun addWildcardRule(
        pattern: String,
        isRegex: Boolean,
        description: String,
        schedule: TimeSchedule,
    )

    suspend fun deleteWildcardRule(rule: WildcardRule)

    suspend fun toggleWildcardRule(
        id: Long,
        enabled: Boolean,
    )

    suspend fun addHashWildcardRule(
        pattern: String,
        description: String,
        schedule: TimeSchedule,
    ): Boolean

    suspend fun deleteHashWildcardRule(rule: HashWildcardRule)

    suspend fun toggleHashWildcardRule(
        id: Long,
        enabled: Boolean,
    )

    suspend fun addKeywordRule(
        keyword: String,
        caseSensitive: Boolean,
        description: String,
        schedule: TimeSchedule,
    )

    suspend fun deleteKeywordRule(rule: SmsKeywordRule)

    suspend fun toggleKeywordRule(
        id: Long,
        enabled: Boolean,
    )

    suspend fun addToWhitelist(
        number: String,
        description: String,
        isEmergency: Boolean,
    )

    suspend fun removeFromWhitelist(entry: WhitelistEntry)

    suspend fun setWhitelistEmergency(
        id: Long,
        emergency: Boolean,
    )
}
