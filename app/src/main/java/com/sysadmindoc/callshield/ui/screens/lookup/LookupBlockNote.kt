package com.sysadmindoc.callshield.ui.screens.lookup

import com.sysadmindoc.callshield.domain.model.BlockReasonCode
import com.sysadmindoc.callshield.domain.model.SpamCheckResult

/**
 * The note a block saved from Lookup keeps: the text of a database row, a
 * prefix row or a pattern rule, which describes the number. Every other
 * result's text is a label the app wrote (contacts-only mode, a region, a
 * score, a user rule standing in for its own note, why a call was allowed),
 * so none is saved and the block keeps any note the number already had. A
 * checker above the user's own rule can answer first for a number the user
 * blocked with a note, and its label must not replace that note.
 */
internal fun lookupBlockNote(result: SpamCheckResult): String = if (result.isSpam && result.reasonCode in NUMBER_DESCRIPTIONS) result.description else ""

private val NUMBER_DESCRIPTIONS =
    setOf(
        BlockReasonCode.DATABASE,
        BlockReasonCode.PREFIX,
        BlockReasonCode.WILDCARD,
        BlockReasonCode.HASH_WILDCARD,
    )
