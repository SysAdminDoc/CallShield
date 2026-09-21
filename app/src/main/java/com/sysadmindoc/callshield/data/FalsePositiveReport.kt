package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.domain.model.SpamCheckResult

/**
 * The text a user shares to say CallShield flagged a number wrongly: the
 * number, the layer that decided, its confidence, and the app and database
 * versions, which is what it takes to find the rule at fault. It reads
 * nothing else from the result. A description can quote a text message or
 * name a contact, and nothing from contacts, messages or the call log belongs
 * in a report that ends up in a public discussion.
 */
internal object FalsePositiveReport {
    const val DISCUSSION_URL = "https://github.com/SysAdminDoc/CallShield/discussions/9"

    fun text(
        number: String,
        result: SpamCheckResult,
        appVersion: String,
        databaseVersion: Int?,
        databaseUpdated: String?,
    ): String {
        val database =
            when {
                databaseVersion == null -> "not synced yet"
                databaseUpdated.isNullOrBlank() -> "version $databaseVersion"
                else -> "version $databaseVersion, updated $databaseUpdated"
            }
        return listOf(
            "CallShield flagged a number I think is safe.",
            "Number: $number",
            "Decided by: ${result.reasonCode.wireValue}",
            "Confidence: ${result.confidence}%",
            "App: $appVersion",
            "Database: $database",
            "Where to post it: $DISCUSSION_URL",
        ).joinToString("\n")
    }
}
