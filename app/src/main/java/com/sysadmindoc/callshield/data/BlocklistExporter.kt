package com.sysadmindoc.callshield.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.model.SpamNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Feature 12: Export/import blocklist.
 * Exports user blocklist as JSON, imports from JSON file.
 */
object BlocklistExporter {
    private const val MIN_IMPORTED_DIGITS = 5

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    data class ImportResult(
        val importedCount: Int,
        val message: String,
        val success: Boolean,
    )

    data class ExportData(
        val version: Int = 1,
        val app: String = "CallShield",
        val exported: String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()),
        val numbers: List<ExportNumber>,
    )

    data class ExportNumber(
        val number: String,
        val type: String,
        val description: String,
    )

    fun exportToJson(numbers: List<SpamNumber>): String {
        val data =
            ExportData(
                numbers = numbers.map { ExportNumber(it.number, it.type, it.description) },
            )
        val adapter = moshi.adapter(ExportData::class.java).indent("  ")
        return adapter.toJson(data)
    }

    suspend fun exportAndShare(
        context: Context,
        numbers: List<SpamNumber>,
    ) {
        val chooserIntent =
            withContext(Dispatchers.IO) {
                val json = exportToJson(numbers)
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                dir.listFiles { file -> file.name.startsWith("callshield_blocklist_") }?.forEach { it.delete() }
                val file = File(dir, "callshield_blocklist_${System.currentTimeMillis()}.json")
                file.writeText(json)

                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val shareIntent =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.blocklist_export_subject))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                Intent.createChooser(shareIntent, context.getString(R.string.blocklist_export_chooser_title)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

        withContext(Dispatchers.Main) {
            context.startActivity(chooserIntent)
        }
    }

    fun parseImport(json: String): List<ExportNumber> {
        val parsed =
            parseExportEnvelope(json)
                ?: parseStructuredList(json)
                ?: parseSimpleNumberList(json)?.map { number ->
                    ExportNumber(number = number, type = "unknown", description = "")
                }
                ?: parsePlainTextNumbers(json)?.map { number ->
                    ExportNumber(number = number, type = "unknown", description = "")
                }
                ?: emptyList()

        return parsed
            .asSequence()
            .mapNotNull(::sanitizeImportEntry)
            .distinctBy { it.number }
            .take(MAX_IMPORT_ROWS)
            .toList()
    }

    suspend fun importNumbers(
        context: Context,
        json: String,
    ): ImportResult {
        val repo = SpamRepository.getInstance(context)
        val numbers = parseImport(json)
        if (numbers.isEmpty()) {
            return ImportResult(
                importedCount = 0,
                message = context.getString(R.string.blocklist_import_no_valid_numbers),
                success = false,
            )
        }

        var count = 0
        for (n in numbers) {
            repo.blockNumber(n.number, n.type, n.description)
            count++
        }
        return ImportResult(
            importedCount = count,
            message = context.resources.getQuantityString(R.plurals.blocklist_imported_numbers, count, count),
            success = true,
        )
    }

    suspend fun importFromUri(
        context: Context,
        uri: Uri,
    ): ImportResult =
        withContext(Dispatchers.IO) {
            try {
                val stream =
                    context.contentResolver.openInputStream(uri)
                        ?: return@withContext ImportResult(
                            importedCount = 0,
                            message = context.getString(R.string.blocklist_import_could_not_open),
                            success = false,
                        )
                // Bounded read — reject a pathologically large file instead of
                // materializing it whole into memory (OOM guard at the import
                // trust boundary).
                val json =
                    stream.readTextBounded()
                        ?: return@withContext ImportResult(
                            importedCount = 0,
                            message = context.getString(R.string.blocklist_import_fallback_read_error),
                            success = false,
                        )
                if (json.isBlank()) {
                    return@withContext ImportResult(
                        importedCount = 0,
                        message = context.getString(R.string.blocklist_import_empty),
                        success = false,
                    )
                }
                importNumbers(context, json)
            } catch (_: SecurityException) {
                ImportResult(
                    importedCount = 0,
                    message = context.getString(R.string.blocklist_import_could_not_access),
                    success = false,
                )
            } catch (e: Exception) {
                ImportResult(
                    importedCount = 0,
                    message =
                        context.getString(
                            R.string.blocklist_import_failed,
                            e.message ?: context.getString(R.string.blocklist_import_fallback_read_error),
                        ),
                    success = false,
                )
            }
        }

    private fun parseExportEnvelope(json: String): List<ExportNumber>? =
        try {
            val adapter = moshi.adapter(ExportData::class.java)
            adapter.fromJson(json)?.numbers
        } catch (_: Exception) {
            null
        }

    private fun parseStructuredList(json: String): List<ExportNumber>? =
        try {
            val type = Types.newParameterizedType(List::class.java, ExportNumber::class.java)
            val adapter = moshi.adapter<List<ExportNumber>>(type)
            adapter.fromJson(json)
        } catch (_: Exception) {
            null
        }

    private fun parseSimpleNumberList(json: String): List<String>? =
        try {
            val type = Types.newParameterizedType(List::class.java, String::class.java)
            val adapter = moshi.adapter<List<String>>(type)
            adapter.fromJson(json)
        } catch (_: Exception) {
            null
        }

    private fun parsePlainTextNumbers(json: String): List<String>? {
        val lines =
            json
                .lineSequence()
                .map { it.substringBefore('#').trim().trim(',') }
                .filter { it.isNotBlank() }
                .toList()

        return lines.takeIf { it.isNotEmpty() }
    }

    private fun sanitizeImportEntry(entry: ExportNumber): ExportNumber? {
        val normalizedNumber = normalizeImportedNumber(entry.number) ?: return null
        return ExportNumber(
            number = normalizedNumber,
            type = entry.type.trim().ifBlank { "unknown" },
            description = entry.description.trim(),
        )
    }

    private fun normalizeImportedNumber(rawNumber: String): String? {
        val trimmed = rawNumber.trim()
        val digits =
            com.sysadmindoc.callshield.util
                .filterAsciiDigits(trimmed)
        if (digits.length !in MIN_IMPORTED_DIGITS..15) {
            return null
        }
        return if (trimmed.startsWith("+")) "+$digits" else digits
    }
}
