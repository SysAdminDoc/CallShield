package com.sysadmindoc.callshield.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleValidationTest {

    @Test
    fun `blank keyword does not match any sms body`() {
        assertFalse(SmsKeywordRule(keyword = "   ").matches("free gift card"))
    }

    @Test
    fun `blank wildcard does not match any number`() {
        assertFalse(WildcardRule(pattern = "   ").matches("+12125551234"))
    }

    @Test
    fun `trimmed keyword still matches normally`() {
        assertTrue(SmsKeywordRule(keyword = "  urgent  ").matches("This is urgent"))
    }
}
