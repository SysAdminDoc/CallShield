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

    /**
     * A validator as lax as libphonenumber's for Germany, which takes 5 to 15
     * digits: only the text around a number can keep it out.
     */
    private fun extractLax(body: String) =
        SmsContentAnalyzer.extractCallbackNumbers(body, nanpHome = false) { raw ->
            val digits = raw.filter { it in '0'..'9' }
            if (digits.startsWith("00")) "+" + digits.drop(2) else "+49" + digits.removePrefix("0")
        }

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
    fun `a bare national number needs a word asking to be called`() {
        assertEquals(emptyList<String>(), extractAt("Your new line is 020 7946 0018", GB))
        assertEquals(listOf("+442079460018"), extractAt("About your order, ring 020 7946 0018", GB))
        assertEquals(listOf("+442079460018"), extractAt("Tel.:020 7946 0018", GB))
        assertEquals(listOf("+4930123456789"), extractLax("Rufen Sie uns unter 030 123456789 an"))
        assertEquals(listOf("+442079460018"), extractAt("Call our booking line on 020 7946 0018", GB))
        assertEquals(listOf("+442079460018"), extractAt("Call before 17:00 on 020 7946 0018", GB))
        assertEquals(listOf("+4930123456789"), extractLax("Rufen Sie unseren Kundenservice an: 030 123456789"))
        assertEquals(listOf("+492079460018"), extractLax("Call 020 7946 0018 about the amount 30123456"))
        assertEquals(listOf("+442079460018"), extractAt("Call us 9.00-5.30 on 020 7946 0018", GB))
    }

    @Test
    fun `helplines and dial words in other languages ask for a call too`() {
        assertEquals(listOf("+448001234567"), extractAt("Helpline: 0800 123 4567", GB))
        assertEquals(listOf("+448001234567"), extractAt("Freephone 0800 123 4567 to stop the payment", GB))
        assertEquals(listOf("+448001234567"), extractAt("Hotline 0800 123 4567", GB))
        assertEquals(listOf("+33123456789"), extractAt("Composez le 01 23 45 67 89", FR))
    }

    @Test
    fun `a seven-digit national number is read where lines are that short`() {
        assertEquals(listOf("+5072234567"), extractAt("Llame al 223-4567 hoy", PA))
    }

    @Test
    fun `a claim, booking or amount word keeps out only a number without the trunk 0`() {
        assertEquals(listOf("+449061701461"), extractAt("You have won 500 GBP! Call to claim 0906 170 1461", GB))
        assertEquals(listOf("+442079460018"), extractAt("Ring to claim: 0207 946 0018", GB))
        assertEquals(listOf("+442079460018"), extractAt("Call to confirm your booking 020 7946 0018", GB))
        assertEquals("claim as a verb", listOf("+6561234567"), extractAt("Call to claim 61234567", SG))
        listOf(
            "Call about your booking 30123456",
            "Call about the balance of 30123456",
            "Ring about your policy 3012345",
            "Call about your parcel number 0123456789",
            "Call about claim no. 0123456789",
        ).forEach { assertEquals(it, emptyList<String>(), extractLax(it)) }
    }

    @Test
    fun `a number dialed with 00 in front is read whole`() {
        assertEquals(listOf("+442079460018"), extractLax("Rappelez le 0044 20 7946 0018"))
    }

    @Test
    fun `references, accounts and prices stay out even when the validator would take them`() {
        listOf(
            "Call about order number 2079460018",
            "Tracking number: 2079460018, call if it's late",
            "Call us. Reference number 2079460018",
            "Rufen Sie wegen Rechnung 3012345678 an",
            "Rufen Sie an, Kundennummer 3012345678",
            "Rufen Sie an, Ihre Kundennummer lautet 3012345678",
            "Call us, your order number is 2079460018",
            "Appelez-nous, N\u00B0 0123456789",
            "Llame, pedido N\u00BA 912345678",
            "Call to pay EUR 3.012.345",
            "Llame: importe EUR 912.345.678",
            "Call now, total 2 079 460 018 EUR",
            "Call now, total 2 079 460 018,50",
            "Ring for the reservation 30 12 34 56 7",
            "Call before 12.05.2026",
        ).forEach { assertEquals(it, emptyList<String>(), extractLax(it)) }
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
        val PA = ListNumberPlan("PA", "507", "", listOf(7, 8))
        val SG = ListNumberPlan("SG", "65", "", listOf(8))
    }
}
