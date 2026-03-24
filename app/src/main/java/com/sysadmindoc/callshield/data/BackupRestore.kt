package com.sysadmindoc.callshield.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.data.model.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full app backup/restore — settings, blocklist, whitelist, wildcard rules, call log.
 */
object BackupRestore {

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    data class Backup(
        val version: Int = 2,
        val app: String = "CallShield",
        val timestamp: Long = System.currentTimeMillis(),
        val blockedNumbers: List<BackupNumber> = emptyList(),
        val whitelistNumbers: List<BackupWhitelist> = emptyList(),
        val wildcardRules: List<BackupWildcard> = emptyList(),
        val keywordRules: List<BackupKeyword> = emptyList()
    )

    data class BackupNumber(val number: String, val type: String, val description: String, val source: String)
    data class BackupWhitelist(val number: String, val description: String)
    data class BackupWildcard(val pattern: String, val isRegex: Boolean, val description: String, val enabled: Boolean)
    data class BackupKeyword(val keyword: String, val caseSensitive: Boolean, val description: String, val enabled: Boolean)

    suspend fun createBackup(context: Context): String = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getInstance(context).spamDao()

        val numbers = dao.getUserBlockedNumbers().first().map {
            BackupNumber(it.number, it.type, it.description, it.source)
        }
        val whitelist = dao.getAllWhitelist().first().map {
            BackupWhitelist(it.number, it.description)
        }
        val wildcards = dao.getAllWildcardRules().first().map {
            BackupWildcard(it.pattern, it.isRegex, it.description, it.enabled)
        }
        val keywords = dao.getAllKeywordRules().first().map {
            BackupKeyword(it.keyword, it.caseSensitive, it.description, it.enabled)
        }

        val backup = Backup(
            blockedNumbers = numbers,
            whitelistNumbers = whitelist,
            wildcardRules = wildcards,
            keywordRules = keywords
        )

        val adapter = moshi.adapter(Backup::class.java).indent("  ")
        adapter.toJson(backup)
    }

    suspend fun shareBackup(context: Context) {
        val json = createBackup(context)
        val file = File(context.cacheDir, "callshield_backup.json")
        file.writeText(json)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "CallShield Backup")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Save backup").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    suspend fun restoreFromUri(context: Context, uri: Uri): RestoreResult = withContext(Dispatchers.IO) {
        try {
            val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                ?: return@withContext RestoreResult(false, "Could not read file")

            val adapter = moshi.adapter(Backup::class.java)
            val backup = adapter.fromJson(json)
                ?: return@withContext RestoreResult(false, "Invalid backup format")

            val repo = SpamRepository.getInstance(context)
            val dao = AppDatabase.getInstance(context).spamDao()

            var numbersRestored = 0
            for (n in backup.blockedNumbers) {
                repo.blockNumber(n.number, n.type, n.description)
                numbersRestored++
            }

            var whitelistRestored = 0
            for (w in backup.whitelistNumbers) {
                repo.addToWhitelist(w.number, w.description)
                whitelistRestored++
            }

            var rulesRestored = 0
            for (r in backup.wildcardRules) {
                dao.insertWildcardRule(WildcardRule(pattern = r.pattern, isRegex = r.isRegex, description = r.description, enabled = r.enabled))
                rulesRestored++
            }

            var keywordsRestored = 0
            for (k in backup.keywordRules) {
                dao.insertKeywordRule(SmsKeywordRule(keyword = k.keyword, caseSensitive = k.caseSensitive, description = k.description, enabled = k.enabled))
                keywordsRestored++
            }

            RestoreResult(true, "Restored $numbersRestored numbers, $whitelistRestored whitelist, $rulesRestored wildcard rules, $keywordsRestored keyword rules")
        } catch (e: Exception) {
            RestoreResult(false, "Error: ${e.message}")
        }
    }

    data class RestoreResult(val success: Boolean, val message: String)
}
