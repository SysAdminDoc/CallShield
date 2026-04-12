package com.sysadmindoc.callshield.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.sysadmindoc.callshield.R

/**
 * Launches a pre-seeded FTC fraud-report flow for a given number.
 *
 * The FTC's reportfraud.ftc.gov is a multi-step wizard that does NOT
 * accept deeplink query parameters — we can't auto-fill the phone-number
 * field directly. The honest UX is:
 *   1. Copy the number to the clipboard.
 *   2. Open the FTC form in the user's browser.
 *   3. Show a Toast telling them to paste into the form's phone-number field.
 *
 * This turns a tedious multi-minute task into a ~10-second one while
 * being truthful about the mechanics. Aligns with the project's
 * community-powered-database ethos — CallShield users become a reporting
 * force without giving up privacy or having an account created for them.
 *
 * FCC option kept as a fallback for users who prefer filing there — the
 * FCC's consumercomplaints.fcc.gov actually has a 6-step "unwanted call"
 * form at a known URL.
 */
object ReportFraudHelper {

    const val FTC_REPORT_URL = "https://reportfraud.ftc.gov/"
    const val FCC_REPORT_URL = "https://consumercomplaints.fcc.gov/hc/en-us/requests/new?ticket_form_id=39744"

    enum class Authority { FTC, FCC }

    /**
     * Copy the number + launch the agency's report form in the browser.
     * Returns true on success, false if no browser was available.
     */
    fun report(context: Context, number: String, authority: Authority = Authority.FTC): Boolean {
        copyToClipboard(context, number)

        val url = when (authority) {
            Authority.FTC -> FTC_REPORT_URL
            Authority.FCC -> FCC_REPORT_URL
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Toast.makeText(
                context,
                context.getString(R.string.ftc_report_toast, PhoneFormatter.format(number)),
                Toast.LENGTH_LONG
            ).show()
            true
        } catch (_: Exception) {
            Toast.makeText(context, R.string.ftc_report_no_browser, Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun copyToClipboard(context: Context, number: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("CallShield spam number", number))
    }
}
