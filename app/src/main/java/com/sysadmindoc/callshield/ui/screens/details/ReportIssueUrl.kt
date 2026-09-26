package com.sysadmindoc.callshield.ui.screens.details

import android.content.res.Resources
import android.net.Uri
import com.sysadmindoc.callshield.R

/**
 * The link the Report button opens: the tracker's spam form with its fields
 * filled in. The form labels the issue itself; a `labels` query parameter
 * only works for people who can triage the repository, so in-app reports used
 * to arrive unlabelled.
 *
 * The description counts how many calls and texts from the number CallShield
 * has logged. A number looked up by hand has none, and "Seen 0 times" read as
 * if the reporter had never seen it, so that case says what it is instead.
 */
fun reportIssueUrl(
    resources: Resources,
    number: String,
    loggedEntries: Int,
): String {
    val description =
        if (loggedEntries == 0) {
            resources.getString(R.string.detail_report_issue_unseen)
        } else {
            resources.getQuantityString(R.plurals.detail_report_issue_seen, loggedEntries, loggedEntries)
        }
    return Uri
        .parse("https://github.com/SysAdminDoc/CallShield/issues/new")
        .buildUpon()
        .appendQueryParameter("template", "spam_report.yml")
        .appendQueryParameter("title", resources.getString(R.string.detail_report_issue_title, number))
        .appendQueryParameter("number", number)
        .appendQueryParameter("type", resources.getString(R.string.detail_report_issue_type))
        .appendQueryParameter("description", description)
        .build()
        .toString()
}
