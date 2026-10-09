package com.sysadmindoc.callshield.service

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.repository.SpamRepositoryAdapter
import com.sysadmindoc.callshield.domain.model.SpamCheckResult
import com.sysadmindoc.callshield.domain.usecase.CheckSpamSmsUseCase
import com.sysadmindoc.callshield.permissions.CallShieldPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scans existing SMS inbox for spam messages.
 */
object SmsInboxScanner {
    data class ScanResult(
        val totalScanned: Int,
        val spamFound: Int,
        val spamMessages: List<ScannedSms>,
        val error: String? = null,
    )

    data class ScannedSms(
        val number: String,
        val body: String,
        val date: Long,
        val matchReason: String,
        val type: String,
    )

    suspend fun scan(
        context: Context,
        limit: Int = 200,
    ): ScanResult {
        // Built only once the permission check passes.
        val checkSpamSms by lazy { CheckSpamSmsUseCase(SpamRepositoryAdapter(SpamRepository.getInstance(context))) }
        // realtimeCall = false so the historical scan doesn't feed
        // CampaignDetector with old senders or pop caller-ID overlays for
        // messages that already arrived.
        return scan(context, limit) { address, body, subscriptionId ->
            checkSpamSms(address, body, realtimeCall = false, subscriptionId = subscriptionId)
        }
    }

    /** [check] gets each text's sender, body and the SIM it arrived on, when the inbox recorded one. */
    internal suspend fun scan(
        context: Context,
        limit: Int,
        check: suspend (address: String, body: String, subscriptionId: Int?) -> SpamCheckResult,
    ): ScanResult =
        withContext(Dispatchers.IO) {
            if (!CallShieldPermissions.canReadSmsInbox(context)) {
                return@withContext ScanResult(
                    totalScanned = 0,
                    spamFound = 0,
                    spamMessages = emptyList(),
                    error = context.getString(R.string.dashboard_sms_inbox_permission_denied),
                )
            }

            val spamList = mutableListOf<ScannedSms>()
            var scanned = 0

            try {
                val cursor =
                    context.contentResolver.query(
                        Uri.parse("content://sms/inbox"),
                        arrayOf("address", "body", "date", Telephony.Sms.SUBSCRIPTION_ID),
                        null,
                        null,
                        "date DESC",
                    )
                if (cursor == null) return@withContext ScanResult(0, 0, emptyList())

                cursor.use { c ->
                    val addrIdx = c.getColumnIndex("address")
                    val bodyIdx = c.getColumnIndex("body")
                    val dateIdx = c.getColumnIndex("date")
                    val subscriptionIdx = c.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)
                    if (addrIdx < 0 || bodyIdx < 0) return@withContext ScanResult(0, 0, emptyList())

                    while (c.moveToNext() && scanned < limit) {
                        val address = c.getString(addrIdx) ?: continue
                        val body = c.getString(bodyIdx) ?: ""
                        val date = if (dateIdx >= 0) c.getLong(dateIdx) else 0L
                        // The carrier scam label is read for the SIM a text
                        // arrived on, as for a live text; -1 means none recorded.
                        val subscriptionId =
                            if (subscriptionIdx >= 0 && !c.isNull(subscriptionIdx)) c.getInt(subscriptionIdx).takeIf { it >= 0 } else null
                        scanned++

                        try {
                            val result = check(address, body, subscriptionId)
                            if (result.isSpam) {
                                spamList.add(
                                    ScannedSms(
                                        number = address,
                                        body = body.take(100),
                                        date = date,
                                        matchReason = result.matchSource,
                                        type = result.type,
                                    ),
                                )
                            }
                        } catch (_: Exception) {
                            // Skip messages that fail to check
                        }
                    }
                }
            } catch (_: SecurityException) {
                return@withContext ScanResult(
                    0,
                    0,
                    emptyList(),
                    error = context.getString(R.string.dashboard_sms_inbox_permission_denied),
                )
            } catch (_: Exception) {
                return@withContext ScanResult(scanned, spamList.size, spamList)
            }

            spamList.sortByDescending { it.date }
            ScanResult(scanned, spamList.size, spamList)
        }
}
