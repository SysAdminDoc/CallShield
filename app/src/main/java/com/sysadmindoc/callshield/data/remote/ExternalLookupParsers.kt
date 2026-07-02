package com.sysadmindoc.callshield.data.remote

private const val MIN_SPAM_REPORTS = 3

internal fun parseSkipCallsBody(body: String): ExternalLookup.SourceResult {
    val isSpam = body.contains("\"spam\":true", ignoreCase = true) ||
        body.contains("\"isSpam\":true", ignoreCase = true) ||
        body.contains("\"status\":\"spam\"", ignoreCase = true)
    val reportMatch = Regex(""""(?:reports?|count)":\s*(\d+)""").find(body)
    val reports = reportMatch?.groupValues?.get(1)?.toIntOrNull() ?: if (isSpam) 1 else 0
    return ExternalLookup.SourceResult(
        "SkipCalls",
        isSpam,
        reports,
        if (isSpam) "Flagged as spam" else "",
        if (isSpam || reports > 0) RemoteLookupStatus.FOUND else RemoteLookupStatus.CLEAN
    )
}

internal fun parsePhoneBlockBody(body: String): ExternalLookup.SourceResult {
    val votesMatch = Regex(""""votes":\s*(\d+)""").find(body)
    val votes = votesMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val blacklisted = body.contains("\"blackListed\":true")
    val rating = Regex(""""rating":\s*"([^"]+)"""").find(body)?.groupValues?.get(1).orEmpty()
    val isSpam = blacklisted ||
        votes >= MIN_SPAM_REPORTS ||
        rating.startsWith("D_") ||
        rating.startsWith("E_")
    return ExternalLookup.SourceResult(
        "PhoneBlock",
        isSpam,
        votes,
        when {
            blacklisted -> "Blacklisted ($votes votes)"
            votes > 0 -> "$votes community votes"
            rating.isNotBlank() -> "Rating: $rating"
            else -> ""
        },
        if (isSpam || votes > 0) RemoteLookupStatus.FOUND else RemoteLookupStatus.CLEAN
    )
}

internal fun parseWhoCalledMeBody(body: String): ExternalLookup.SourceResult {
    val reportMatch = Regex("""(\d+)\s*(?:report|complaint|comment)""", RegexOption.IGNORE_CASE).find(body)
    val reports = reportMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
    return if (reports > 0) {
        ExternalLookup.SourceResult(
            "WhoCalledMe",
            reports >= MIN_SPAM_REPORTS,
            reports,
            "$reports reports",
            RemoteLookupStatus.FOUND
        )
    } else {
        ExternalLookup.SourceResult("WhoCalledMe", isSpam = false, status = RemoteLookupStatus.CLEAN)
    }
}

internal fun parseCallerNameBody(body: String): String {
    // Response: {"number":"+15551234567","name":"JOHN DOE"}
    val nameMatch = Regex(""""name"\s*:\s*"([^"]+)"""").find(body)
    return nameMatch?.groupValues?.get(1)?.trim().orEmpty()
}
