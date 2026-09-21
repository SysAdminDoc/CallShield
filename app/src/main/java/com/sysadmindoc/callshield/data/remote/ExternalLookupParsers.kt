package com.sysadmindoc.callshield.data.remote

private val SKIP_CALLS_VERDICT = Regex(""""is_spam"\s*:\s*(true|false)""")
private val SKIP_CALLS_DESCRIPTION = Regex(""""status_description"\s*:\s*"([^"]*)"""")

/**
 * SkipCalls answers `{"number":"…","is_spam":true,"status_code":110,"status_description":"scam"}`
 * (shape checked 2026-09-21).
 *
 * The `is_spam` field is the marker that this is a lookup result at all. A body
 * without it (a parking page, a login wall, an error page served with 200) is
 * UNAVAILABLE, never CLEAN: reading "no match found" as "clean" is how a dead
 * source kept telling people their callers were safe.
 */
internal fun parseSkipCallsBody(body: String): ExternalLookup.SourceResult {
    if (body.isMalformedJsonObject()) {
        return ExternalLookup.SourceResult("SkipCalls", isSpam = false, status = RemoteLookupStatus.PARSE_ERROR)
    }
    val verdict =
        SKIP_CALLS_VERDICT.find(body)?.groupValues?.get(1)
            ?: return ExternalLookup.SourceResult("SkipCalls", isSpam = false, status = RemoteLookupStatus.UNAVAILABLE)
    if (verdict == "false") {
        return ExternalLookup.SourceResult("SkipCalls", isSpam = false, status = RemoteLookupStatus.CLEAN)
    }
    val description =
        SKIP_CALLS_DESCRIPTION
            .find(body)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeUnless { it.isEmpty() || it.equals("unknown", ignoreCase = true) }
    // SkipCalls gives a verdict and a category, never a count. Reporting one
    // report would put a made-up "1 report" on the overlay and detail page.
    return ExternalLookup.SourceResult(
        source = "SkipCalls",
        isSpam = true,
        reports = 0,
        detail = if (description != null) "Flagged as $description" else "Flagged as spam",
        status = RemoteLookupStatus.FOUND,
    )
}

internal fun String.isMalformedJsonObject(): Boolean {
    val trimmed = trim()
    return trimmed.startsWith("{") && !trimmed.endsWith("}")
}
