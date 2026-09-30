package com.sysadmindoc.callshield.data

import java.text.Normalizer

/**
 * Undoes the tricks that hide words from the SMS rules: compatibility forms
 * such as fullwidth and math letters (NFKC), invisible and bidi characters,
 * and Cyrillic or Greek letters that look Latin. Rules read the result; the
 * message is still stored as it arrived.
 *
 * No regex here: Android's ICU engine and the JVM's disagree on `\b` and
 * `\s`, so tokens are split and classified by code point.
 */
internal object SmsTextNormalizer {
    data class Result(
        /** NFKC with invisible characters dropped. URL and host checks read this. */
        val visible: String,
        /** [visible] with look-alike letters turned Latin. Keyword rules read this. */
        val folded: String,
        /** A URL host or brand name hid characters or used look-alike letters. */
        val disguised: Boolean,
    )

    private class Token(
        val visible: String,
        val folded: String,
        val disguised: Boolean,
    )

    fun normalize(text: String): Result {
        if (text.isEmpty()) return Result("", "", false)
        val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val foldAll = readsAsLatin(nfkc)
        val visible = StringBuilder(nfkc.length)
        val folded = StringBuilder(nfkc.length)
        var disguised = false
        var i = 0
        while (i < nfkc.length) {
            val cp = nfkc.codePointAt(i)
            if (isSpace(cp)) {
                visible.appendCodePoint(cp)
                folded.appendCodePoint(cp)
                i += Character.charCount(cp)
            } else {
                val start = i
                while (i < nfkc.length && !isSpace(nfkc.codePointAt(i))) {
                    i += Character.charCount(nfkc.codePointAt(i))
                }
                val token = token(nfkc.substring(start, i), foldAll)
                visible.append(token.visible)
                folded.append(token.folded)
                disguised = disguised || token.disguised
            }
        }
        return Result(visible.toString(), folded.toString(), disguised)
    }

    /** [normalize]'s folded text alone, for a keyword the user typed. */
    fun fold(text: String): String = normalize(text).folded

    private fun token(
        raw: String,
        foldAll: Boolean,
    ): Token {
        val visible = StringBuilder(raw.length)
        var hiddenInside = false
        var hiddenAfterWordChar = false
        var latinLetters = 0
        var lookalikes = 0
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            if (isInvisible(cp)) {
                if (visible.isNotEmpty() && isLatinWordChar(visible.codePointBefore(visible.length))) hiddenAfterWordChar = true
                continue
            }
            if (hiddenAfterWordChar && isLatinWordChar(cp)) hiddenInside = true
            hiddenAfterWordChar = false
            visible.appendCodePoint(cp)
            when {
                cp < ASCII_LIMIT && Character.isLetter(cp) -> latinLetters++
                LOOKALIKES.containsKey(cp) -> lookalikes++
            }
        }
        val visibleText = visible.toString()
        val foldedText = if (lookalikes > 0) foldLookalikes(visibleText) else visibleText
        // A word mixing Latin with look-alikes is a disguise even inside a
        // Russian or Greek message; a whole Russian word stays as written.
        val mixed = latinLetters > 0 && lookalikes > 0
        return Token(
            visible = visibleText,
            folded = if (foldAll || mixed) foldedText else visibleText,
            disguised = (hiddenInside || lookalikes > 0) && isHostOrBrand(foldedText.lowercase()),
        )
    }

    /**
     * True when look-alikes in this message stand in for Latin letters: Latin
     * outnumbers Cyrillic and Greek, or every Cyrillic and Greek letter in it
     * has a Latin twin (real Russian or Greek always uses letters that don't).
     */
    private fun readsAsLatin(text: String): Boolean {
        var latin = 0
        var cyrillicOrGreek = 0
        var withoutTwin = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp < ASCII_LIMIT && Character.isLetter(cp) -> {
                    latin++
                }

                isCyrillicOrGreek(cp) && Character.isLetter(cp) -> {
                    cyrillicOrGreek++
                    if (!LOOKALIKES.containsKey(cp)) withoutTwin++
                }
            }
        }
        return latin > cyrillicOrGreek || (cyrillicOrGreek > 0 && withoutTwin == 0)
    }

    private fun foldLookalikes(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val twin = LOOKALIKES[cp]
            if (twin != null) out.append(twin) else out.appendCodePoint(cp)
        }
        return out.toString()
    }

    private fun isHostOrBrand(lowerToken: String): Boolean {
        val trimmed = lowerToken.trim { it in EDGE_PUNCTUATION }
        if (trimmed.isEmpty()) return false
        val host =
            trimmed
                .substringAfter("://")
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .trimEnd('.')
        val tld = host.substringAfterLast('.', "")
        // Only a host that reads as all Latin once folded poses as one. "доставлен.Не" is a
        // Russian sentence missing a space, not a disguised ".he" address.
        val readsLatin = host.all { it.code < ASCII_LIMIT }
        if (readsLatin && host.indexOf('.') > 0 && tld.length >= 2 && tld.all { it in 'a'..'z' }) return true
        val letters = trimmed.filter { it in 'a'..'z' || it in '0'..'9' }
        return BRANDS.any { brand -> letters == brand || (brand.length >= MIN_CONTAINED_BRAND && brand in letters) }
    }

    private fun isSpace(cp: Int): Boolean = Character.isWhitespace(cp) || Character.isSpaceChar(cp)

    /**
     * What a Latin host or brand is made of: ASCII letters, digits, dots and
     * hyphens, or a look-alike standing in for one. An invisible character
     * between Hebrew and a hyphen is how bidi text is written, not a disguise.
     */
    private fun isLatinWordChar(cp: Int): Boolean = (cp < ASCII_LIMIT && (Character.isLetterOrDigit(cp) || cp == '.'.code || cp == '-'.code)) || LOOKALIKES.containsKey(cp)

    private fun isCyrillicOrGreek(cp: Int): Boolean = cp in GREEK_START..GREEK_END || cp in CYRILLIC_START..CYRILLIC_END

    /**
     * Format characters (zero-width, bidi, soft hyphen, tags), variation
     * selectors, and the blank letters that render as nothing.
     */
    internal fun isInvisible(cp: Int): Boolean =
        Character.getType(cp) == Character.FORMAT.toInt() ||
            cp in VARIATION_SELECTORS ||
            cp in VARIATION_SELECTORS_SUPPLEMENT ||
            cp in BLANK_LOOKING

    private const val ASCII_LIMIT = 0x80
    private const val GREEK_START = 0x0370
    private const val GREEK_END = 0x03FF
    private const val CYRILLIC_START = 0x0400
    private const val CYRILLIC_END = 0x052F
    private const val MIN_CONTAINED_BRAND = 4
    private val VARIATION_SELECTORS = 0xFE00..0xFE0F
    private val VARIATION_SELECTORS_SUPPLEMENT = 0xE0100..0xE01EF
    private val BLANK_LOOKING = setOf(0x034F, 0x115F, 0x1160, 0x17B4, 0x17B5, 0x180E, 0x2800, 0x3164, 0xFFA0)
    private const val EDGE_PUNCTUATION = "()[]{}<>\"',!?;:*"

    /** Brands SMS phishing impersonates, as lowercase letters and digits. */
    private val BRANDS =
        setOf(
            "amazon",
            "apple",
            "icloud",
            "google",
            "gmail",
            "microsoft",
            "outlook",
            "paypal",
            "venmo",
            "zelle",
            "cashapp",
            "coinbase",
            "netflix",
            "fedex",
            "usps",
            "ups",
            "dhl",
            "ezpass",
            "sunpass",
            "fastrak",
            "txtag",
            "irs",
            "chase",
            "wellsfargo",
            "bankofamerica",
            "citibank",
            "verizon",
            "tmobile",
            "att",
            "walmart",
            "costco",
        )

    /** Cyrillic, Greek and IPA letters that render like a Latin letter. */
    private val LOOKALIKES: Map<Int, Char> =
        mapOf(
            // Cyrillic
            0x0430 to 'a',
            0x0435 to 'e',
            0x043E to 'o',
            0x0440 to 'p',
            0x0441 to 'c',
            0x0443 to 'y',
            0x0445 to 'x',
            0x0455 to 's',
            0x0456 to 'i',
            0x0458 to 'j',
            0x04BB to 'h',
            0x04CF to 'l',
            0x0501 to 'd',
            0x051B to 'q',
            0x051D to 'w',
            0x0410 to 'A',
            0x0412 to 'B',
            0x0415 to 'E',
            0x041A to 'K',
            0x041C to 'M',
            0x041D to 'H',
            0x041E to 'O',
            0x0420 to 'P',
            0x0421 to 'C',
            0x0422 to 'T',
            0x0423 to 'Y',
            0x0425 to 'X',
            0x0405 to 'S',
            0x0406 to 'I',
            0x0408 to 'J',
            0x04AE to 'Y',
            0x04BA to 'H',
            0x04C0 to 'I',
            0x051A to 'Q',
            0x051C to 'W',
            // Greek
            0x03B1 to 'a',
            0x03B9 to 'i',
            0x03BA to 'k',
            0x03BD to 'v',
            0x03BF to 'o',
            0x03C1 to 'p',
            0x03C5 to 'u',
            0x0391 to 'A',
            0x0392 to 'B',
            0x0395 to 'E',
            0x0396 to 'Z',
            0x0397 to 'H',
            0x0399 to 'I',
            0x039A to 'K',
            0x039C to 'M',
            0x039D to 'N',
            0x039F to 'O',
            0x03A1 to 'P',
            0x03A4 to 'T',
            0x03A5 to 'Y',
            0x03A7 to 'X',
            // Latin and IPA
            0x0131 to 'i',
            0x0251 to 'a',
            0x0261 to 'g',
            0x0585 to 'o',
        )
}
