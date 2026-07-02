package com.sysadmindoc.callshield.data.remote

enum class RemoteLookupStatus {
    FOUND,
    CLEAN,
    DISABLED,
    INVALID_INPUT,
    HTTP_ERROR,
    EMPTY_BODY,
    BODY_TOO_LARGE,
    UNREADABLE_BODY,
    PARSE_ERROR,
    UNAVAILABLE;

    val isFallback: Boolean
        get() = when (this) {
            FOUND, CLEAN -> false
            DISABLED,
            INVALID_INPUT,
            HTTP_ERROR,
            EMPTY_BODY,
            BODY_TOO_LARGE,
            UNREADABLE_BODY,
            PARSE_ERROR,
            UNAVAILABLE -> true
        }
}
