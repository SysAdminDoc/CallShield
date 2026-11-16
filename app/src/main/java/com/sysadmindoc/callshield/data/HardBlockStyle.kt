package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.R

/**
 * How a call CallShield rejects shows up on the phone afterwards. Silenced
 * calls ring through to voicemail and never use this.
 */
enum class HardBlockStyle(
    val storageKey: String,
    val labelResId: Int,
    val descriptionResId: Int,
) {
    /** Today's behavior: rejected, logged, and the system's missed-call alert stays on. */
    REJECT("reject", R.string.hard_block_style_reject, R.string.hard_block_style_reject_desc),

    /** Logged as missed instead of rejected where Android supports it (API 37.2), rejected elsewhere. */
    MISSED("missed", R.string.hard_block_style_missed, R.string.hard_block_style_missed_desc),

    /** Rejected without the system's missed-call alert; CallShield's Blocked log still lists it. */
    QUIET("quiet", R.string.hard_block_style_quiet, R.string.hard_block_style_quiet_desc),
    ;

    companion object {
        fun fromStorageKey(key: String?): HardBlockStyle = entries.firstOrNull { it.storageKey == key } ?: REJECT
    }
}
