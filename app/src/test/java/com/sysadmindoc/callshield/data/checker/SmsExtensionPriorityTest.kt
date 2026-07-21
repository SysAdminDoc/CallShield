package com.sysadmindoc.callshield.data.checker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the SMS-only extension chain's ordering and its independence from the
 * call ladder. The SMS checkers used to borrow call-ladder constants by
 * arithmetic (`WILDCARD_RULE - 100`, `ML_SCORER - 100`), so a renumber of the
 * call ladder would silently reorder SMS checks. They now use dedicated
 * constants; these tests fail if the historical ordering or values drift.
 */
class SmsExtensionPriorityTest {
    @Test
    fun `sms extension checkers keep their historical priority values`() {
        assertEquals(5_400, CheckerPriority.SMS_KEYWORD)
        assertEquals(4_700, CheckerPriority.SMS_CONTEXT_TRUST)
        assertEquals(1_900, CheckerPriority.SMS_CONTENT)
    }

    @Test
    fun `sms extension ordering is keyword then trust then content`() {
        assertTrue(CheckerPriority.SMS_KEYWORD > CheckerPriority.SMS_CONTEXT_TRUST)
        assertTrue(CheckerPriority.SMS_CONTEXT_TRUST > CheckerPriority.SMS_CONTENT)
    }
}
