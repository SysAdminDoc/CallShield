package com.sysadmindoc.callshield.ui.screens.lookup

import com.sysadmindoc.callshield.data.BlockReasoning
import com.sysadmindoc.callshield.domain.model.SpamCheckResult

/**
 * The note a block saved from Lookup keeps. A database or pattern match
 * describes the number. A user rule's text is that rule's own note or a
 * label standing in for it ("Temporarily blocked"), and an allowed result
 * says why it was let through, so neither is saved: the block keeps any
 * note the number already had.
 */
internal fun lookupBlockNote(result: SpamCheckResult): String = if (!result.isSpam || BlockReasoning.isUserRule(result.reasonCode)) "" else result.description
