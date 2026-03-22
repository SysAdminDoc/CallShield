package com.sysadmindoc.callshield.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

/**
 * Feature 12: Export/import blocklist.
 * Exports user blocklist as JSON, imports from JSON file.
 */
object BlocklistExporter {

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    data class ExportData(
        val version: Int = 1,
        val app: String = "CallShield",
        val exported: String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()),
        val numbers: List<ExportNumber>
    )

    data class ExportNumber(
        val number: String,
        val type: String,
        val description: String
    )

    fun exportToJson(numbers: List<SpamNumber>): String {
        val data = ExportData(
            numbers = numbers.map { ExportNumber(it.number, it.type, it.description) }
        )
        val adapter = moshi.adapter(ExportData::class.java).indent("  ")
        return adapter.toJson(data)
    }

    fun exportAndShare(context: Context, numbers: List<SpamNumber>) {
        val json = exportToJson(numbers)
        val file = File(context.cacheDir, "callshield_blocklist.json")
        file.writeText(json)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "CallShield Blocklist")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share blocklist").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun parseImport(json: String): List<ExportNumber> {
        return try {
            val adapter = moshi.adapter(ExportData::class.java)
            adapter.fromJson(json)?.numbers ?: emptyList()
        } catch (_: Exception) {
            // Try parsing as a simple array of numbers
            try {
                val type = Types.newParameterizedType(List::class.java, ExportNumber::class.java)
                val adapter = moshi.adapter<List<ExportNumber>>(type)
                adapter.fromJson(json) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    suspend fun importNumbers(context: Context, json: String): Int {
        val repo = SpamRepository.getInstance(context)
        val numbers = parseImport(json)
        var count = 0
        for (n in numbers) {
            repo.blockNumber(n.number, n.type, n.description)
            count++
        }
        return count
    }

    suspend fun importFromUri(context: Context, uri: Uri): Int {
        val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return 0
        return importNumbers(context, json)
    }
}
