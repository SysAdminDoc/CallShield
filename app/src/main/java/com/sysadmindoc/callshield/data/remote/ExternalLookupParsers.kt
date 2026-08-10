package com.sysadmindoc.callshield.data.remote

private const val MIN_SPAM_REPORTS = 3

internal fun parseSkipCallsBody(body: String): ExternalLookup.SourceResult {
    if (body.isMalformedJsonObject()) {
        return ExternalLookup.SourceResult("SkipCalls", isSpam = false, status = RemoteLookupStatus.PARSE_ERROR)
    }
    val isSpam =
        body.contains("\"spam\":true", ignoreCase = true) ||
            body.contains("\"isSpam\":true", ignoreCase = true) ||
            body.contains("\"status\":\"spam\"", ignoreCase = true)
    val reportMatch = Regex(""""(?:reports?|count)":\s*(\d+)""").find(body)
    val reports = reportMatch?.groupValues?.get(1)?.toIntOrNull() ?: if (isSpam) 1 else 0
    return ExternalLookup.SourceResult(
        "SkipCalls",
        isSpam,
        reports,
        if (isSpam) "Flagged as spam" else "",
        if (isSpam || reports > 0) RemoteLookupStatus.FOUND else RemoteLookupStatus.CLEAN,
    )
}

internal fun parsePhoneBlockBody(body: String): ExternalLookup.SourceResult {
    if (body.isMalformedJsonObject()) {
        return ExternalLookup.SourceResult("PhoneBlock", isSpam = false, status = RemoteLookupStatus.PARSE_ERROR)
    }
    val votes =
        Regex(""""votes":\s*(\d+)""")
            .find(body)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: 0
    val wildcardVotes =
        Regex(""""votesWildcard":\s*(\d+)""")
            .find(body)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: 0
    val reports = maxOf(votes, wildcardVotes)
    val blacklisted = Regex(""""blackListed":\s*true""", RegexOption.IGNORE_CASE).containsMatchIn(body)
    val rating =
        Regex(""""rating":\s*"([^"]+)"""")
            .find(body)
            ?.groupValues
            ?.get(1)
            .orEmpty()
    val isSpam =
        blacklisted ||
            reports >= MIN_SPAM_REPORTS ||
            rating.startsWith("D_") ||
            rating.startsWith("E_")
    return ExternalLookup.SourceResult(
        "PhoneBlock",
        isSpam,
        reports,
        when {
            blacklisted && wildcardVotes > 0 -> "Blacklisted ($votes direct, $wildcardVotes range votes)"
            blacklisted -> "Blacklisted ($votes votes)"
            votes > 0 && wildcardVotes > 0 -> "$votes direct, $wildcardVotes range votes"
            votes > 0 -> "$votes community votes"
            wildcardVotes > 0 -> "$wildcardVotes range votes"
            rating.isNotBlank() -> "Rating: $rating"
            else -> ""
        },
        if (isSpam || reports > 0) RemoteLookupStatus.FOUND else RemoteLookupStatus.CLEAN,
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
            RemoteLookupStatus.FOUND,
        )
    } else {
        ExternalLookup.SourceResult("WhoCalledMe", isSpam = false, status = RemoteLookupStatus.CLEAN)
    }
}

internal fun parseCallerNameBody(body: String): ExternalLookup.CallerNameResult {
    if (body.isMalformedJsonObject()) {
        return ExternalLookup.CallerNameResult(status = RemoteLookupStatus.PARSE_ERROR)
    }
    val nameMatch = Regex(""""name"\s*:\s*"([^"]+)"""").find(body)
    val callerName =
        nameMatch
            ?.groupValues
            ?.get(1)
            ?.trim()
            .orEmpty()
    return ExternalLookup.CallerNameResult(
        callerName = callerName,
        status = if (callerName.isNotBlank()) RemoteLookupStatus.FOUND else RemoteLookupStatus.CLEAN,
    )
}

internal fun String.isMalformedJsonObject(): Boolean {
    val trimmed = trim()
    return trimmed.startsWith("{") && !trimmed.endsWith("}")
}
