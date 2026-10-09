package com.sysadmindoc.callshield.data

/**
 * Reply bait: a text that opens a conversation and carries no link, the way
 * "Hi mum, this is my new number" and "Is this Sarah?" scams wait for an
 * answer before they ask for money or move the chat to WhatsApp. It only
 * means something in a stranger's first text, which the caller decides; this
 * reads the words.
 *
 * Measured on the IMC 2025 smishing sample in SmsEvaluationCorpusTest. Most
 * wrong-number openers ("How are you doing", "Is the house still for sale?")
 * read exactly like a real first text, so only the unambiguous ones count.
 * Word edges are \p{L} lookarounds and spaces include \p{Zs}, as in
 * [SmsContentAnalyzer], because the JVM and Android's ICU disagree on \b.
 */
internal object SmsReplyBait {
    /** A real first text that asks "is this Sarah?" says why it's writing, so the opener counts only when it's short. */
    private const val MAX_OPENER_LENGTH = 80

    /** How far back from a parent word to look for "Maya's" or "my". */
    private const val POSSESSIVE_WINDOW = 24

    /** "Sorry, wrong number" opens a stranger's text; further in, it's usually a business's opt-out footer. */
    private const val WRONG_NUMBER_OPENING = 40

    private const val START = "(?<![\\p{L}\\p{N}])"
    private const val END = "(?![\\p{L}\\p{N}])"
    private const val SPACE = "[\\s\\p{Zs}]"
    private const val APOSTROPHE = "['’]"

    private val chatLinkHosts = setOf("wa.me", "api.whatsapp.com", "chat.whatsapp.com")

    // A parent the text speaks to.
    private val parent =
        Regex(
            "(?iu)$START(?:mum|mom|mam|mama|mamma|mammy|mummy|mommy|dad|daddy|papa|pap|pappa|m[aã]e|mam[aã]e|papai" +
                "|mam[aá]|pap[aá]|mami|papi|mutti|vati|maman|ayah|bunda)$END|엄마|아빠|妈妈|爸爸|お母さん|お父さん",
        )

    // "mam" is also ma'am and "pap" is also porridge, so they count with a lost
    // or new phone but not with a request to use WhatsApp alone.
    private val ambiguousParents = setOf("mam", "pap")

    // Whose parent: "Maya's mom", "my dad", "la mamá de Lucía", "le papa de Léo".
    private val possessive =
        Regex(
            "(?iu)(?:$APOSTROPHE(?:s)?|s$APOSTROPHE|$START(?:my|your|his|her|their|our|the|a|la|el|lo|los|las|su|sus|mi|mis|tu" +
                "|der|die|das|dem|den|meine?|deine?|seine?|ihre?|unsere?|het|mijn|jouw|je|zijn|haar|onze" +
                "|le|les|mon|ma|ton|ta|son|sa|notre|il|gli|mio|mia|tuo|tua|suo|sua" +
                "|o|os|as|meu|minha|teu|seu|nossa|de|van|von|di|du|des|do|da|della|del))$SPACE*$",
        )

    // "This is your mum", "It's your son".
    private val parentSignsOff =
        Regex(
            "(?iu)^[^\\p{L}\\p{N}]*(?:(?:hi|hello|hey)[^\\p{L}\\p{N}]+)?(?:this is|it$APOSTROPHE?s|it is)$SPACE+" +
                "(?:your$SPACE+)?(?:mum|mom|mother|dad|father|son|daughter)$END",
        )

    // A lost, broken or new phone, in the languages the reports use.
    private val newPhone =
        Regex(
            "(?iu)$START(?:" +
                "new (?:mobile |phone |cell |cellphone |whatsapp )?(?:number|phone|no\\.?$END|nr\\.?$END)" +
                "|temporary (?:number|phone)|(?:save|store) (?:this|my|the) (?:new )?number" +
                "|(?:lost|broke|smashed|dropped|cracked|damaged) my (?:old )?(?:phone|mobile|cell|sim)" +
                "|my (?:old )?(?:phone|mobile|cell)[^.!?\\n]{0,20}(?:broken|broke|smashed|lost|dead|damaged|cracked|stolen" +
                "|won$APOSTROPHE?t (?:turn on|work)|doesn$APOSTROPHE?t (?:turn on|work))" +
                "|friend$APOSTROPHE?s phone|using a spare|water damage" +
                "|(?:nuevo|otro) n[uú]mero|n[uú]mero (?:nuevo|temporal)" +
                "|(?:tel[eé]fono|m[oó]vil|celular)[^.!?\\n]{0,15}(?:roto|rompi|ca[ií]d|perd[ií])" +
                "|neue[sn]? (?:handy)?nummer|nummer gewechselt|nummer (?:ein)?speichern" +
                "|(?:handy|telefon)[^.!?\\n]{0,15}(?:kaputt|verloren|defekt|gestohlen|zertr)" +
                "|nieuwe? (?:mobiele |telefoon|gsm |06[- ]?)?(?:nummer|nr$END)|leentoestel" +
                "|(?:telefoon|mobiel|gsm)[^.!?\\n]{0,15}(?:kapot|kwijt|gevallen|gestolen)" +
                "|(?:novo|outro) n[uú]mero|n[uú]mero novo" +
                "|(?:telem[oó]vel|celular|telefone|ecr[aã])[^.!?\\n]{0,15}(?:avariad|partid|quebrad|estragad|perdi)" +
                "|nuov[oi] numero|(?:telefono|cellulare)[^.!?\\n]{0,15}(?:rott|cadut|pers[oa])" +
                "|nouveau num[eé]ro|(?:t[eé]l[eé]phone|portable)[^.!?\\n]{0,15}(?:cass[eé]|perdu|tomb[eé])" +
                "|nomor baru|hp[^.!?\\n]{0,10}rusak" +
                ")|폰이? ?고장|새 번호|手机坏|换号|新号码|携帯[がを]?壊|新しい番号",
        )

    private val chatApp = Regex("(?iu)${START}whats ?app")

    private val wrongNumber =
        Regex(
            "(?iu)$START(?:wrong (?:number|person)|do you (?:still )?remember me" +
                "|did(?:n$APOSTROPHE?t| not) you saved? my number|long time no (?:see|talk|chat|speak))$END",
        )

    // A business's first text ends "Wrong number? Reply STOP to opt out".
    private val optOut =
        Regex("(?iu)$START(?:(?:reply|text|send)$SPACE+[\"'“]?stop|opt$SPACE?-?$SPACE?out|unsubscribe)$END")

    // "Hi, is this Sarah?" The name has to be capitalized: "is this the
    // number for the flyer?" is someone with a reason to write.
    private val isThisName =
        Regex(
            "^[^\\p{L}\\p{N}]*(?:(?iu:hi|hello|hey|good (?:morning|afternoon|evening))[^\\p{L}\\p{N}]+)?" +
                "(?iu:is this|are you|is that)$SPACE+(?iu:you$SPACE+)?(?:(?iu:mr|mrs|ms|miss|dr)\\.?$SPACE+)?" +
                "\\p{Lu}\\p{Ll}+(?:${APOSTROPHE}s)?(?:$SPACE+(?iu:number|phone|cell))?$SPACE*\\?",
        )

    /**
     * True when [text] fishes for a reply. [urls] are the lowercase links it
     * carries: any link but a WhatsApp chat link makes it a link scam instead.
     * [visibleLength] is its length without invisible characters.
     */
    fun matches(
        text: String,
        urls: List<String>,
        visibleLength: Int,
    ): Boolean {
        if (urls.any { !isChatLink(it) }) return false
        val parents = parentsAddressed(text)
        val signsOff = parentSignsOff.containsMatchIn(text)
        if ((parents.isNotEmpty() || signsOff) && newPhone.containsMatchIn(text)) return true
        if ((signsOff || parents.any { it !in ambiguousParents }) && chatApp.containsMatchIn(text)) return true
        return isWrongNumberOpener(text) || (visibleLength <= MAX_OPENER_LENGTH && isThisName.containsMatchIn(text))
    }

    private fun isWrongNumberOpener(text: String): Boolean {
        val match = wrongNumber.find(text) ?: return false
        return match.range.first <= WRONG_NUMBER_OPENING && !optOut.containsMatchIn(text)
    }

    /** The parent words [text] speaks to, skipping ones that name someone's parent. */
    private fun parentsAddressed(text: String): List<String> =
        parent
            .findAll(text)
            .filterNot { match ->
                val start = match.range.first
                possessive.containsMatchIn(text.substring((start - POSSESSIVE_WINDOW).coerceAtLeast(0), start))
            }.map { it.value.lowercase() }
            .toList()

    /** Reads the host the way a browser does, as SmsContentAnalyzer's extractDomain does. */
    private fun isChatLink(url: String): Boolean {
        val host =
            url
                .substringAfter("://")
                .substringBefore('/')
                .substringBefore('\\')
                .substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('@')
                .substringBefore(':')
                .trimEnd('.')
                .removePrefix("www.")
        return host in chatLinkHosts
    }
}
