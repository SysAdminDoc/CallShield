package com.sysadmindoc.callshield.service

import android.content.Context
import android.net.Uri
import com.sysadmindoc.callshield.permissions.CallShieldPermissions
import com.sysadmindoc.callshield.data.SpamRepository
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
        val error: String? = null
    )

    data class ScannedSms(
        val number: String,
        val body: String,
        val date: Long,
        val matchReason: String,
        val type: String
    )

    suspend fun scan(context: Context, limit: Int = 200): ScanResult = withContext(Dispatchers.IO) {
        if (!CallShieldPermissions.canReadSmsInbox(context)) {
            return@withContext ScanResult(
                totalScanned = 0,
                spamFound = 0,
                spamMessages = emptyList(),
                error = "SMS inbox permission denied. Grant SMS access in Settings."
            )
        }

        val repo = SpamRepository.getInstance(context)
        val spamList = mutableListOf<ScannedSms>()
        var scanned = 0

        try {
            val cursor = context.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("address", "body", "date"),
                null, null, "date DESC"
            )
            if (cursor == null) return@withContext ScanResult(0, 0, emptyList())

            cursor.use { c ->
                val addrIdx = c.getColumnIndex("address")
                val bodyIdx = c.getColumnIndex("body")
                val dateIdx = c.getColumnIndex("date")
                if (addrIdx < 0 || bodyIdx < 0) return@withContext ScanResult(0, 0, emptyList())

                while (c.moveToNext() && scanned < limit) {
                    val address = c.getString(addrIdx) ?: continue
                    val body = c.getString(bodyIdx) ?: ""
                    val date = if (dateIdx >= 0) c.getLong(dateIdx) else 0L
                    scanned++

                    try {
                        // realtimeCall = false so the historical scan doesn't
                        // feed CampaignDetector with old senders or pop
                        // caller-ID overlays for messages that already arrived.
                        val result = repo.isSpamSms(address, body, realtimeCall = false)
                        if (result.isSpam) {
                            spamList.add(ScannedSms(
                                number = address,
                                body = body.take(100),
                                date = date,
                                matchReason = result.matchSource,
                                type = result.type
                            ))
                        }
                    } catch (_: Exception) {
                        // Skip messages that fail to check
                    }
                }
            }
        } catch (_: SecurityException) {
            return@withContext ScanResult(0, 0, emptyList(), error = "SMS inbox permission denied. Grant SMS access in Settings.")
        } catch (_: Exception) {
            return@withContext ScanResult(scanned, spamList.size, spamList)
        }

        spamList.sortByDescending { it.date }
        ScanResult(scanned, spamList.size, spamList)
    }
}
