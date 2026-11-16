package com.sysadmindoc.callshield.service

import android.content.Context
import android.text.format.DateFormat
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.BlockingProfiles
import com.sysadmindoc.callshield.data.SpamHeuristics
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.UnknownCallWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Turning protection off while someone the user doesn't know is on the
 * phone, or in the [UnknownCallWindow.AFTER_CALL_MS] after that call, waits
 * until then. Scam callers talk people into it ("turn off call blocking so
 * our fraud team can reach you"), and Apple holds the same kind of change for
 * up to a day. Turning something back on, or any change with no such call
 * around, applies at once and drops what was waiting for it.
 */
object ProtectionHold {
    enum class Switch(
        val key: String,
    ) {
        BLOCK_CALLS("block_calls"),
        BLOCK_SMS("block_sms"),
        SMS_CONTENT("sms_content"),
    }

    /** What's waiting. [releaseAt] is null while the call is still going. */
    data class Pending(
        val switchesOff: Set<Switch> = emptySet(),
        val contactGroups: Set<String>? = null,
        val releaseAt: Long? = null,
    ) {
        val isEmpty: Boolean get() = switchesOff.isEmpty() && contactGroups == null
    }

    sealed interface Outcome {
        data object Applied : Outcome

        /** Waits until [releaseAt], or until the call ends and the window after it passes when that's null. */
        data class Held(
            val releaseAt: Long?,
        ) : Outcome
    }

    data class Replaced(
        val previous: BlockingProfiles.Snapshot,
        val outcome: Outcome,
    )

    private const val TAG = "ProtectionHold"
    private const val PREFS = "protection_hold"
    private const val KEY_SWITCHES_OFF = "switches_off"
    private const val KEY_CONTACT_GROUPS = "contact_groups"
    private const val KEY_RELEASE_AT = "release_at"
    internal const val WORK_NAME = "callshield_protection_hold"

    /** How often a hold looks again while the call is still going. */
    internal const val RECHECK_MS = 60_000L

    /** The window counts up to and including its last millisecond, so the release runs just after. */
    private const val RELEASE_MARGIN_MS = 1_000L

    private val mutex = Mutex()
    private val state = MutableStateFlow(Pending())

    @Volatile private var loaded = false

    fun pending(context: Context): StateFlow<Pending> {
        load(context)
        return state.asStateFlow()
    }

    /** Turns [switch] on or off. Off waits while a call from someone the user doesn't know still counts. */
    suspend fun setSwitch(
        context: Context,
        switch: Switch,
        enabled: Boolean,
        repo: SpamRepository = SpamRepository.getInstance(context),
        window: UnknownCallWindow = UnknownCallWindow.shared,
        isContact: (String) -> Boolean = { SpamHeuristics.isInContacts(context, it) },
    ): Outcome =
        locked {
            val pending = load(context)
            val hold = if (!enabled && read(repo, switch)) holdFor(context, window, isContact) else null
            if (hold == null) {
                write(context, pending.copy(switchesOff = pending.switchesOff - switch))
                apply(repo, switch, enabled)
                Outcome.Applied
            } else {
                write(context, pending.copy(switchesOff = pending.switchesOff + switch, releaseAt = hold.closesAt))
                schedule(context, delayFor(hold, System.currentTimeMillis()), ExistingWorkPolicy.REPLACE)
                Outcome.Held(hold.closesAt)
            }
        }

    /** Limits automatic contact trust to [groups] (empty for every contact). Trusting more contacts waits. */
    suspend fun setContactGroups(
        context: Context,
        groups: Set<String>,
        repo: SpamRepository = SpamRepository.getInstance(context),
        window: UnknownCallWindow = UnknownCallWindow.shared,
        isContact: (String) -> Boolean = { SpamHeuristics.isInContacts(context, it) },
    ): Outcome =
        locked {
            val pending = load(context)
            val hold = if (widensTrust(repo.selectedContactGroups.first(), groups)) holdFor(context, window, isContact) else null
            if (hold == null) {
                write(context, pending.copy(contactGroups = null))
                repo.setSelectedContactGroups(groups)
                SpamHeuristics.clearContactCache()
                Outcome.Applied
            } else {
                write(context, pending.copy(contactGroups = groups, releaseAt = hold.closesAt))
                schedule(context, delayFor(hold, System.currentTimeMillis()), ExistingWorkPolicy.REPLACE)
                Outcome.Held(hold.closesAt)
            }
        }

    /**
     * Applies a profile's [settings]. When it turns call or text screening
     * off while a call counts, the rest applies now and those switches wait.
     */
    suspend fun replaceSettings(
        context: Context,
        settings: BlockingProfiles.Settings,
        profileName: String?,
        repo: SpamRepository = SpamRepository.getInstance(context),
        window: UnknownCallWindow = UnknownCallWindow.shared,
        isContact: (String) -> Boolean = { SpamHeuristics.isInContacts(context, it) },
    ): Replaced =
        locked {
            val pending = load(context)
            val turningOff =
                buildSet {
                    if (!settings.blockCalls && repo.blockCallsEnabled.first()) add(Switch.BLOCK_CALLS)
                    if (!settings.analyzeSms && repo.blockSmsEnabled.first()) add(Switch.BLOCK_SMS)
                }
            val hold = if (turningOff.isNotEmpty()) holdFor(context, window, isContact) else null
            val others = pending.switchesOff - Switch.BLOCK_CALLS - Switch.BLOCK_SMS
            if (hold == null) {
                write(context, pending.copy(switchesOff = others))
                Replaced(repo.replaceBlockingSettings(settings, profileName), Outcome.Applied)
            } else {
                val now =
                    settings.copy(
                        blockCalls = settings.blockCalls || Switch.BLOCK_CALLS in turningOff,
                        analyzeSms = settings.analyzeSms || Switch.BLOCK_SMS in turningOff,
                    )
                val previous = repo.replaceBlockingSettings(now, profileName)
                write(context, pending.copy(switchesOff = others + turningOff, releaseAt = hold.closesAt))
                schedule(context, delayFor(hold, System.currentTimeMillis()), ExistingWorkPolicy.REPLACE)
                Replaced(previous, Outcome.Held(hold.closesAt))
            }
        }

    /** Applies what's waiting once no call counts. Returns how long until it should look again, or null when it's done. */
    suspend fun releaseDue(
        context: Context,
        repo: SpamRepository = SpamRepository.getInstance(context),
        window: UnknownCallWindow = UnknownCallWindow.shared,
        isContact: (String) -> Boolean = { SpamHeuristics.isInContacts(context, it) },
    ): Long? =
        locked {
            val pending = load(context)
            if (pending.isEmpty) return@locked null
            val hold = holdFor(context, window, isContact)
            if (hold != null) {
                if (hold.closesAt != pending.releaseAt) write(context, pending.copy(releaseAt = hold.closesAt))
                return@locked delayFor(hold, System.currentTimeMillis())
            }
            pending.switchesOff.forEach { apply(repo, it, false) }
            pending.contactGroups?.let {
                repo.setSelectedContactGroups(it)
                SpamHeuristics.clearContactCache()
            }
            write(context, Pending())
            null
        }

    /**
     * The call [window] was watching has ended, so what's waiting gets its
     * release time now rather than at the next minute's look. Until then the
     * screen could only say "after your call ends" with the call already over.
     */
    suspend fun callEnded(
        context: Context,
        window: UnknownCallWindow = UnknownCallWindow.shared,
        isContact: (String) -> Boolean = { SpamHeuristics.isInContacts(context, it) },
    ) {
        if (load(context).isEmpty) return
        // The call watcher runs on a scope with no exception handler, so a throw here would end the process.
        // The minute's look is still scheduled and does the same work.
        try {
            releaseDue(context, window = window, isContact = isContact)?.let { schedule(context, it, ExistingWorkPolicy.REPLACE) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Couldn't set the release time when the call ended", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't set the release time when the call ended", e)
        }
    }

    /** Whether [requested] trusts contacts [current] didn't. An empty set trusts every contact. */
    internal fun widensTrust(
        current: Set<String>,
        requested: Set<String>,
    ): Boolean = current.isNotEmpty() && (requested.isEmpty() || !current.containsAll(requested))

    /** The toast for a change that's waiting. */
    fun heldNotice(
        context: Context,
        releaseAt: Long?,
    ): String =
        if (releaseAt == null) {
            context.getString(R.string.protection_hold_after_call)
        } else {
            context.getString(R.string.protection_hold_at, formatTime(context, releaseAt))
        }

    /** The line under a control whose change is waiting. */
    fun pendingNote(
        context: Context,
        releaseAt: Long?,
    ): String =
        if (releaseAt == null) {
            context.getString(R.string.protection_hold_pending_after_call)
        } else {
            context.getString(R.string.protection_hold_pending_at, formatTime(context, releaseAt))
        }

    internal fun schedule(
        context: Context,
        delayMs: Long,
        policy: ExistingWorkPolicy,
    ) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            policy,
            OneTimeWorkRequestBuilder<ProtectionHoldWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build(),
        )
    }

    internal fun resetForTests() {
        loaded = false
        state.value = Pending()
    }

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    private fun holdFor(
        context: Context,
        window: UnknownCallWindow,
        isContact: (String) -> Boolean,
    ): UnknownCallWindow.Open? {
        val open = CodeDuringCallWarning.openCall(context, window) ?: return null
        // Asked now, not when it rang: the user may have saved the caller since.
        if (open.caller.isNotEmpty() && isContact(open.caller)) return null
        return open
    }

    private fun delayFor(
        hold: UnknownCallWindow.Open,
        now: Long,
    ): Long = hold.closesAt?.let { (it - now).coerceAtLeast(0L) + RELEASE_MARGIN_MS } ?: RECHECK_MS

    private suspend fun read(
        repo: SpamRepository,
        switch: Switch,
    ): Boolean =
        when (switch) {
            Switch.BLOCK_CALLS -> repo.blockCallsEnabled.first()
            Switch.BLOCK_SMS -> repo.blockSmsEnabled.first()
            Switch.SMS_CONTENT -> repo.smsContentEnabled.first()
        }

    private suspend fun apply(
        repo: SpamRepository,
        switch: Switch,
        enabled: Boolean,
    ) {
        when (switch) {
            Switch.BLOCK_CALLS -> repo.setBlockCalls(enabled)
            Switch.BLOCK_SMS -> repo.setBlockSms(enabled)
            Switch.SMS_CONTENT -> repo.setSmsContent(enabled)
        }
    }

    private fun formatTime(
        context: Context,
        time: Long,
    ): String = DateFormat.getTimeFormat(context).format(Date(time))

    private fun load(context: Context): Pending {
        if (!loaded) {
            synchronized(this) {
                if (!loaded) {
                    state.value = readSaved(context)
                    loaded = true
                }
            }
        }
        return state.value
    }

    private fun readSaved(context: Context): Pending =
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val keys = prefs.getStringSet(KEY_SWITCHES_OFF, null).orEmpty()
            Pending(
                switchesOff = Switch.entries.filter { it.key in keys }.toSet(),
                contactGroups = if (prefs.contains(KEY_CONTACT_GROUPS)) prefs.getStringSet(KEY_CONTACT_GROUPS, null)?.toSet() else null,
                releaseAt = prefs.getLong(KEY_RELEASE_AT, -1L).takeIf { it >= 0L },
            )
        } catch (e: IllegalStateException) {
            // Before the first unlock there's no app storage, so nothing was saved there either.
            Log.w(TAG, "Couldn't read the held changes", e)
            Pending()
        }

    private fun write(
        context: Context,
        pending: Pending,
    ) {
        state.value = pending
        try {
            val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
            if (!pending.isEmpty) {
                edit.putStringSet(KEY_SWITCHES_OFF, pending.switchesOff.map { it.key }.toSet())
                pending.contactGroups?.let { edit.putStringSet(KEY_CONTACT_GROUPS, it) }
                edit.putLong(KEY_RELEASE_AT, pending.releaseAt ?: -1L)
            }
            // Committed, not applied: the release has to find it after the process is gone.
            edit.commit()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Couldn't save the held changes", e)
        }
    }
}
