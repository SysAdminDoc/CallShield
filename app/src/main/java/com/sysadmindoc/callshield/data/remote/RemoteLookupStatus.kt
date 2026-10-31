package com.sysadmindoc.callshield.data.remote

import java.io.InterruptedIOException
import java.net.SocketTimeoutException

private const val HTTP_TOO_MANY_REQUESTS = 429

enum class RemoteLookupStatus {
    FOUND,
    CLEAN,
    UNCATEGORIZED,
    DISABLED,
    INVALID_INPUT,
    TIMEOUT,
    RATE_LIMITED,
    HTTP_ERROR,
    EMPTY_BODY,
    BODY_TOO_LARGE,
    UNREADABLE_BODY,
    PARSE_ERROR,
    UNAVAILABLE,

    /** Strict offline mode is on, so no request was made. */
    OFFLINE_MODE,
    ;

    val isFallback: Boolean
        get() = this != FOUND && this != CLEAN && this != UNCATEGORIZED

    companion object {
        fun fromHttpCode(code: Int): RemoteLookupStatus =
            if (code == HTTP_TOO_MANY_REQUESTS) {
                RATE_LIMITED
            } else {
                HTTP_ERROR
            }
    }
}

internal fun Throwable.toRemoteLookupStatus(): RemoteLookupStatus =
    if (isStrictOffline()) {
        RemoteLookupStatus.OFFLINE_MODE
    } else if (this is SocketTimeoutException || this is InterruptedIOException) {
        RemoteLookupStatus.TIMEOUT
    } else {
        RemoteLookupStatus.UNAVAILABLE
    }
