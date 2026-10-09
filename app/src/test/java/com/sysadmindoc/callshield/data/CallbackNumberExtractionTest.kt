package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.model.ListNumberPlan
import org.junit.Assert.assertEquals
import org.junit.Test

class CallbackNumberExtractionTest {
    private fun extract(
        body: String,
        nanpHome: Boolean = true,
    ) = SmsContentAnalyzer.extractCallbackNumbers(body, nanpHome)

    /** What a phone whose home region is [plan]'s country reads a bare number as. */
    private fun extractAt(
        body: String,
        plan: ListNumberPlan,
    ) = SmsContentAnalyzer.extractCallbackNumbers(body, nanpHome = false) { plan.toInternational(it) }

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
        assertEquals(emptyList<String>(), extract("Call 1-800-345-1234", nanpHome = false))
        assertEquals("a Chinese mobile number", emptyList<String>(), extract("\u8BF7\u81F4\u753513812345678", nanpHome = false))
        assertEquals(listOf("+18003451234"), extract("Call +1 800 345 1234", nanpHome = false))
        assertEquals(listOf("+573131918305"), extract("Llame al +57 313 191 8305 hoy", nanpHome = false))
    }

    @Test
    fun `outside North America a bare number is read the way the phone's region reads it`() {
        assertEquals(listOf("+442079460018"), extractAt("Ring 020 7946 0018 to stop the charge", GB))
        assertEquals(listOf("+442079460018"), extractAt("Ring (020) 7946-0018 to stop the charge", GB))
        assertEquals(listOf("+33123456789"), extractAt("Rappelez le 01 23 45 67 89 avant ce soir", FR))
        assertEquals(listOf("+34912345678"), extractAt("Llame al 912 345 678 hoy", ES))
    }

    @Test
    fun `a bare national number isn't read twice beside its international form`() {
        assertEquals(listOf("+442079460018"), extractAt("Ring +44 20 7946 0018 or 020 7946 0018", GB))
    }

    @Test
    fun `dates, amounts and order numbers aren't national numbers`() {
        listOf(
            "Lieferung am 12.05.2026 um 10:30" to DE,
            "Delivery on 12/05/2026" to GB,
            "Total 2 079 460 018,50 EUR" to GB,
            "Paid \u00A32079460018 today" to GB,
            "Order 2079460018 has shipped" to GB,
            "Ref: 2079460018" to GB,
            "Your code is 2079460018" to GB,
            "#2079460018" to GB,
            "Tracking no. 2079460018" to GB,
            "Call 555-1234 today" to GB,
        ).forEach { (text, plan) -> assertEquals(text, emptyList<String>(), extractAt(text, plan)) }
    }

    @Test
    fun `an international number isn't also read as a North American one`() {
        assertEquals(listOf("+493012345678"), extract("Rufen Sie +49 301 234 5678 an"))
        assertEquals(listOf("+442079460958"), extract("Ring +44 20 7946 0958 now"))
    }

    @Test
    fun `fullwidth digits, typographic dashes and odd spaces are read too`() {
        listOf(
            "Call \uFF18\uFF10\uFF10-\uFF13\uFF14\uFF15-\uFF11\uFF12\uFF13\uFF14 now",
            "Call 800\u2013345\u20131234 now",
            "Call 800\u00A0345\u00A01234 now",
            "Call 800 - 345 - 1234 now",
            "\u8BF7\u81F4\u75358003451234",
        ).forEach { assertEquals(it, listOf("+18003451234"), extract(it)) }
        assertEquals(listOf("+493012345678"), extract("Rufen Sie +49 30 \u2013 1234 5678 an"))
    }

    @Test
    fun `a trunk zero after the country code is dropped`() {
        assertEquals(listOf("+442079460958"), extract("Ring +44 (0)20 7946 0958 now"))
        assertEquals(listOf("+442079460958"), extract("Ring +44(0) 20 7946 0958 now"))
    }

    @Test
    fun `numbers inside a link or a word are not callback numbers`() {
        listOf(
            "Track it at example.com/8003451234",
            "Pay at pay.example/+442079460958",
            "Ref AB8003451234",
        ).forEach { assertEquals(it, emptyList<String>(), extract(it)) }
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

    private companion object {
        val GB = ListNumberPlan("GB", "44", "0", listOf(10))
        val FR = ListNumberPlan("FR", "33", "0", listOf(9))
        val ES = ListNumberPlan("ES", "34", "", listOf(9))
        val DE = ListNumberPlan("DE", "49", "0", listOf(8, 9, 10, 11))
    }
}
