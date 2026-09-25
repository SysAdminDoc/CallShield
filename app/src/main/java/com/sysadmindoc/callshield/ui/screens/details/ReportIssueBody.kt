package com.sysadmindoc.callshield.ui.screens.details

import android.content.res.Resources
import com.sysadmindoc.callshield.R

/**
 * Body of the GitHub issue the Report button opens. The count is how many
 * calls and texts from the number CallShield has logged. A number looked up
 * by hand has none, and "Seen 0 times" read as if the reporter had never
 * seen it, so that case says what it is instead.
 */
fun reportIssueBody(
    resources: Resources,
    number: String,
    loggedEntries: Int,
): String =
    if (loggedEntries == 0) {
        resources.getString(R.string.detail_report_issue_body_unseen, number)
    } else {
        resources.getQuantityString(R.plurals.detail_report_issue_body, loggedEntries, number, loggedEntries)
    }
