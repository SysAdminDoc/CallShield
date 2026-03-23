package com.sysadmindoc.callshield.data

import android.content.Context
import android.content.Intent

/**
 * Share a spam number to other apps as a warning.
 */
object SpamSharer {

    fun share(context: Context, number: String, reason: String = "") {
        val formatted = PhoneFormatter.format(number)
        val location = com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup.lookup(number)
        val text = buildString {
            append("Spam alert: $formatted")
            if (location != null) append(" ($location)")
            append(" is a known spam number.")
            if (reason.isNotEmpty()) append(" Type: ${reason.replace("_", " ")}.")
            append(" Blocked by CallShield.")
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "Spam Number Warning: $formatted")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share spam warning").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
