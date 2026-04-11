package com.sysadmindoc.callshield.service

import android.content.Context
import android.provider.CallLog
import com.sysadmindoc.callshield.data.SpamRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Feature 3: Call log scanner.
 * Scans the user's existing call history against the spam database
 * and returns numbers that match.
 */
object CallLogScanner {

    data class ScanResult(
        val totalScanned: Int,
        val spamFound: Int,
        val spamNumbers: List<ScannedSpam>,
        val error: String? = null
    )

    data class ScannedSpam(
        val number: String,
        val callCount: Int,
        val matchReason: String,
        val type: String
    )

    suspend fun scan(context: Context, limit: Int = 500): ScanResult = withContext(Dispatchers.IO) {
        val repo = SpamRepository.getInstance(context)
        val numbers = mutableMapOf<String, Int>() // number -> call count

        try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER),
                null, null,
                "${CallLog.Calls.DATE} DESC"
            )

            cursor?.use { c ->
                val colIndex = c.getColumnIndex(CallLog.Calls.NUMBER)
                if (colIndex < 0) return@use
                var count = 0
                while (c.moveToNext() && count < limit) {
                    val number = c.getString(colIndex) ?: continue
                    val clean = number.filter { ch -> ch.isDigit() || ch == '+' }
                    if (clean.length >= 7) {
                        numbers[clean] = (numbers[clean] ?: 0) + 1
                    }
                    count++
                }
            }
        } catch (_: SecurityException) {
            return@withContext ScanResult(0, 0, emptyList(), error = "Call log permission denied. Grant permission in Settings.")
        }

        val spamList = mutableListOf<ScannedSpam>()
        for ((number, callCount) in numbers) {
            val result = repo.isSpam(number)
            if (result.isSpam) {
                spamList.add(ScannedSpam(number, callCount, result.matchSource, result.type))
            }
        }

        // Sort by call count descending (most frequent spam callers first)
        spamList.sortByDescending { it.callCount }

        ScanResult(
            totalScanned = numbers.size,
            spamFound = spamList.size,
            spamNumbers = spamList
        )
    }
}
