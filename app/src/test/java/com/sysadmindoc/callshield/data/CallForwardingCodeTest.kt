package com.sysadmindoc.callshield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallForwardingCodeTest {
    @Test
    fun `each GSM code that turns forwarding on with a number is found`() {
        mapOf(
            "To confirm your account dial *21*+15551234567# now" to "*21*+15551234567",
            "Dial **21*5551234567# to finish setup" to "**21*5551234567",
            "Activate with *61*+447700900123*11*20#" to "*61*+447700900123",
            "Send *62*+919876543210# to keep your number" to "*62*+919876543210",
            "Courier needs you to dial *67*2125550143#" to "*67*2125550143",
            "Dial **004*+5511987654321# so the agent can call" to "**004*+5511987654321",
            "Dial *002*+34612345678#" to "*002*+34612345678",
            "Your SIM will be blocked. Dial *401*9876543210 to stop it" to "*401*9876543210",
            "Dial *21* +1 (555) 123-4567# to verify" to "*21* +1 (555) 123-4567",
        ).forEach { (body, code) -> assertEquals(body, code, CallForwardingCode.find(body)) }
    }

    @Test
    fun `Verizon's forwarding codes before a number are found`() {
        assertEquals("*72 555-123-4567", CallForwardingCode.find("To verify your line, dial *72 555-123-4567 and hang up"))
        assertEquals("*72(555) 123-4567", CallForwardingCode.find("Dial *72(555) 123-4567"))
        assertEquals("*71 1-212-555-0143", CallForwardingCode.find("Dial *71 1-212-555-0143 to keep service"))
        assertEquals("*725551234567", CallForwardingCode.find("Dial *725551234567 now"))
    }

    @Test
    fun `codes that check or cancel forwarding, and hiding caller ID, are not`() {
        listOf(
            "To turn off all call forwarding, dial ##002#",
            "Dial #21# to cancel forwarding",
            "Dial *#21# to see if your calls are forwarded",
            "On Verizon, dial *73 to stop forwarding",
            "Call me with *67 555-123-4567 so it shows private",
            "Call me with *675551234567",
            "Dial *72 to turn on forwarding",
            "Dial *72 555 1234",
            "Dial *72 0123456789",
        ).forEach { assertNull(it, CallForwardingCode.find(it)) }
    }

    @Test
    fun `ordinary stars are not a code`() {
        listOf(
            "**IMPORTANT** read this before Friday",
            "Only *21* days left in the sale",
            "Save 25%* on 2 items, see store for details",
            "Rated 4.5* by 21 reviewers, call 555-123-4567",
            "My code is *1234* and the gate is 5551234567",
            "Bal *121*1# then press 2 for 5551234567",
        ).forEach { assertNull(it, CallForwardingCode.find(it)) }
    }

    @Test
    fun `a fullwidth or padded code still counts once the message is normalized`() {
        assertEquals("*21*5551234567", CallForwardingCode.inMessage("Dial ＊２１＊５５５１２３４５６７＃"))
        assertEquals("*21*5551234567", CallForwardingCode.inMessage("Dial *2${Char(0x200B)}1*5551234567#"))
        assertNull(CallForwardingCode.inMessage("   "))
    }

    @Test
    fun `a forwarding code is enough on its own to flag a text`() {
        val result = SmsContentAnalyzer.analyze("Your parcel is held. Dial *21*+15551234567# to release it")

        assertEquals(CallForwardingCode.SIGNAL, result.reasons.first())
        assertTrue(result.score >= PastedMessageVerdict.DEFAULT_THRESHOLD)
        assertTrue(PastedMessageVerdict.of("Dial **21*5551234567#", aggressive = false, dangerousLinks = emptyList()).looksLikeSpam)
    }

    @Test
    fun `advice to cancel forwarding is not flagged`() {
        val result = SmsContentAnalyzer.analyze("Worried your calls are forwarded? Dial ##002# to turn all call forwarding off.")

        assertFalse(CallForwardingCode.SIGNAL in result.reasons)
        assertTrue(result.score < PastedMessageVerdict.DEFAULT_THRESHOLD)
    }
}
