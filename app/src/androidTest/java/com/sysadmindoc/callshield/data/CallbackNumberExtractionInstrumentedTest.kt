package com.sysadmindoc.callshield.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Android's regex engine is ICU, where \w and \d cover more than the JVM's, so the reading is checked here too. */
@RunWith(AndroidJUnit4::class)
class CallbackNumberExtractionInstrumentedTest {
    private fun extract(
        body: String,
        nanpHome: Boolean = true,
    ) = SmsContentAnalyzer.extractCallbackNumbers(body, nanpHome)

    @Test
    fun numbersReadTheSameOnThePhone() {
        listOf(
            "\u8BF7\u81F4\u75358003451234",
            "Call \uFF18\uFF10\uFF10-\uFF13\uFF14\uFF15-\uFF11\uFF12\uFF13\uFF14 now",
            "Call 800\u2013345\u20131234 now",
            "Call 800 - 345 - 1234 now",
        ).forEach { assertEquals(it, listOf("+18003451234"), extract(it)) }
        assertEquals(listOf("+442079460958"), extract("Ring +44 (0)20 7946 0958 now", nanpHome = false))
        assertEquals(listOf("+8613812345678"), extract("\u8BF7\u81F4\u7535+86 138 1234 5678", nanpHome = false))
    }

    @Test
    fun nonNumbersStayOutOnThePhone() {
        assertEquals(emptyList<String>(), extract("\u8BF7\u81F4\u753513812345678", nanpHome = false))
        listOf(
            "Track it at example.com/8003451234",
            "Ref AB8003451234",
            "Your code is 482913",
            "Delivery on 2026-09-30 at 10:30",
        ).forEach { assertEquals(it, emptyList<String>(), extract(it)) }
    }
}
