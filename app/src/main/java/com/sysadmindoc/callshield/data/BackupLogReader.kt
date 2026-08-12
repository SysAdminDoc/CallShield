package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.local.SpamDao

/** Reads the bounded, newest portion of the call log for backup generation. */
internal object BackupLogReader {
    private const val BATCH_SIZE = 256

    suspend fun read(
        dao: SpamDao,
        limit: Int,
    ): List<BackupRestore.BackupLogEntry> {
        if (limit <= 0) return emptyList()

        val logs = ArrayList<BackupRestore.BackupLogEntry>(minOf(limit, BATCH_SIZE))
        var beforeTimestamp = Long.MAX_VALUE
        var beforeId = Long.MAX_VALUE
        while (logs.size < limit) {
            val batchLimit = minOf(BATCH_SIZE, limit - logs.size)
            val batch = dao.readBlockedCallsBatch(beforeTimestamp, beforeId, batchLimit)
            if (batch.isEmpty()) break

            batch.forEach { call ->
                logs +=
                    BackupRestore.BackupLogEntry(
                        number = call.number,
                        timestamp = call.timestamp,
                        type = call.type,
                        wasBlocked = call.wasBlocked,
                        isCall = call.isCall,
                        smsBody = call.smsBody,
                        matchReason = call.matchReason,
                        reasonCode = call.reasonCode.wireValue,
                        confidence = call.confidence,
                        logKey = call.logKey,
                        ruleId = call.ruleId,
                        pipelineDiagnostic = call.pipelineDiagnostic,
                        origid = BackupRestore.sanitizeOrigid(call.origid),
                    )
            }

            val last = batch.last()
            if (last.timestamp > beforeTimestamp ||
                (last.timestamp == beforeTimestamp && last.id >= beforeId)
            ) {
                // A DAO implementation that violates the cursor contract must
                // not trap backup generation in an infinite loop.
                break
            }
            beforeTimestamp = last.timestamp
            beforeId = last.id
            if (batch.size < batchLimit) break
        }
        return logs
    }
}
