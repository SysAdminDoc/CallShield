package com.sysadmindoc.callshield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsReplyBaitTest {
    private val analyzer = SmsContentAnalyzer()

    private fun baited(
        body: String,
        firstContact: Boolean = true,
    ): Boolean = "reply_bait" in analyzer.analyze(body, firstContact).reasons

    @Test
    fun `a child with a new phone, in the languages the reports use`() {
        listOf(
            "Hi mum, my phone broke. This is my new number, can you text me back?",
            "Hallo Mama, mein Handy ist kaputt. Das ist meine neue Nummer.",
            "Hola papá, se me rompió el teléfono, este es mi nuevo número.",
            "Hoi mam, dit is mijn nieuwe nummer. Kun je me een appje sturen?",
            "Olá mãe, este é o meu número novo, o telemóvel ficou avariado.",
            "Ciao mamma, il mio telefono è rotto, questo è il mio nuovo numero.",
            "Coucou maman, mon téléphone est cassé, voici mon nouveau numéro.",
            "It's your son, I lost my phone. Save this number please.",
            "Hey mum whatsapp me asap on this number x",
        ).forEach { assertTrue(it, baited(it)) }
    }

    @Test
    fun `a wrong-number opener`() {
        listOf(
            "Hi, is this Sarah?",
            "Hello, are you Mr. Lee?",
            "Oh sorry, I think I texted the wrong number.",
            "Hey, do you remember me? We met at the conference.",
        ).forEach { assertTrue(it, baited(it)) }
    }

    @Test
    fun `a business's wrong-number line is not an opener`() {
        // A clinic's first text: reply bait on top of its opt-out wording,
        // which scored 40 on its own back then, blocked it in the default mode.
        val clinic = "Lakeside Family Clinic: your visit is confirmed for Tue 3:40 PM. Wrong number? Reply STOP to opt out."
        assertFalse(baited(clinic))
        assertTrue(analyzer.analyze(clinic, firstContact = true).score < 50)
        assertFalse(baited("Wrong number? Text STOP and we won't message you again. Maple Bakery order updates"))
        // Well past the opening, with no opt-out, it's an apology, not an opener.
        assertFalse(baited("Your table at Rosa's is ready in 10 minutes, see you soon! If we have the wrong number, sorry about that."))
    }

    @Test
    fun `it counts only in a stranger's first text`() {
        assertFalse(baited("Hi mum, my phone broke. This is my new number, can you text me back?", firstContact = false))
        assertFalse(baited("Hi, is this Sarah?", firstContact = false))
    }

    @Test
    fun `on its own it clears aggressive mode's bar but not the default one`() {
        val score = analyzer.analyze("Hi, is this Sarah?", firstContact = true).score
        assertEquals(SmsContentAnalyzer.REPLY_BAIT_SCORE, score)
        assertTrue(score in 25 until 50)
    }

    @Test
    fun `a link other than a WhatsApp chat makes it a link scam instead`() {
        assertFalse(baited("Hi mum, new number, pay my bill here https://pay.example.invalid/bill"))
        assertTrue(baited("Hi mum, new number, message me here https://wa.me/15550100"))
    }

    @Test
    fun `someone else's parent, or a parent with no new phone, is a real first text`() {
        listOf(
            "Hi, this is Maya's mom. Here's my new number for the carpool list.",
            "Hola, soy la mamá de Lucía. Este es mi número nuevo para el grupo del cole.",
            "Bonjour, c'est le papa de Léo. Voici mon nouveau numéro pour le covoiturage.",
            "My mom got a new number, she asked me to send it to you.",
            "Mom, practice ran late. Can you pick me up at 6?",
            "Hallo Mama, mein Zug hat Verspätung, bin gegen 8 zu Hause.",
        ).forEach { assertFalse(it, baited(it)) }
    }

    @Test
    fun `ma'am asking for a WhatsApp message is not a parent`() {
        assertFalse(baited("Hello mam, your order is ready, WhatsApp me for delivery"))
        assertTrue(baited("Hello mam, I lost my phone, this is my new number"))
    }

    @Test
    fun `an opener with a reason to write is not bait`() {
        assertFalse(baited("Hi, is this the number on the lost dog flyer? I think I saw him on Elm Street."))
        assertFalse(
            baited("Hi, is this Jordan? I'm the tenant in 4B and your package came to my door this morning, I'll keep it for you."),
        )
    }

    @Test
    fun `a disguised new number is still read`() {
        val cyrillicE = 0x0435.toChar()
        assertTrue(baited("Hi mum, this is my n${cyrillicE}w number, text me back"))
    }
}
