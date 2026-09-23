package com.sysadmindoc.callshield.data

/**
 * Type chips on the Database tab. [key] is what the paging query matches:
 * a stored type, "" for every row, or "other" for any type without a chip
 * (SpamDao.pageSpamNumbers lists the named types, and a test keeps the two
 * in step).
 */
enum class DatabaseTypeFilter(
    val key: String,
) {
    ALL(""),
    ROBOCALL("robocall"),
    TELEMARKETER("telemarketer"),
    SPAM("spam"),
    SPAM_TEXT("sms_spam"),
    OTHER("other"),
}

/**
 * Source chips on the Database tab: the downloaded database, the trending
 * feed, subscribed external lists, and the user's own blocks.
 */
enum class DatabaseSourceFilter(
    val key: String,
) {
    ALL(""),
    DATABASE("github"),
    TRENDING("hot_list"),
    LISTS("lists"),
    MINE("mine"),
}
