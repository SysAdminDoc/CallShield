package com.sysadmindoc.callshield.data

import android.content.Context
import android.provider.Telephony
import java.util.Calendar

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
object SmsContextChecker {

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

    /** Strip non-digits, drop leading country code, return last 10 digits. */
    private fun normalize(number: String): String {
        val digits = number.filter { it.isDigit() }
        return when {
            digits.length == 11 && digits.startsWith("1") -> digits.drop(1)
            digits.length >= 10 -> digits.takeLast(10)
            else -> digits
        }
    }
}
