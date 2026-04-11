package com.sysadmindoc.callshield.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.sysadmindoc.callshield.data.model.BlockedCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Exports blocked call/SMS log as CSV for analysis or evidence.
 */
object LogExporter {

    suspend fun exportAsCsv(context: Context, calls: List<BlockedCall>) {
        val chooserIntent = withContext(Dispatchers.IO) {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val sb = StringBuilder()
            sb.appendLine("Number,Date,Type,IsCall,MatchReason,Confidence,SMSBody")

            for (call in calls) {
                val date = dateFormat.format(Date(call.timestamp))
                val body = csvEscape(call.smsBody ?: "")
                sb.appendLine("${csvEscape(call.number)},${csvEscape(date)},${csvEscape(call.type)},${call.isCall},${csvEscape(call.matchReason)},${call.confidence},$body")
            }

            val dir = File(context.cacheDir, "exports")
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "callshield_log_${System.currentTimeMillis()}.csv")
            file.writeText(sb.toString())

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "CallShield Blocked Log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            Intent.createChooser(intent, "Export log").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        withContext(Dispatchers.Main) {
            context.startActivity(chooserIntent)
        }
    }

    private fun csvEscape(value: String): String {
        val escaped = value.replace("\"", "\"\"").replace("\n", " ").replace("\r", "")
        return "\"$escaped\""
    }
}
