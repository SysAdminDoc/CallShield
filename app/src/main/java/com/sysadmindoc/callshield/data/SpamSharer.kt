package com.sysadmindoc.callshield.data

import android.content.Context
import android.content.Intent
import com.sysadmindoc.callshield.R

/**
 * Share a spam number to other apps as a warning.
 */
object SpamSharer {
    fun share(
        context: Context,
        number: String,
        reason: String = "",
    ) {
        val formatted = PhoneFormatter.format(number)
        val location =
            com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup
                .lookup(number)
        val locationText = location?.let { context.getString(R.string.share_spam_warning_location, it) }.orEmpty()
        val typeText =
            if (reason.isNotEmpty()) {
                context.getString(R.string.share_spam_warning_type, reason.replace("_", " "))
            } else {
                ""
            }
        val text = context.getString(R.string.share_spam_warning_text, formatted, locationText, typeText)

        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.share_spam_warning_subject, formatted))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.share_spam_warning_chooser_title)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}
