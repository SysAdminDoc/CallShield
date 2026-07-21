package com.sysadmindoc.callshield.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.model.BlockedCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports blocked call/SMS log as CSV for analysis or evidence.
 */
object LogExporter {
    suspend fun exportAsCsv(
        context: Context,
        calls: List<BlockedCall>,
        includeRawSmsBodies: Boolean = false,
    ) {
        val chooserIntent =
            withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "exports")
                dir.mkdirs()
                // Only prune our own prior log exports; `cacheDir/exports` is
                // shared with BlocklistExporter, and deleting everything would
                // nuke any in-flight blocklist file that's still being shared.
                dir
                    .listFiles { file -> file.name.startsWith("callshield_log_") }
                    ?.forEach { it.delete() }
                val file = File(dir, "callshield_log_${System.currentTimeMillis()}.csv")
                file.writeText(exportToCsv(calls, includeRawSmsBodies))

                val uri =
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.log_export_subject))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                Intent
                    .createChooser(intent, context.getString(R.string.log_export_chooser_title))
                    .apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
            }

        withContext(Dispatchers.Main) {
            context.startActivity(chooserIntent)
        }
    }

    fun exportToCsv(
        calls: List<BlockedCall>,
        includeRawSmsBodies: Boolean = false,
    ): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.appendLine("Number,Date,Type,IsCall,MatchReason,Confidence,SMSBody")

        for (call in calls) {
            val date = dateFormat.format(Date(call.timestamp))
            val body =
                if (includeRawSmsBodies) {
                    call.smsBody.orEmpty()
                } else {
                    SmsBodyRedactor.redactForCsv(call.smsBody)
                }
            sb.appendLine(
                listOf(
                    csvEscape(call.number),
                    csvEscape(date),
                    csvEscape(call.type),
                    call.isCall.toString(),
                    csvEscape(call.matchReason),
                    call.confidence.toString(),
                    csvEscape(body),
                ).joinToString(","),
            )
        }

        return sb.toString()
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
