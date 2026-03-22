package com.sysadmindoc.callshield.service

import android.content.Context
import android.net.Uri
import com.sysadmindoc.callshield.data.SpamRepository

/**
 * Scans existing SMS inbox for spam messages.
 */
object SmsInboxScanner {

    data class ScanResult(
        val totalScanned: Int,
        val spamFound: Int,
        val spamMessages: List<ScannedSms>
    )

    data class ScannedSms(
        val number: String,
        val body: String,
        val date: Long,
        val matchReason: String,
        val type: String
    )

    suspend fun scan(context: Context, limit: Int = 200): ScanResult {
        val repo = SpamRepository.getInstance(context)
        val spamList = mutableListOf<ScannedSms>()
        var scanned = 0

        try {
            val cursor = context.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("address", "body", "date"),
                null, null, "date DESC"
            )
            cursor?.use { c ->
                val addrIdx = c.getColumnIndex("address")
                val bodyIdx = c.getColumnIndex("body")
                val dateIdx = c.getColumnIndex("date")
                if (addrIdx < 0 || bodyIdx < 0) return@use

                while (c.moveToNext() && scanned < limit) {
                    val address = c.getString(addrIdx) ?: continue
                    val body = c.getString(bodyIdx) ?: ""
                    val date = if (dateIdx >= 0) c.getLong(dateIdx) else 0L
                    scanned++

                    val result = repo.isSpamSms(address, body)
                    if (result.isSpam) {
                        spamList.add(ScannedSms(
                            number = address,
                            body = body.take(100),
                            date = date,
                            matchReason = result.matchSource,
                            type = result.type
                        ))
                    }
                }
            }
        } catch (_: SecurityException) {}

        spamList.sortByDescending { it.date }
        return ScanResult(scanned, spamList.size, spamList)
    }
}
