package com.sysadmindoc.callshield.data

import android.content.Context
import android.provider.Telephony
import com.sysadmindoc.callshield.util.filterAsciiDigits
import java.util.Calendar
import javax.inject.Inject

/**
 * SMS Conversation Context Checker
 *
 * Determines whether a given number is a trusted sender by inspecting
 * the device's local SMS history. This catches cases that neither the
 * spam database nor heuristics can: a number that has exchanged real
 * messages with the user is almost never spam.
 *
 * Two trust signals (either is sufficient):
 *  1. We have previously SENT at least one SMS to this number.
 *  2. We have RECEIVED messages from this number on 2+ distinct days
 *     (indicates an ongoing relationship, not a one-shot blast).
 *
 * Requires READ_SMS permission (already declared in AndroidManifest).
 * All queries are local — no network access.
 */
class SmsContextChecker @Inject constructor() {

    /**
     * Returns true if the user has ever sent an SMS to this number.
     * A sent message is strong evidence of a known, legitimate contact.
     *
     * Uses a LIKE pre-filter on the last 7 digits so the content provider
     * only returns candidate rows instead of the entire sent folder.
     * Normalizes in Kotlin afterward to handle +1 / leading-country-code
     * variants that LIKE can't express.
     */
    fun hasSentMessageTo(context: Context, number: String): Boolean {
        val normalized = normalize(number)
        if (normalized.isEmpty()) return false
        val likeSuffix = normalized.takeLast(7)

        return try {
            context.contentResolver.query(
                Telephony.Sms.Sent.CONTENT_URI,
                arrayOf(Telephony.Sms.Sent.ADDRESS),
                "${Telephony.Sms.Sent.ADDRESS} LIKE ?",
                arrayOf("%$likeSuffix"),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val address = cursor.getString(0) ?: continue
                    if (normalize(address) == normalized) return true
                }
                false
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns true if the user has received messages from this number
     * on at least 2 different calendar days.
     *
     * Single-day multi-blast is a common spam pattern; genuine contacts
     * message across multiple days.
     *
     * Uses a LIKE pre-filter on the last 7 digits (same rationale as
     * [hasSentMessageTo]) to avoid loading the entire inbox.
     */
    fun hasRecurringConversation(context: Context, number: String): Boolean {
        val normalized = normalize(number)
        if (normalized.isEmpty()) return false
        val likeSuffix = normalized.takeLast(7)

        return try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.Inbox.ADDRESS, Telephony.Sms.Inbox.DATE),
                "${Telephony.Sms.Inbox.ADDRESS} LIKE ?",
                arrayOf("%$likeSuffix"),
                "${Telephony.Sms.Inbox.DATE} ASC"
            )?.use { cursor ->
                val days = mutableSetOf<String>()
                val cal = Calendar.getInstance()
                while (cursor.moveToNext()) {
                    val address = cursor.getString(0) ?: continue
                    if (normalize(address) != normalized) continue
                    cal.timeInMillis = cursor.getLong(1)
                    days.add("${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH) + 1}-${cal.get(Calendar.DAY_OF_MONTH)}")
                    if (days.size >= 2) return true
                }
                false
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Combined trust check — true if either signal fires.
     * Called from SpamRepository before keyword/content analysis.
     */
    fun isTrustedSender(context: Context, number: String): Boolean =
        hasSentMessageTo(context, number) || hasRecurringConversation(context, number)

    /**
     * Detect short-window SMS floods from unknown senders.
     *
     * The current incoming message may not be visible in Telephony yet,
     * especially on newer Android releases that can delay OTP-related SMS
     * broadcasts. Count it explicitly, but avoid double-counting if the inbox
     * provider already exposed a near-now row for the same sender.
     */
    fun findRecentBurst(
        context: Context,
        number: String,
        nowMillis: Long = System.currentTimeMillis(),
        config: SmsBurstConfig = SmsBurstConfig(),
    ): SmsBurstSignal? {
        val query = buildRecentIncomingSmsQuery(nowMillis, config.windowMinutes)
        val observations = mutableListOf<SmsBurstObservation>()

        return try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.Inbox.ADDRESS, Telephony.Sms.Inbox.DATE),
                query.selection,
                query.selectionArgs,
                "${Telephony.Sms.Inbox.DATE} DESC",
            )?.use { cursor ->
                val addressIndex = cursor.getColumnIndex(Telephony.Sms.Inbox.ADDRESS)
                val dateIndex = cursor.getColumnIndex(Telephony.Sms.Inbox.DATE)
                if (addressIndex < 0 || dateIndex < 0) {
                    return null
                }
                while (cursor.moveToNext() && observations.size < MAX_SMS_BURST_ROWS) {
                    observations += SmsBurstObservation(
                        address = cursor.getString(addressIndex) ?: "",
                        timestamp = cursor.getLong(dateIndex),
                    )
                }
            }
            evaluateSmsBurst(
                observations = observations,
                sender = number,
                nowMillis = nowMillis,
                config = config,
            )
        } catch (_: Exception) {
            null
        }
    }

    internal fun buildRecentIncomingSmsQuery(
        nowMillis: Long,
        windowMinutes: Int,
    ): SmsInboxQuery {
        val safeWindowMinutes = windowMinutes.coerceAtLeast(1)
        val cutoff = (nowMillis - safeWindowMinutes * MILLIS_PER_MINUTE).toString()
        return SmsInboxQuery(
            selection = "${Telephony.Sms.Inbox.DATE} > ?",
            selectionArgs = arrayOf(cutoff),
        )
    }

    internal fun evaluateSmsBurst(
        observations: List<SmsBurstObservation>,
        sender: String,
        nowMillis: Long,
        config: SmsBurstConfig = SmsBurstConfig(),
    ): SmsBurstSignal? {
        val normalizedSender = normalize(sender)
        if (normalizedSender.isEmpty()) return null

        val safeWindowMinutes = config.windowMinutes.coerceAtLeast(1)
        val cutoff = nowMillis - safeWindowMinutes * MILLIS_PER_MINUTE
        val recent =
            observations.mapNotNull { observation ->
                val normalized = normalize(observation.address)
                if (normalized.isNotEmpty() && observation.timestamp > cutoff) {
                    normalized to observation.timestamp
                } else {
                    null
                }
            }

        val providerAlreadyContainsCurrent =
            recent.any { (normalized, timestamp) ->
                normalized == normalizedSender &&
                    kotlin.math.abs(nowMillis - timestamp) <= CURRENT_SMS_DUPLICATE_GRACE_MS
            }
        val senderCount =
            recent.count { (normalized, _) -> normalized == normalizedSender } +
                if (providerAlreadyContainsCurrent) 0 else 1
        val senderSignal =
            if (senderCount >= config.senderThreshold.coerceAtLeast(2)) {
                SmsBurstSignal(
                    kind = SmsBurstKind.SENDER,
                    count = senderCount,
                    windowMinutes = safeWindowMinutes,
                )
            } else {
                null
            }
        val prefixSignal =
            if (senderSignal == null) {
                evaluatePrefixBurst(
                    normalizedSender = normalizedSender,
                    recent = recent,
                    windowMinutes = safeWindowMinutes,
                    prefixThreshold = config.prefixThreshold,
                )
            } else {
                null
            }

        return senderSignal ?: prefixSignal
    }

    private fun evaluatePrefixBurst(
        normalizedSender: String,
        recent: List<Pair<String, Long>>,
        windowMinutes: Int,
        prefixThreshold: Int,
    ): SmsBurstSignal? {
        val prefix = smsBurstPrefix(normalizedSender) ?: return null
        val prefixSenders =
            recent.asSequence()
                .map { (normalized, _) -> normalized }
                .filter { smsBurstPrefix(it) == prefix }
                .toMutableSet()
                .apply { add(normalizedSender) }
        return if (prefixSenders.size >= prefixThreshold.coerceAtLeast(2)) {
            SmsBurstSignal(
                kind = SmsBurstKind.PREFIX,
                count = prefixSenders.size,
                windowMinutes = windowMinutes,
                prefix = prefix,
            )
        } else {
            null
        }
    }

    /** Strip non-digits, drop leading country code, return last 10 digits. */
    private fun normalize(number: String): String {
        val digits = filterAsciiDigits(number)
        return when {
            digits.length == 11 && digits.startsWith("1") -> digits.drop(1)
            digits.length >= 10 -> digits.takeLast(10)
            else -> digits
        }
    }

    private fun smsBurstPrefix(normalizedNumber: String): String? =
        if (normalizedNumber.length == SMS_BURST_PHONE_DIGITS) {
            normalizedNumber.take(SMS_BURST_PREFIX_DIGITS)
        } else {
            null
        }

    companion object {
        val shared: SmsContextChecker = SmsContextChecker()

        const val DEFAULT_SMS_BURST_WINDOW_MINUTES = 30
        const val DEFAULT_SMS_BURST_SENDER_THRESHOLD = 3
        const val DEFAULT_SMS_BURST_PREFIX_THRESHOLD = 5
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val CURRENT_SMS_DUPLICATE_GRACE_MS = 5_000L
        private const val MAX_SMS_BURST_ROWS = 500
        private const val SMS_BURST_PHONE_DIGITS = 10
        private const val SMS_BURST_PREFIX_DIGITS = 6

        fun hasSentMessageTo(context: Context, number: String): Boolean =
            shared.hasSentMessageTo(context, number)

        fun hasRecurringConversation(context: Context, number: String): Boolean =
            shared.hasRecurringConversation(context, number)

        fun isTrustedSender(context: Context, number: String): Boolean =
            shared.isTrustedSender(context, number)

        fun findRecentBurst(
            context: Context,
            number: String,
            nowMillis: Long = System.currentTimeMillis(),
            config: SmsBurstConfig = SmsBurstConfig(),
        ): SmsBurstSignal? =
            shared.findRecentBurst(
                context = context,
                number = number,
                nowMillis = nowMillis,
                config = config,
            )

        internal fun buildRecentIncomingSmsQuery(
            nowMillis: Long,
            windowMinutes: Int,
        ): SmsInboxQuery =
            shared.buildRecentIncomingSmsQuery(nowMillis, windowMinutes)

        internal fun evaluateSmsBurst(
            observations: List<SmsBurstObservation>,
            sender: String,
            nowMillis: Long,
            config: SmsBurstConfig = SmsBurstConfig(),
        ): SmsBurstSignal? =
            shared.evaluateSmsBurst(
                observations = observations,
                sender = sender,
                nowMillis = nowMillis,
                config = config,
            )
    }
}

data class SmsBurstConfig(
    val windowMinutes: Int = SmsContextChecker.DEFAULT_SMS_BURST_WINDOW_MINUTES,
    val senderThreshold: Int = SmsContextChecker.DEFAULT_SMS_BURST_SENDER_THRESHOLD,
    val prefixThreshold: Int = SmsContextChecker.DEFAULT_SMS_BURST_PREFIX_THRESHOLD,
)

data class SmsBurstSignal(
    val kind: SmsBurstKind,
    val count: Int,
    val windowMinutes: Int,
    val prefix: String? = null,
) {
    val description: String
        get() =
            when (kind) {
                SmsBurstKind.SENDER -> "$count messages from this sender in $windowMinutes minutes"
                SmsBurstKind.PREFIX -> "$count senders from prefix $prefix in $windowMinutes minutes"
            }
}

enum class SmsBurstKind {
    SENDER,
    PREFIX,
}

internal data class SmsBurstObservation(
    val address: String,
    val timestamp: Long,
)

internal data class SmsInboxQuery(
    val selection: String,
    val selectionArgs: Array<String>,
)
