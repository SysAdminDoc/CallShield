package com.sysadmindoc.callshield.util

/*
 * ASCII-only phone digit utilities.
 *
 * Kotlin's Char.isDigit accepts Arabic-Indic (U+0660..U+0669),
 * fullwidth (U+FF10..U+FF19), and many other Unicode digit classes.
 * Carrier dialers strip these before placing a call, so allowing them
 * in blocklist/import/matching paths lets a spoofed caller-ID bypass
 * exact matches by sending the same number in a visually-identical but
 * byte-different form.
 *
 * All security-sensitive paths must use isAsciiDigit or filterAsciiDigits
 * instead of Char.isDigit / filter { it.isDigit() }.
 *
 * Display-only paths (Compose UI formatting, visual transformations)
 * may continue to use Char.isDigit since they never feed back into
 * matching or storage.
 */

/** True only for ASCII '0'..'9'. */
fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

/**
 * Extract only ASCII digits from [value], dropping everything else.
 * Equivalent to `value.filter { it in '0'..'9' }` but named for
 * discoverability and grep-ability.
 */
fun filterAsciiDigits(value: String): String =
    buildString(value.length) {
        for (ch in value) if (ch.isAsciiDigit()) append(ch)
    }

/**
 * Extract the trailing [n] ASCII digits from [value].
 * Equivalent to `value.filter { it in '0'..'9' }.takeLast(n)`.
 */
fun filterAsciiDigitsLast(value: String, n: Int): String = filterAsciiDigits(value).takeLast(n)

/**
 * Keep only characters users expect in a phone input field while rejecting
 * Unicode digit classes that would diverge from screening-path matching.
 */
fun sanitizePhoneNumberInput(value: String, maxLength: Int = DEFAULT_PHONE_INPUT_LENGTH): String =
    buildString(value.length) {
        for (ch in value) {
            when {
                ch.isAsciiDigit() -> append(ch)
                ch == '+' && isEmpty() -> append(ch)
                ch == ' ' || ch == '-' || ch == '(' || ch == ')' -> append(ch)
            }
        }
    }.take(maxLength)

/**
 * Normalize UI-created numbers with the same ASCII-only digit contract used by
 * the screening/data path. Empty means no usable ASCII digit was present.
 */
fun normalizePhoneNumberInput(value: String): String {
    val trimmed = stripPhoneFormatControls(value).trim()
    val digits = filterAsciiDigits(trimmed)
    if (digits.isEmpty()) return ""
    return if (trimmed.startsWith("+")) "+$digits" else digits
}

fun hasMinAsciiDigits(value: String, minimum: Int = MIN_CONFIRMABLE_PHONE_DIGITS): Boolean =
    filterAsciiDigits(value).length >= minimum

private fun stripPhoneFormatControls(value: String): String =
    buildString(value.length) {
        for (ch in value) {
            when (ch.code) {
                ZERO_WIDTH_SPACE,
                ZERO_WIDTH_NON_JOINER,
                ZERO_WIDTH_JOINER,
                LEFT_TO_RIGHT_MARK,
                RIGHT_TO_LEFT_MARK,
                BYTE_ORDER_MARK -> Unit
                else -> append(ch)
            }
        }
    }

private const val DEFAULT_PHONE_INPUT_LENGTH = 24
private const val MIN_CONFIRMABLE_PHONE_DIGITS = 5
private const val ZERO_WIDTH_SPACE = 0x200B
private const val ZERO_WIDTH_NON_JOINER = 0x200C
private const val ZERO_WIDTH_JOINER = 0x200D
private const val LEFT_TO_RIGHT_MARK = 0x200E
private const val RIGHT_TO_LEFT_MARK = 0x200F
private const val BYTE_ORDER_MARK = 0xFEFF
