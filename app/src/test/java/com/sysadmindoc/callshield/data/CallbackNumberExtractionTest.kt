package com.sysadmindoc.callshield.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CallbackNumberExtractionTest {
    private fun extract(
        body: String,
        nanpHome: Boolean = true,
    ) = SmsContentAnalyzer.extractCallbackNumbers(body, nanpHome)

    @Test
    fun `a North American number is found however it's written`() {
        listOf(
            "Call 1-800-345-1234 to cancel your order",
            "Call (800) 345-1234 to cancel your order",
            "Call 800.345.1234 to cancel your order",
            "Call 800 345 1234 to cancel your order",
            "Call 8003451234 to cancel your order",
            "Call +1 800 345 1234 to cancel your order",
            "Call +1 (800) 345-1234 to cancel your order",
            "Call +18003451234 to cancel your order",
        ).forEach { assertEquals(it, listOf("+18003451234"), extract(it)) }
    }

    @Test
    fun `every number in a text is kept once`() {
        assertEquals(
            listOf("+18003451234", "+12123456789"),
            extract("Call 800-345-1234 or (212) 345-6789. Again: 800.345.1234"),
        )
    }

    @Test
    fun `outside North America a bare number needs its country code`() {
        assertEquals(emptyList<String>(), extract("Llame al 3131918305 hoy", nanpHome = false))
        assertEquals(emptyList<String>(), extract("Call (800) 345-1234", nanpHome = false))
        assertEquals(listOf("+18003451234"), extract("Call 1-800-345-1234", nanpHome = false))
        assertEquals(listOf("+573131918305"), extract("Llame al +57 313 191 8305 hoy", nanpHome = false))
    }

    @Test
    fun `an international number isn't also read as a North American one`() {
        assertEquals(listOf("+493012345678"), extract("Rufen Sie +49 301 234 5678 an"))
        assertEquals(listOf("+442079460958"), extract("Ring +44 20 7946 0958 now"))
    }

    @Test
    fun `codes, order numbers, dates and short numbers are not phone numbers`() {
        listOf(
            "Your code is 482913",
            "Order 12345678 has shipped",
            "Ref 1234567890123 is on hold",
            "Delivery on 2026-09-30 at 10:30",
            "Call 555-1234 today",
            "Call 800-145-1234 today",
            "",
        ).forEach { assertEquals(it, emptyList<String>(), extract(it)) }
    }
}
