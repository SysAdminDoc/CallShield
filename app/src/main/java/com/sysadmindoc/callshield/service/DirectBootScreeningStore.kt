package com.sysadmindoc.callshield.service

import android.content.Context
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.model.SpamNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

/**
 * Minimal device-encrypted mirror used only before the first unlock after boot.
 * The full database and settings remain credential-encrypted; direct boot can
 * enforce explicit user blocks and essential call preferences only.
 */
internal object DirectBootScreeningStore {
    data class Snapshot(
        val ready: Boolean = false,
        val blockCallsEnabled: Boolean = true,
        val blockUnknownEnabled: Boolean = false,
        val silentVoicemailEnabled: Boolean = false,
        val blockedNumbers: Map<String, Long?> = emptyMap(),
    ) {
        fun isBlocked(
            normalizedNumber: String,
            now: Long = System.currentTimeMillis(),
        ): Boolean {
            if (normalizedNumber !in blockedNumbers) return false
            return blockedNumbers[normalizedNumber]?.let { it > now } ?: true
        }
    }

    private data class MirrorInput(
        val blockedNumbers: List<SpamNumber>,
        val blockCallsEnabled: Boolean,
        val blockUnknownEnabled: Boolean,
        val silentVoicemailEnabled: Boolean,
    )

    private const val PREFS_NAME = "direct_boot_screening"
    private const val KEY_READY = "ready"
    private const val KEY_BLOCK_CALLS = "block_calls"
    private const val KEY_BLOCK_UNKNOWN = "block_unknown"
    private const val KEY_SILENT_VOICEMAIL = "silent_voicemail"
    private const val KEY_BLOCKED_NUMBERS = "blocked_numbers"
    private const val ENTRY_SEPARATOR = '|'

    suspend fun observeAndMirror(
        context: Context,
        repository: SpamRepository,
    ) {
        combine(
            repository.getUserBlockedNumbers(),
            repository.blockCallsEnabled,
            repository.blockUnknownEnabled,
            repository.silentVoicemailEnabled,
        ) { blockedNumbers, blockCalls, blockUnknown, silentVoicemail ->
            MirrorInput(blockedNumbers, blockCalls, blockUnknown, silentVoicemail)
        }.collectLatest { input ->
            write(
                context = context,
                blockedNumbers = input.blockedNumbers,
                blockCallsEnabled = input.blockCallsEnabled,
                blockUnknownEnabled = input.blockUnknownEnabled,
                silentVoicemailEnabled = input.silentVoicemailEnabled,
            )
        }
    }

    suspend fun write(
        context: Context,
        blockedNumbers: List<SpamNumber>,
        blockCallsEnabled: Boolean,
        blockUnknownEnabled: Boolean,
        silentVoicemailEnabled: Boolean,
    ) = withContext(Dispatchers.IO) {
        val entries =
            blockedNumbers
                .asSequence()
                .filter(SpamNumber::isUserBlocked)
                .filter { number -> number.number.isNotBlank() && ENTRY_SEPARATOR !in number.number }
                .map { number -> "${number.expiresAt ?: 0L}$ENTRY_SEPARATOR${number.number}" }
                .toSet()
        check(
            preferences(context)
                .edit()
                .putBoolean(KEY_READY, true)
                .putBoolean(KEY_BLOCK_CALLS, blockCallsEnabled)
                .putBoolean(KEY_BLOCK_UNKNOWN, blockUnknownEnabled)
                .putBoolean(KEY_SILENT_VOICEMAIL, silentVoicemailEnabled)
                .putStringSet(KEY_BLOCKED_NUMBERS, entries)
                .commit(),
        ) { "Failed to persist the direct-boot screening mirror" }
    }

    fun read(context: Context): Snapshot {
        val prefs = preferences(context)
        val blocked =
            prefs
                .getStringSet(KEY_BLOCKED_NUMBERS, emptySet())
                .orEmpty()
                .mapNotNull(::decodeEntry)
                .toMap()
        return Snapshot(
            ready = prefs.getBoolean(KEY_READY, false),
            blockCallsEnabled = prefs.getBoolean(KEY_BLOCK_CALLS, true),
            blockUnknownEnabled = prefs.getBoolean(KEY_BLOCK_UNKNOWN, false),
            silentVoicemailEnabled = prefs.getBoolean(KEY_SILENT_VOICEMAIL, false),
            blockedNumbers = blocked,
        )
    }

    internal fun clearForTest(context: Context) {
        preferences(context).edit().clear().commit()
    }

    private fun decodeEntry(entry: String): Pair<String, Long?>? {
        val separator = entry.indexOf(ENTRY_SEPARATOR)
        if (separator <= 0 || separator == entry.lastIndex) return null
        val expiry = entry.substring(0, separator).toLongOrNull() ?: return null
        val number = entry.substring(separator + 1).takeIf(String::isNotBlank) ?: return null
        return number to expiry.takeIf { it > 0L }
    }

    private fun preferences(context: Context) =
        context
            .createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
