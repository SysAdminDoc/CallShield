package com.sysadmindoc.callshield.data.checker

import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import com.sysadmindoc.callshield.data.checker.PushAlertChecker.Companion.anchoredDigitRegex
import com.sysadmindoc.callshield.data.checker.PushAlertChecker.Companion.TRUST_PHRASES

/**
 * Regression tests for the v1.6.1 push-alert hardening (H1-H3 in the
 * post-release review). These target the pure match logic — full
 * integration test of the checker is out of scope because it needs a
 * prefs snapshot + registry fixtures.
 */
class PushAlertCheckerTest {

    // ── H1: anchored digit match ───────────────────────────────────────

    @Test fun `anchored regex matches standalone 7-digit run`() {
        assertTrue(
            anchoredDigitRegex("5551234").containsMatchIn(
                "Your driver is arriving — call 5551234 if needed"
            )
        )
    }

    @Test fun `anchored regex rejects 7-digit substring of a longer digit run`() {
        // 15551234567 contains "5551234" as a substring. Pre-H1 this
        // matched; post-H1 the lookbehind/lookahead rejects it.
        assertFalse(
            anchoredDigitRegex("5551234").containsMatchIn(
                "Tracking number 15551234567 — delivery in progress"
            )
        )
    }

    @Test fun `anchored regex rejects embedded digit inside alphanumeric token`() {
        // "ABC5551234XYZ" — the 7 digits are flanked by letters, not digits,
        // so the boundary allows it. That's the intended behaviour —
        // digit-adjacent is what matters, not letter-adjacent.
        assertTrue(
            anchoredDigitRegex("5551234").containsMatchIn("ABC5551234XYZ")
        )
    }

    @Test fun `anchored regex matches at start of string`() {
        assertTrue(
            anchoredDigitRegex("5551234").containsMatchIn("5551234 is your code")
        )
    }

    @Test fun `anchored regex matches at end of string`() {
        assertTrue(
            anchoredDigitRegex("5551234").containsMatchIn("Call 5551234")
        )
    }

    // ── H2: tightened trust-phrase list ───────────────────────────────

    @Test fun `bare calendar word no longer qualifies`() {
        // Pre-H2, any alert body containing the word "calendar" fired the
        // push-alert bridge. v1.6.1 drops the bare regex; only
        // "appointment reminder" (from calendar apps) remains.
        val bareCalendar = "Your calendar for today has three events"
        val hit = TRUST_PHRASES.any { phrase ->
            phrase.allowedFromPackages == null &&
                phrase.regex.containsMatchIn(bareCalendar)
        }
        assertFalse(hit)
    }

    @Test fun `bare outside word no longer qualifies alone`() {
        // "Dress warm — it's 28 degrees outside" previously fired the rule.
        val weatherAlert = "Dress warm — it's 28 degrees outside today"
        val hit = TRUST_PHRASES.any { phrase ->
            phrase.allowedFromPackages == null &&
                phrase.regex.containsMatchIn(weatherAlert)
        }
        assertFalse(hit)
    }

    @Test fun `driver outside still matches after tightening`() {
        val deliveryAlert = "Your driver is outside"
        val hit = TRUST_PHRASES.any { phrase ->
            phrase.allowedFromPackages == null &&
                phrase.regex.containsMatchIn(deliveryAlert)
        }
        assertTrue(hit)
    }

    // ── H3: package-gated verification phrases ────────────────────────

    @Test fun `MFA phrase does not fire for non-messaging app`() {
        val outlookMfa = "Your verification code is 483921"
        val hit = TRUST_PHRASES.any { phrase ->
            phrase.appliesTo("com.microsoft.office.outlook") &&
                phrase.regex.containsMatchIn(outlookMfa)
        }
        assertFalse(hit)
    }

    @Test fun `MFA phrase fires for Google Messages`() {
        val smsVerification = "Your verification code is 483921"
        val hit = TRUST_PHRASES.any { phrase ->
            phrase.appliesTo("com.google.android.apps.messaging") &&
                phrase.regex.containsMatchIn(smsVerification)
        }
        assertTrue(hit)
    }

    @Test fun `appointment reminder only fires for calendar apps`() {
        val reminder = "Appointment reminder: dentist at 3pm"
        val fromCalendar = TRUST_PHRASES.any { phrase ->
            phrase.appliesTo("com.google.android.calendar") &&
                phrase.regex.containsMatchIn(reminder)
        }
        val fromEmail = TRUST_PHRASES.any { phrase ->
            phrase.appliesTo("com.microsoft.office.outlook") &&
                phrase.regex.containsMatchIn(reminder)
        }
        assertTrue(fromCalendar)
        assertFalse(fromEmail)
    }

    // ── Driver / delivery phrases fire for any package ────────────────

    @Test fun `driver phrase fires regardless of sender package`() {
        val deliveryBody = "Your driver Michael is arriving soon"
        val fromUber = TRUST_PHRASES.any { phrase ->
            phrase.appliesTo("com.ubercab") &&
                phrase.regex.containsMatchIn(deliveryBody)
        }
        val fromRandom = TRUST_PHRASES.any { phrase ->
            phrase.appliesTo("com.random.unknown") &&
                phrase.regex.containsMatchIn(deliveryBody)
        }
        assertTrue(fromUber)
        assertTrue(fromRandom)
    }
}
