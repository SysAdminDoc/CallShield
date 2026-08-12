package com.sysadmindoc.callshield.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.model.BlockedCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stable cursor reader used by memory-bounded log exports. */
typealias BlockedCallBatchReader = suspend (Long, Long, Int) -> List<BlockedCall>

/**
 * Exports blocked call/SMS logs as CSV for analysis or evidence.
 *
 * Production exports use the cursor reader overloads so a large Room log is
 * never collected into one process-sized list or StringBuilder.
 */
object LogExporter {
    private const val EXPORT_BATCH_SIZE = 256

    suspend fun exportAsCsv(
        context: Context,
        calls: List<BlockedCall>,
        includeRawSmsBodies: Boolean = false,
    ) {
        shareCsv(
            context = context,
            prefix = "callshield_log_",
            subject = R.string.log_export_subject,
            chooserTitle = R.string.log_export_chooser_title,
        ) { writer ->
            writeCsvHeader(writer)
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            calls.forEach { call -> appendCsvRow(writer, call, includeRawSmsBodies, dateFormat) }
            calls.isNotEmpty()
        }
    }

    /**
     * Share every log row by reading ordered, bounded cursor pages.
     * Returns false when the log is empty so callers can show the existing
     * localized empty-state message without creating an empty share file.
     */
    suspend fun exportAsCsv(
        context: Context,
        readBatch: BlockedCallBatchReader,
        includeRawSmsBodies: Boolean = false,
    ): Boolean =
        shareCsv(
            context = context,
            prefix = "callshield_log_",
            subject = R.string.log_export_subject,
            chooserTitle = R.string.log_export_chooser_title,
        ) { writer ->
            writeCsvBatches(writer, readBatch, includeRawSmsBodies)
        }

    /**
     * Share the regulator/redress-shaped call list. It intentionally contains
     * only blocked calls, date, time, calling number, and the stored reason —
     * never SMS bodies, contacts, or unrelated allowed activity.
     */
    suspend fun exportRedressAsCsv(
        context: Context,
        calls: List<BlockedCall>,
    ) {
        shareCsv(
            context = context,
            prefix = "callshield_redress_",
            subject = R.string.redress_export_subject,
            chooserTitle = R.string.redress_export_chooser_title,
        ) { writer ->
            writeRedressHeader(writer)
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
            var wroteRow = false
            calls.forEach { call ->
                wroteRow = appendRedressRow(writer, call, dateFormat, timeFormat) || wroteRow
            }
            wroteRow
        }
    }

    /** Share only blocked calls from a bounded cursor reader. */
    suspend fun exportRedressAsCsv(
        context: Context,
        readBatch: BlockedCallBatchReader,
    ): Boolean =
        shareCsv(
            context = context,
            prefix = "callshield_redress_",
            subject = R.string.redress_export_subject,
            chooserTitle = R.string.redress_export_chooser_title,
        ) { writer ->
            writeRedressBatches(writer, readBatch)
        }

    /** Testable streaming writer; the production share path passes a file writer. */
    internal suspend fun writeCsvBatches(
        output: Appendable,
        readBatch: BlockedCallBatchReader,
        includeRawSmsBodies: Boolean = false,
    ): Boolean {
        writeCsvHeader(output)
        var wroteRow = false
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        forEachBlockedCall(readBatch) { call ->
            appendCsvRow(output, call, includeRawSmsBodies, dateFormat)
            wroteRow = true
        }
        return wroteRow
    }

    /** Testable streaming writer for the redress subset. */
    internal suspend fun writeRedressBatches(
        output: Appendable,
        readBatch: BlockedCallBatchReader,
    ): Boolean {
        writeRedressHeader(output)
        var wroteRow = false
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
        forEachBlockedCall(readBatch) { call ->
            wroteRow = appendRedressRow(output, call, dateFormat, timeFormat) || wroteRow
        }
        return wroteRow
    }

    fun exportToCsv(
        calls: List<BlockedCall>,
        includeRawSmsBodies: Boolean = false,
    ): String {
        val output = StringBuilder()
        writeCsvHeader(output)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        calls.forEach { call -> appendCsvRow(output, call, includeRawSmsBodies, dateFormat) }
        return output.toString()
    }

    fun exportRedressToCsv(calls: List<BlockedCall>): String {
        val output = StringBuilder()
        writeRedressHeader(output)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
        calls.forEach { call -> appendRedressRow(output, call, dateFormat, timeFormat) }
        return output.toString()
    }

    private suspend fun shareCsv(
        context: Context,
        prefix: String,
        subject: Int,
        chooserTitle: Int,
        write: suspend (Writer) -> Boolean,
    ): Boolean {
        val chooserIntent =
            withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "exports")
                dir.mkdirs()
                // Only prune the matching export family; another share target
                // may still be reading the other family through FileProvider.
                dir
                    .listFiles { file -> file.name.startsWith(prefix) }
                    ?.forEach { it.delete() }
                val file = File(dir, "$prefix${System.currentTimeMillis()}.csv")
                val writer = file.outputStream().bufferedWriter(Charsets.UTF_8)
                val wroteRows =
                    try {
                        write(writer)
                    } finally {
                        writer.close()
                    }
                if (!wroteRows) {
                    file.delete()
                    return@withContext null
                }

                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, context.getString(subject))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                Intent
                    .createChooser(intent, context.getString(chooserTitle))
                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            } ?: return false

        withContext(Dispatchers.Main) {
            context.startActivity(chooserIntent)
        }
        return true
    }

    private suspend fun forEachBlockedCall(
        readBatch: BlockedCallBatchReader,
        action: suspend (BlockedCall) -> Unit,
    ) {
        var beforeTimestamp = Long.MAX_VALUE
        var beforeId = Long.MAX_VALUE
        while (true) {
            val batch = readBatch(beforeTimestamp, beforeId, EXPORT_BATCH_SIZE)
            if (batch.isEmpty()) return

            batch.forEach { call -> action(call) }

            val last = batch.last()
            if (last.timestamp > beforeTimestamp ||
                (last.timestamp == beforeTimestamp && last.id >= beforeId)
            ) {
                // Do not spin forever if a future reader violates the cursor
                // contract. The rows already written remain valid and ordered.
                return
            }
            beforeTimestamp = last.timestamp
            beforeId = last.id
            if (batch.size < EXPORT_BATCH_SIZE) return
        }
    }

    private fun writeCsvHeader(output: Appendable) {
        output.appendLine("Number,Date,Type,IsCall,ReasonCode,RuleId,Confidence,PipelineDiagnostic,SMSBody")
    }

    private fun appendCsvRow(
        output: Appendable,
        call: BlockedCall,
        includeRawSmsBodies: Boolean,
        dateFormat: SimpleDateFormat,
    ) {
        val body =
            if (includeRawSmsBodies) {
                call.smsBody.orEmpty()
            } else {
                SmsBodyRedactor.redactForCsv(call.smsBody)
            }
        output.appendLine(
            listOf(
                csvEscape(call.number),
                csvEscape(dateFormat.format(Date(call.timestamp))),
                csvEscape(call.type),
                call.isCall.toString(),
                csvEscape(call.reasonCode.wireValue),
                call.ruleId?.toString().orEmpty(),
                call.confidence.toString(),
                csvEscape(call.pipelineDiagnostic.orEmpty()),
                csvEscape(body),
            ).joinToString(","),
        )
    }

    private fun writeRedressHeader(output: Appendable) {
        output.appendLine("Date,Time,CallingNumber,ReasonCode,RuleId")
    }

    private fun appendRedressRow(
        output: Appendable,
        call: BlockedCall,
        dateFormat: SimpleDateFormat,
        timeFormat: SimpleDateFormat,
    ): Boolean {
        if (!call.isCall || !call.wasBlocked) return false
        output.appendLine(
            listOf(
                csvEscape(dateFormat.format(Date(call.timestamp))),
                csvEscape(timeFormat.format(Date(call.timestamp))),
                csvEscape(call.number),
                csvEscape(call.reasonCode.wireValue),
                call.ruleId?.toString().orEmpty(),
            ).joinToString(","),
        )
        return true
    }

    private fun csvEscape(value: String): String {
        // Neutralize spreadsheet formula injection: a cell beginning with
        // = + - @ (or tab/CR) is interpreted as a live formula by Excel/Sheets.
        // matchReason and (with raw bodies) the SMS text are attacker-influenced,
        // so prefix a single quote before quoting. Standard CSV-injection defense.
        val guarded =
            if (value.isNotEmpty() && value.first() in FORMULA_TRIGGERS) {
                "'$value"
            } else {
                value
            }
        val escaped =
            guarded
                .replace("\"", "\"\"")
                .replace("\n", " ")
                .replace("\r", "")
        return "\"$escaped\""
    }

    private val FORMULA_TRIGGERS = charArrayOf('=', '+', '-', '@', '\t', '\r')
}
