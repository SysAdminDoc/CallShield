package com.sysadmindoc.callshield.data.checker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrequencyEscalationCheckerTest {
    @Test
    fun `repeat-caller rule is call-only`() {
        assertTrue(FrequencyEscalationChecker.shouldRun(settingEnabled = true, isSms = false))
        assertFalse(FrequencyEscalationChecker.shouldRun(settingEnabled = true, isSms = true))
    }

    @Test
    fun `disabled repeat-caller rule never runs`() {
        assertFalse(FrequencyEscalationChecker.shouldRun(settingEnabled = false, isSms = false))
    }
}
