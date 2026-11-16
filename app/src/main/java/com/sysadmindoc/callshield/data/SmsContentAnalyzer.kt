package com.sysadmindoc.callshield.data

import javax.inject.Inject

/**
 * Analyzes SMS message body content for spam indicators.
 * Pure regex/keyword-based — runs entirely on-device.
 */
class SmsContentAnalyzer
    @Inject
    constructor() {
        data class SmsAnalysisResult(
            val score: Int,
            val reasons: List<String>,
        )

        data class SmsReportIndicators(
            val domains: List<String> = emptyList(),
            val urlIndicators: List<String> = emptyList(),
        ) {
            fun isEmpty(): Boolean = domains.isEmpty() && urlIndicators.isEmpty()
        }

        // URL shorteners frequently used in SMS spam
        private val shortenerDomains =
            setOf(
                "bit.ly",
                "tinyurl.com",
                "t.co",
                "goo.gl",
                "ow.ly",
                "is.gd",
                "buff.ly",
                "rebrand.ly",
                "cutt.ly",
                "shorturl.at",
                "rb.gy",
                "t.ly",
                "v.gd",
                "tiny.cc",
                "qr.ae",
                "bl.ink",
                "lnk.to",
            )

        // Suspicious TLDs commonly used in phishing
        private val suspiciousTlds =
            setOf(
                ".xyz",
                ".top",
                ".club",
                ".work",
                ".buzz",
                ".icu",
                ".cam",
                ".life",
                ".click",
                ".link",
                ".info",
                ".loan",
                ".win",
                ".bid",
                ".stream",
                ".racing",
                ".download",
                ".gq",
                ".ml",
                ".tk",
                ".cf",
                ".ga",
                ".pw",
            )

        // "Urgent" on its own is everyday speech ("message me if it's urgent"),
        // so it counts as a spam keyword only beside what a scam is after: a
        // link, a number to call, money, an account or card, or a "new number".
        // The rest of the old urgency rule still counts on its own.
        private val urgencyPattern = Regex("(?i)(immediate action|account (suspended|locked|compromised|closed))")
        private val urgentWord = Regex("(?i)urgent")
        private val urgentStakes =
            Regex(
                "(?i)$JOB_MONEY" +
                    "|(?<!\\d)\\d(?:[ \\t()-]?\\d){6,}(?!\\d)" +
                    "|$WORD_START(?:new|temp|temporary|spare)${SPACE}number$WORD_END" +
                    "|$WORD_START(?:n.mero${SPACE}nuevo|nuevo${SPACE}n.mero|n.mero${SPACE}novo|novo${SPACE}n.mero" +
                    "|nuovo${SPACE}numero|numero${SPACE}nuovo)$WORD_END" +
                    "|$WORD_START(?:account|bank|card|credit|debit|payment|invoice|fee|tax|debt|loan|balance|refund" +
                    "|prize|reward|password|login|otp|verify|verification|security" +
                    "|cuenta|banco|tarjeta|pago|pagar|factura|deuda|premio" +
                    "|cart.o|pagamento|fatura|d.vida|pr.mio|conto|banca|carta|fattura|debito" +
                    "|compte|banque|carte|paiement|facture|solde|imp.ts?|rekening|betaling|betalen)$WORD_END",
            )

        // Spam keyword patterns (case-insensitive)
        private val spamPatterns =
            listOf(
                // Financial scams
                Regex("(?i)(you('ve| have)? ($WON|been selected|been chosen))"),
                Regex("(?i)(claim (your|the) (prize|reward|gift|money))"),
                Regex("(?i)(free (gift|money|cash|iphone|samsung|card))"),
                Regex("(?i)(\\$\\d{2,}[,.]?\\d*\\s*(cash|reward|prize|gift))"),
                Regex("(?i)(wire transfer|western union|money ?gram|crypto payment)"),
                Regex("(?i)(bitcoin|btc|ethereum|eth|usdt).{0,20}(send|transfer|pay|wallet)"),
                // Urgency / pressure tactics
                Regex("(?i)(act (now|fast|immediately|today)|limited time|expires? (today|soon|now))"),
                urgencyPattern,
                Regex("(?i)(verify (your|the) (account|identity|information|ssn|social))"),
                Regex("(?i)(your (package|delivery|shipment) (has|is|was) (held|delayed|stopped))"),
                Regex("(?i)(final (notice|warning|attempt|reminder))"),
                // Loan / debt scams
                Regex("(?i)(pre-?approved|guaranteed approval|no credit check)"),
                Regex("(?i)(student loan (forgive|relief|discharge))"),
                Regex("(?i)(debt (relief|consolidation|settlement|forgive))"),
                Regex("(?i)(irs|tax).{0,20}(owe|debt|lien|levy|refund)"),
                // Impersonation
                Regex(
                    "(?i)(amazon|apple|google|microsoft|paypal|netflix|fedex|ups|usps)" +
                        ".{0,30}(verify|confirm|update|suspend|locked|expire)",
                ),
                Regex("(?i)(social security).{0,20}(suspend|compromis|fraud|block)"),
                // Romance / adult scams
                Regex("(?i)(meet (singles|women|men|hot)|dating (site|app)|hookup)"),
                Regex("(?i)(adult|xxx|sexy).{0,15}(video|photo|pic|chat|call)"),
                // Health scams
                Regex("(?i)(miracle (cure|pill|drug|weight))"),
                Regex("(?i)(lose \\d+ (lbs?|pounds|kg) (in|fast|quick))"),
                Regex("(?i)(pharmacy|viagra|cialis|prescription).{0,20}(discount|cheap|free|order)"),
                // Generic spam signals
                Regex("(?i)(congratulations|congrats).{0,20}($WON|winner|selected|chosen)"),
                // ── Spanish ────────────────────────────────────────────────
                // "Has ganado", "te ha tocado" and "ha sido seleccionada" are ordinary
                // Spanish (a match result, loyalty points, a work shift, a job
                // application), so they count only with a prize or an amount after them.
                Regex(
                    "(?i)(ganador(a)? del (sorteo|premio)|sorteo" +
                        "|premio (de|por valor de) \\d" +
                        "|(ha(s)? ganado|ganaste|(le|te) ha tocado) (un|una|el|la) " +
                        "(premio|regalo|iphone|viaje|cheque|coche|m.vil|tel.fono|tarjeta regalo|bono|loter.a|sorteo)" +
                        "|(ha(s)? ganado|ganaste|(le|te) ha tocado) \\d[\\d.,]* ?(€|eur|euros|d.lares)" +
                        "|(fuiste|ha sido|has sido) (seleccionad[oa]|elegid[oa]) (para (recibir|ganar|un premio|el premio)|como ganador))",
                ),
                Regex("(?i)(reclam[ae]|cobra) (tu|su|el) (premio|regalo|dinero)"),
                Regex("(?i)(cuenta (suspendida|bloqueada|comprometida))"),
                Regex("(?i)(verific[ae]|confirm[ae]) (tu|su) (cuenta|identidad|informaci.n)"),
                Regex("(?i)(paquete|env.o|entrega).{0,20}(retenid[oa]|detenid[oa]|pendiente de (pago|abono))"),
                Regex("(?i)(transferencia|giro|dep.sito).{0,20}(inmediato|urgente|ahora)"),
                Regex("(?i)(banco|tarjeta|cr.dito).{0,20}(bloquead[oa]|suspendid[oa]|verificar)"),
                // ── Portuguese ─────────────────────────────────────────────
                Regex("(?i)(voc. ganhou|parab.ns.{0,40}(ganhou|premiad|sortead|selecionad|contemplad)|sorteio|premiado)"),
                Regex("(?i)(resgate|retirar) (seu|o) (pr.mio|presente|dinheiro)"),
                Regex("(?i)(conta (suspensa|bloqueada|comprometida))"),
                Regex("(?i)(verificar|confirmar) (sua|a) (conta|identidade|informa..o)"),
                Regex("(?i)(encomenda|pacote|entrega).{0,20}(retid[oa]|parad[oa]|pendente de pagamento)"),
                Regex("(?i)(transfer.ncia|pix|dep.sito).{0,20}(imediato|urgente|agora)"),
                Regex("(?i)(banco|cart.o|cr.dito).{0,20}(bloqueado|suspenso|verificar)"),
                // ── Italian ────────────────────────────────────────────────
                // A bare "premio" is also an insurance premium, so a prize needs its
                // context. An insurance premium can wait for payment too, so "in
                // attesa" counts only when the premio is waiting to be collected.
                Regex(
                    "(?i)(hai vinto|congratulazioni|sorteggio|vinto un premio|premio in denaro" +
                        "|premio (ti|vi) aspetta|premio . (pronto|disponibile)" +
                        "|premio . in attesa di (essere )?(ritir|riscoss|reclam)|premio da (ritirare|riscuotere|reclamare))",
                ),
                Regex("(?i)(riscuoti|ritira) (il tuo|il) (premio|regalo|denaro)"),
                Regex("(?i)(conto (sospeso|bloccato|compromesso))"),
                Regex("(?i)(verifica|conferma) (il tuo|la tua) (conto|identit.|informazioni)"),
                Regex("(?i)(pacco|spedizione|consegna).{0,20}(trattenut[oa]|in sospeso|bloccat[oa])"),
                Regex("(?i)(bonifico|trasferimento).{0,20}(immediato|urgente|adesso)"),
                Regex("(?i)(banca|carta|credito).{0,20}(bloccat[oa]|sospes[oa]|verificare)"),
                // Real-world smishing lures (IMC 2025 sample, tuned on its even rows only).
                // A lure phrase must come with an action or a consequence, never alone,
                // so a bank's fraud alert or a real parcel notice stays clean.
                // ── Dutch ──
                Regex("(?is)(voorkom|voorkomen|vermijd)\\w*.{0,40}(blokkade|blokkering|opschorting|in beperking)"),
                Regex("(?is)(blokkade|blokkering|opschorting|beperking)\\s+(van \\w+ ){0,3}te voorkomen"),
                Regex("(?is)veiligheids(overwegingen|redenen).{0,40}(geblokkeerd|onderbroken|in beperking|opgeschort|beperkt)"),
                Regex("(?is)(geblokkeerd|onderbroken|in beperking gesteld|opgeschort).{0,30}veiligheids(overwegingen|redenen)"),
                Regex("(?is)quarantaine\\s?zone"),
                Regex("(?is)digitale machtiging"),
                Regex("(?is)openstaande (schuld|vordering).{0,150}(conservatoir|beslag|deurwaarder)"),
                Regex("(?is)herkent u dit niet.{0,20}(ga naar|klik|log)"),
                Regex("(?is)(betaalpas|bankpas|digipas|\\bpas\\b).{0,70}(geheel gratis|kosteloos)"),
                Regex("(?is)(vervalt|vervallen|verouderd).{0,80}(vraag .{0,30} aan|aan te vragen|voorkom)"),
                Regex("(?is)(vergoeding|teruggave|restitutie).{0,50}(goedgekeurd|klaargezet).{0,60}(link|ingediend|claim)"),
                Regex("(?is)deblokkeren|blokkade opheffen|opheffen van .{0,15}blokkade"),
                Regex("(?is)verifi.r\\w* (direct |nu )?uw .{0,30}account"),
                Regex("(?is)(rekening|account|betaalpas|app|kaart).{0,40}(geblokkeerd|opgeschort|beperkt|in beperking).{0,80}(klik|log in|inloggen|herstel|activeer|verifi|valid|bevestig|ga naar|link)"),
                // ── French ──
                Regex("(?is)(dossier d.infraction|contravention impay|amende impay|avant majoration|poursuites? judiciaire)"),
                Regex("(?is)crit.?air.{0,100}(contravention|r.cup.rer)"),
                Regex("(?is)colis.{0,80}(aucun affranchissement|frais de douane|d.douanement|frais suppl.mentaires|paiement manquant|r.gler (ces|les) frais)"),
                Regex("(?is)(livraison|colis).{0,40}(interrompu|suspendu|bloqu).{0,100}(confirm|r.gul|r.sol|v.rifi|frais|adresse|suivez|cliquez)"),
                Regex("(?is)(compte|carte).{0,50}(suspendu|bloqu|restreint|limit|d.sactiv).{0,90}(v.rifi|confirm|identif|mettre . jour|mettez . jour|connectez|cliquez|suivez|r.activ|d.bloqu|r.gularis)"),
                Regex("(?is)(v.rifier|confirmer|mettre . jour|mettez . jour|actualiser).{0,15}(votre|vos) (compte|identit.|informations (de paiement|bancaires|personnelles)|moyen de paiement|mode de paiement)"),
                Regex("(?is)(paiement|pr.l.vement).{0,40}(refus|rejet|rejeter|.chou).{0,100}(mettre . jour|mettez . jour|confirm|r.gularis|remettre|suspendu|informations)"),
                Regex("(?is)carte vitale.{0,100}(avant le|imp.rativement|veuillez)"),
                Regex("(?is)cpf.{0,70}(expire|.ch.ance|convertir|formulaire)"),
                Regex("(?is)rembours\\w*.{0,40}disponible.{0,40}(rendez-vous|cliquez|ici|sur)"),
                Regex("(?is)ch.que .nergie"),
                // ── German ──
                Regex("(?is)zollgeb\\w*.{0,60}(\\d[\\d.,]* ?(€|eur)|bezahl|zahlen)"),
                Regex("(?is)(paket|sendung|zustellung).{0,80}(nicht zugestellt|fehlgeschlagen|gesperrt|festgehalten|einbehalten|unterbrochen).{0,100}(best.tigen|adresse|bezahl|zahlen|geb.hr|porto|link|klick|besuchen)"),
                Regex("(?is)kein porto"),
                Regex("(?is)(konto|zugang|zugriff|karte|pushtan|securego|phototan|app).{0,50}(gesperrt|deaktiviert|l.uft .{0,12}ab|endet|abgelaufen|eingefroren).{0,100}(aktualisier|aktivier|best.tigen|verifizier|klick|hier|link|entsperr|reaktivier|identit.t)"),
                Regex("(?is)um.{0,40}(sperr\\w*|einschr.nkung\\w*).{0,15}zu vermeiden"),
                Regex("(?is)zahlung.{0,30}abgelehnt.{0,80}(best.tigen|aktualisier|gesperrt)"),
                Regex("(?is)trotz mehrerer mahnungen"),
                Regex("(?is)(betrag|erstattung|guthaben).{0,30}(erhalten|erstattet).{0,50}(registrier|anmeld|melden|best.tigen|klick|link|per:)"),
                Regex("(?is)(pushtan|phototan|girocard|sparkasse|volksbank|raiffeisen|konto|karte|app).{0,80}(aktivier|erneuer|beantragen|verifizier|best.tigen|freischalten|entsperren|einschr.nk).{0,60}(http|link|klick|unter:|.ber|hier|folgen)"),
                Regex("(?is)(paket|sendung|lieferung).{0,100}(angehalten|unzustellbar|nicht zustellen|zur.ckgesendet|abzuholen|adresse|lieferstatus|zugestellt werden).{0,80}(http|link|klick|hier|unter|details|best.tigen)"),
                Regex("(?is)(unzustellbar|letzte (chance|m.glichkeit)).{0,60}(paket|sendung|abzuholen|abholen)"),
                Regex("(?is)(r.ckerstattung|erstattung|steuerr.ckzahlung).{0,60}(zur verf.gung|bereit|anspruch).{0,80}(link|klick|http|website|betrag)"),
                Regex("(?is)doppelt bezahlt.{0,60}(anfordern|erstatt|http|hier)"),
                Regex("(?is)(konto|karte|zugang).{0,60}(gesperrt|vor.bergehend|eingeschr.nkt).{0,100}(http|link|klick|best.tigen|identit.t|aktualisier)"),
                Regex("(?is)\\b(handy|telefon|smartphone)\\b.{0,20}\\bkaputt\\b.{0,160}\\b(whats\\s?app|whastapp|einspeichern|speichern|neue\\w*\\s+(handy)?\\s*nummer)\\b|\\bneue\\w*\\s+(handy)?\\s*nummer\\b.{0,120}\\b(handy|telefon|smartphone)\\b.{0,20}\\bkaputt\\b"),
                // ── Indonesian ──
                Regex("(?is)selamat.{0,40}(anda|kamu).{0,20}(menang|pemenang|mendapat\\w* hadiah)"),
                Regex("(?is)(paruh waktu|part.?time|kerja sampingan).{0,80}(penghasilan|gaji|komisi|per hari)"),
                Regex("(?is)(pinjaman|dana).{0,60}(tanpa agunan|tanpa jaminan|hanya ktp|cukup:? ?ktp)"),
                Regex("(?is)\\b(slot gacor|maxwin|togel|judi online|bandar)\\b"),
                Regex("(?is)(akun|rekening|kartu|mobile banking).{0,30}(diblokir|dibekukan|ditangguhkan|dinonaktifkan).{0,60}(klik|link|verifikasi|konfirmasi|perbarui|segera)"),
                Regex("(?is)(segera|harap|wajib).{0,15}(verifikasi|update|perbarui).{0,25}(data|akun|rekening|kartu|identitas)"),
                Regex("(?is)(selamat|slamt|slmt).{0,80}(hadiah|cek|pemenang|undian).{0,80}(pin|klik|hub|info|rp)"),
                Regex("(?is)(hadiah|cek).{0,30}rp\\.? ?\\d+ ?(jt|juta)"),
                Regex("(?is)pesan resmi.{0,80}(hadiah|undian|pemenang|pin)"),
                Regex("(?is)pulsa.{0,40}(terpotong|hangus)|auto ?tp"),
                Regex("(?is)\\b(pinjaman|kredit|dana)\\b.{0,40}\\b(cair|cepat|mudah|tanpa|online)\\b.{0,80}\\b(klik|hub|wa|whatsapp|bunga|ktp|bpkb)\\b"),
                Regex("(?is)(dilibatkan|dicantumkan|dilibatkan &).{0,30}penjamin"),
                Regex("(?is)\\b(t[o0]g[e3]l|p[o0]k[e3]r|d[i1]ngd[o0]ng|gacor|b[o0]ndar)\\b.{0,80}\\b(deposit|depo|bonus|diskon|min)\\b|\\b(casino|slot)\\b.{0,80}\\b(deposit|depo|bonus|diskon)\\b"),
                Regex("(?is)gratis (netflix|disney|premium).{0,60}(verifikasi|login|klik|link|akun)"),
                // ── Hindi and Hinglish ──
                Regex("(?is)(खाता|खाते).{0,40}आधार.{0,30}(जुड़ा|लिंक)"),
                Regex("(?is)आधार ?कार्ड ?(से )?लोन"),
                Regex("(?is)प्रधान ?मंत्री.{0,20}लोन"),
                Regex("(?is)(lottery|लॉटरी|लाटरी) (laga|लगा)"),
                Regex("(?is)(इनाम|ईनाम|inaam) (आया|mila)"),
                Regex("(?is)(dues|loan|payment).{0,30}(not paid|unpaid|overdue|repaid).{0,80}(legal|necessary measures|police|court)"),
                Regex("(?is)(बिजली).{0,60}(काट दी जाएगी|कनेक्शन कट|कटने)"),
                Regex("(?is)(airtel|एयरटेल|jio|जियो).{0,30}(जीत|इनाम|ईनाम|विजेता|बिजेता)"),
                Regex("(?is)(इनाम|ईनाम|लाख).{0,40}(कॉल|call)"),
                Regex("(?is)(kbc|कौन बनेगा|lucky winner|lucky draw).{0,100}(prize|lakh|lottery|whatsapp|करोड़पति|इनाम)"),
                Regex("(?is)(जनधन|योजना).{0,10}लोन.{0,50}(ब्याज|call|छूट)"),
                Regex("(?is)(4g tower|टावर).{0,60}(किराया|advance|rent)"),
                // ── Japanese ──
                Regex("(?is)異常(な)?ログイン"),
                Regex("(?is)(利用停止|利用制限).{0,20}(再開|解除|復元)|(再開|解除|復元)手続"),
                Regex("(?is)(アカウント|口座).{0,12}一時(的に)?停止"),
                Regex("(?is)(ご登録|登録済み).{0,12}情報.{0,12}(ご入力|再確認)"),
                Regex("(?is)不正な?アクセスを検知"),
                Regex("(?is)(暗証番号|パスワード).{0,15}(失効|期限切れ).{0,30}(更新|変更|入力)"),
                Regex("(?is)差押|差し押さえ"),
                Regex("(?is)(アカウント|口座).{0,20}(停止|凍結|ロック)(され|して)"),
                Regex("(?is)直近の取引.{0,20}(確認|重要)"),
                Regex("(?is)重要.{0,15}LINE追加"),
                Regex("(?is)ニーモニック"),
                Regex("(?is)(お荷物|荷物).{0,30}(不在|宛先不明|不存在).{0,20}持ち帰り"),
                Regex("(?is)(amazon|アマゾン).{0,40}(お支払い方法に問題|支払い方法に問題|アカウント違[反遺]|アカウント.{0,6}(停止|ロック|制限))"),
                Regex("(?is)(口座|カード|アカウント).{0,30}(不正利用|悪用された可能性).{0,40}(認証|確認|設定|実施)"),
                Regex("(?is)(暗証番号|パスワード).{0,30}(失効|入力相違|利用制限).{0,60}(更新|解除|再開|ご確認|お願い)"),
                Regex("(?is)(ご利用料金|ご請求).{0,30}(お支払い|請求).{0,20}(こちら|はこちら)"),
                Regex("(?is)(利用料金|未納|滞納).{0,30}(お話したい|至急|本日中|ご連絡)"),
                // ── Spanish ──
                Regex("(?is)si no (lo |la )?(reconoce|ha sido usted|fue usted|fuiste t[uú]|has sido t[uú]|fue t[uú]).{0,60}(verifi|siga|pulse|haga clic|cancel|acced|ingres|enlace|https?)"),
                Regex("(?is)a partir del.{0,30}no (podr[aá]|puede\\w*|se puede) utilizar.{0,30}(cuenta|tarjeta|servicios)"),
                Regex("(?is)nuevo sistema de seguridad"),
                Regex("(?is)usuario deshabilitado"),
                Regex("(?is)(cuenta|tarjeta|clave|acceso|productos|usuario).{0,40}(bloquead|suspendid|desactivad|inhabilitad|limitad|deshabilitad|suspende|riesgo).{0,100}(verifi|actualiz|confirm|clic|pulse|enlace|https?|acceda|ingres|valide|aqu[ií]|siguiente)"),
                Regex("(?is)(bancol\\w*|clave din[aá]mica).{0,80}(activ|https?|enlace|aqu[ií])"),
                Regex("(?is)(paquete|env[ií]o|entrega).{0,100}(no se ha podido|imposible entregar|listo para la entrega|aduanas?).{0,100}(enlace|instrucciones|https?|pagar|pago|confirme)"),
                Regex("(?is)aduanas?.{0,60}(enlace|instrucciones|https?|pagar|pago)"),
                Regex("(?is)agencia tributaria.{0,150}(reembolso|devoluci[oó]n|favorable|pulse|enlace|https?)"),
                Regex("(?is)(cargo|compra|pre-cargo).{0,80}(no autorizado|no reconoce|no ha sido usted).{0,60}(verifi|enlace|cancel|https?|siga)"),
                Regex("(?is)(te felicitan|felicita) por ser.{0,40}ganador|ganador.{0,40}(motocicleta|millones de pesos|iphone)"),
                Regex("(?is)verificaci[oó]n requerida"),
                Regex("(?is)puntos.{0,30}(vencer|caducar).{0,40}(canjear|cambiar|efectivo)"),
                // ── Italian ──
                Regex("(?is)(carta|conto|account|utenza|app|accesso|utenze).{0,60}(bloccat|sospes|limitat|disabilitat).{0,100}(verific|aggiorn|conferm|clicc|link|https?|accedi|riattiv|segu|ripristin|procedura|sblocc)"),
                Regex("(?is)(non (sei|è|e) (stato )?(tu|lei)|se non sei tu|disconosc\\w+|non riconosci).{0,60}(clicc|link|segui|blocca|accedi|https?|procedura)"),
                Regex("(?is)psd2.{0,100}(blocc|aggiorn|conferm|dati|procedura|attivazione)"),
                Regex("(?is)(pacco|spedizione|ordine|consegna).{0,80}(trattenut|indirizzo (errato|sconosciuto)|spese di spedizione|in attesa).{0,100}(clicc|link|https?|istruzioni|aggiorn|qui|pagare|segui)"),
                Regex("(?is)pedaggi.{0,60}(non pagat|scadut|pagare)|non pagat\\w*.{0,40}pedaggi"),
                Regex("(?is)mancat[oa] (aggiornamento|attivazione).{0,60}blocc"),
                Regex("(?is)nuovo dispositivo.{0,80}(link|https?|bloccalo|segui|accedi)"),
                Regex("(?is)gentile cliente.{0,100}(verific|conferm|aggiorn).{0,40}(dati|conto|carta|link|https?)"),
                // ── Portuguese ──
                Regex("(?is)(conta|cart[aã]o|acesso|chave|app).{0,40}(bloquead|suspens|suspend|cancelad|limitad|desativad).{0,100}(verific|atualiz|confirm|clique|link|https?|acesse|regulariz|aqui)"),
                Regex("(?is)(encomenda|pacote|entrega|objeto).{0,80}(retid|taxa|pendente|endere[cç]o|n[aã]o foi poss[ií]vel|devolvid).{0,100}(clique|link|https?|pague|pagamento|regulariz|aqui|atualiz)"),
                Regex("(?is)pix.{0,60}(bloquead|irregular).{0,60}(clique|link|https?|regulariz)"),
                Regex("(?is)(correios|cpf).{0,80}(irregular|pendente|bloquead|suspens).{0,80}(clique|link|https?|regulariz)"),
                Regex("(?is)(n[aã]o foi voc[eê]|n[aã]o reconhece).{0,60}(clique|link|acesse|https?)"),
                // ── Malay ──
                Regex("(?is)(pakej|bungkusan|parcel).{0,60}(alamat|tidak dapat dihantar|tertahan|ditahan|tertangguh).{0,100}(kemaskini|klik|https?|sila|uruskan|bayar|whatsapp)"),
                Regex("(?is)(telah|akan) (di)?kredit(kan)?.{0,30}(ewallet|e-dompet|e-wallet)"),
                Regex("(?is)bantuan.{0,80}(ewallet|e-dompet|e-wallet).{0,100}(https?|klik|sila|semak)"),
                Regex("(?is)(akaun|kad).{0,40}(dibekukan|digantung|disekat|ditutup).{0,100}(https?|klik|sila|hubungi|kemaskini|pengesahan)"),
                Regex("(?is)bukan transaksi anda.{0,80}(https?|klik|hubungi|sila)"),
                // ── Polish ──
                Regex("(?is)paczk\\w*.{0,80}(wstrzyman|zawieszon|zatrzyman|niedop[łl]at|celn|nie mog[łl]a zosta[ćc] dostarczona|b[łl][ęe]dn\\w* adres).{0,100}(https?|prosimy|uregul|ui[śs]ci|zap[łl]a|kliknij|odbi|aktualiz|podaj)"),
                Regex("(?is)niezap[łl]acon\\w*.{0,20}mandat"),
                Regex("(?is)od[łl][ąa]czenie.{0,30}energii.{0,60}(https?|zapłać|zaplac|kliknij|:)"),
                // ── Turkish ──
                Regex("(?is)(hesab\\w*|kullan[ıi]c\\w*).{0,60}(onaylamayan|bloke|ask[ıi]ya|kapat[ıi]l).{0,80}(https?|t[ıi]kla|onayla|do[ğg]rula|giri[şs])"),
                Regex("(?is)[şs]üpheli i[şs]lem.{0,100}(https?|hesab|do[ğg]rula)"),
                Regex("(?is)(internet bankac\\w*|mobil [şs]ube)\\w*.{0,40}giri[şs].{0,60}(kazan|para|tl)"),
                Regex("(?is)e-?devlet.{0,80}(giri[şs]|link|tan[ıi]ml)"),
                Regex("(?is)binance.{0,120}(hesab|hediye|bnb|bakiye|kullan[ıi]c)"),
                Regex("(?is)b ?i ?n ?a ?n ?c ?e ?\\].{0,100}(bnb|m[üu][şs]teri|hediye)"),
                // ── English lures in plain words ──
                Regex("(?is)(account|a/c|netbanking|net banking|yono|card|debit|id|membership|wallet).{0,30}(has been|is now|is|will be|was)\\s+(temporarily\\s+)?(blocked|suspended|restricted|locked|deactivated|disabled|on hold|limited|frozen|disconnected|hold|suspend|block).{0,100}(update|verify|confirm|click|link|log ?in|restore|regain|recover|secure|visit)"),
                Regex("(?is)(update|link|verify|complete).{0,15}(your\\s+)?(pan ?card|pan no|pan number|kyc|aadhaar)"),
                Regex("(?is)(package|parcel|delivery|shipment|pkg).{0,60}(incomplete address|unable to deliver|could not be delivered|cannot be delivered|import duty|customs fee|redelivery fee).{0,100}(confirm|update|reschedule|click|follow|link|pay|visit|here)"),
                Regex("(?is)\\bi\\.?r\\.?s\\b.{0,40}(refund|tax)"),
                Regex("(?is)(unpaid|outstanding|overdue|pending).{0,15}toll|toll.{0,25}(unpaid|outstanding|overdue)"),
                Regex("(?is)(electricity|power|bijli).{0,25}(will be|to be) (disconnected|cut|cut off|terminated)"),
                Regex("(?is)part.?time.{0,40}(online|from home|remote).{0,60}(earn|income|salary|commission|daily|per day)"),
                Regex("(?is)kyc.{0,25}\\b(expire[sd]?|pending|suspend\\w*|block\\w*|incomplete)\\b"),
                Regex("(?is)(dues|loan).{0,30}(not paid|unpaid|overdue|repaid).{0,80}(legal|necessary measures|police|court)"),
                Regex("(?is)(account|a/c|card|wallet|banking|netbanking|membership|sim|access|login).{0,40}(blocked|suspended|restricted|locked|deactivated|disabled|on hold|compromised|closed|terminated|limited).{0,100}(update|verify|confirm|click|link|log ?in|restore|regain|reactivate|visit|http|tap|secure)"),
                Regex("(?is)points.{0,40}(expire[sd]?|expiring).{0,60}(redeem|cash|click|convert)"),
                Regex("(?is)(package|parcel|delivery|shipment|pkg).{0,80}(on hold|undeliverable|undelivered|unpaid|outstanding|customs|address|not (been )?delivered|missed|rebook|reschedule).{0,100}(click|visit|follow|confirm|update|pay|link|http|here)"),
                Regex("(?is)toll.{0,60}(not (been )?paid|unpaid|outstanding)"),
                Regex("(?is)outstanding payment (history|record)"),
                Regex("(?is)(not you|was not you|wasn.t you|didn.t do this|did not do this|do not recogni[sz]e).{0,60}(visit|click|tap|go to|log ?in|verify|reactivate).{0,20}(here|:|https?://|www)"),
                Regex("(?is)(payee|payment|transfer|transaction).{0,80}(unrecogni[sz]ed|not you|was not you|do not recogni[sz]e|don.t recogni[sz]e|attempt).{0,60}(visit|https?://|click|verify|approve|decline)"),
                Regex("(?is)kindly (click|update|verify|redeem|convert|submit).{0,40}(link|here|points|account|document|kyc|http)"),
                Regex("(?is)(sim|number|service|electricity|ac|a/c).{0,30}(will be|to be|has been) ?(suspended|blocked|disconnected|closed|deactivated).{0,60}(call|contact|immediately|update|click)"),
                Regex("(?is)(irs|tax).{0,40}(refund).{0,80}(incomplete|confirm|click|claim|link|pdf)"),
                Regex("(?is)(account|atm card|debit card|online access|internet banking|online banking).{0,50}(blocked|compromised|deactivated|suspended|locked|restricted).{0,100}(visit|click|tap|verify|update|confirm|https?|call us|contact)"),
                Regex("(?is)road toll.{0,80}(as soon as possible|incur|overdue|outstanding|immediately)"),
                Regex("(?is)(income ?tax ?department|dvla|hmrc|council tax).{0,150}(refund|claim|click|https?|overdue)"),
                Regex("(?is)(loan|credit score|credit limit).{0,50}(approved|generated).{0,80}(confirm now|check (the )?(status|details)|click|apply now|https?)"),
                Regex("(?is)kyc.{0,60}(hold|pending|expire|update|block|suspend|submit)"),
                Regex("(?is)you (have |'ve )?($WON|win)\\b.{0,60}(prize|lottery|iphone|cash|rs[. -]?\\d)"),
                Regex("(?is)(apple|icloud|paypal|netflix|amazon|facebook) (id|acc?o+unt).{0,50}(locked|suspended|disabled|limited|on hold).{0,80}(details|here|click|verify|https?|submit)"),
                Regex("(?is)(congrat\\w*|you have unlocked).{0,80}($WON|unlocked|cashback|scratch card|approved|generated).{0,100}(click|tap|redeem|confirm|https?|claim)"),
                Regex("(?is)(lost|missing) (iphone|ipad|macbook|device).{0,80}(located|found|location).{0,60}(https?|click|check)"),
                Regex("(?is)(donation|inheritance|grant) (for you|in your name|of).{0,80}(total sum|gbp|usd|million|\\\$|£)"),
                Regex("(?is)wallet.{0,40}(accessed|linked|connected).{0,100}(confirm|review|https?|verify)"),
                Regex("(?is)\\b(account|a/c|card|internet banking|online banking)\\b(?=.*https?://).{0,80}\\b(suspended|restricted|blocked|deactivated|terminated|locked|on hold)\\b.{0,160}\\b(update|verify|confirm|reactivate|restore|unlock|log ?in|sign ?in|click|visit)\\b"),
                Regex("(?is)\\b(suspended|restricted|blocked|deactivated|locked)\\s+(your|ur)\\b(?=.*https?://).{0,40}\\b(account|a/c|card|internet banking|online banking)\\b.{0,160}\\b(update|verify|confirm|reactivate|restore|unlock|log ?in|sign ?in|click|visit)\\b"),
                Regex("(?is)\\b(update|verify|confirm|review)\\b.{0,40}\\b(account|a/c)\\b.{0,40}\\bto avoid\\b.{0,30}\\b(suspension|deactivation|termination|restriction)"),
                Regex("(?is)\\b(s'?im|m0?obile number)\\b.{0,20}\\bdocu\\w+\\b.{0,50}\\b(pending|expired?|reject\\w*|block\\w*)\\b"),
                Regex("(?is)\\bdear customer\\b.{0,120}\\b(block\\w*|locked|expired|disconnect\\w*)\\b.{0,120}\\bcall\\b.{0,40}\\b(customer care|immediately|office\\w*)\\b|\\bdear customer\\b.{0,120}\\bcall\\b.{0,60}\\b(block\\w*|locked|disconnect\\w*)\\b"),
                // Work-from-home job offers (en, es, pt, it). See JOB_OFFER_PATTERN.
                Regex(JOB_OFFER_PATTERN),
            )

        // How to answer or stop a text. Every business text sent under US
        // carrier rules carries them ("Reply STOP to opt out", "Reply Y to
        // confirm"), and alone they blocked a clinic's appointment reminder in
        // aggressive mode. Scams copy the footer onto a chatty opener too
        // ("Hey Sam, are you free for coffee? Reply STOP to unsubscribe"), so
        // they still count next to any other signal, or when the text doesn't
        // say who sent it (see [namesItsSender]).
        private val responseCues =
            listOf(
                Regex("(?i)(unsubscribe|opt.?out|stop to (end|cancel|quit|unsubscribe))"),
                Regex("(?i)text (yes|y|go|start|ok) to"),
                Regex("(?i)reply (yes|y|stop|1|2)"),
            )

        // The carrier rules also make a business say who it is first:
        // "Lakeside Family Clinic: your visit is...", "[Corner Market] your
        // order..." or "this is Dana from Maple Street Dental". The colon has to
        // be followed by a space, so "3:30", "https://" and "tel:" aren't names.
        private val senderPrefix =
            Regex("^[^\\p{L}\\p{N}\\[]*(?:\\[([^\\]\\n]{1,40})]|([\\p{L}\\p{N}][\\p{L}\\p{N}&'’.\\- ]{0,39}):(?:$SPACE|\$))")

        // Each name word is its own run of letters, never spaces, so a long run
        // of spaces after "this is" can't make the engine try every split.
        private val senderIntroduction =
            Regex("(?i:\\bthis is)$SPACE+(?:\\p{L}[\\p{L}'’.-]*$SPACE+){1,3}?(?i:from|at|with)$SPACE+\\p{Lu}")

        private val nameWordSeparator = Regex("$SPACE+")

        // Openers that start a notice or a greeting, not a business's name:
        // "Alert:", "Valued customer:", "Hi Sam:", "Quick question:".
        private val notANameOpener =
            (
                "hi hey hello hiya howdy yo greetings dear good valued customer member user client friend sir madam " +
                    "alert notice warning urgent important attention final reminder update info notification action " +
                    "security account message msg re fwd fw your our my the a congrats congratulations quick question " +
                    "note ps http https www tel sms"
            ).split(' ').toSet()

        // Lowercase words a business name can carry: "Bank of America", "Smith & Sons".
        private val nameConnectors = "of the and for at on de del la le du des da do y & + -".split(' ').toSet()

        // A sender's name is a few capitalized words or digits ("Rosa's Kitchen",
        // "Bank of America", "7-Eleven", "24 Hour Fitness") that doesn't open
        // with a greeting or a notice word.
        private fun looksLikeName(name: String): Boolean {
            val words = name.split(nameWordSeparator).filter { it.isNotEmpty() }
            if (words.isEmpty() || words.size > 5 || name.none { it.isLetter() }) return false
            if (words.first().lowercase().trim { !it.isLetterOrDigit() } in notANameOpener) return false
            return words.all { word -> word.lowercase() in nameConnectors || word.any { it.isUpperCase() || it.isDigit() } }
        }

        private fun namesItsSender(text: String): Boolean {
            val prefix = senderPrefix.find(text)
            val name = prefix?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            return (name != null && looksLikeName(name)) || senderIntroduction.containsMatchIn(text)
        }

        // Phone number in SMS body (common in callback scams)
        private val phoneInBody =
            Regex(
                "(?:call|dial|text|contact)\\s*(?:us\\s+(?:at|on))?\\s*\\+?\\d[\\d\\s\\-()]{7,}",
                RegexOption.IGNORE_CASE,
            )

        // URL pattern — length-capped to prevent ReDoS on pathological inputs
        private val urlPattern =
            Regex(
                "https?://[^\\s]{1,2048}|www\\.[^\\s]{1,2048}|" +
                    // A Cyrillic а in front of pple.com is part of the word, so
                    // the edge is any letter, not only an ASCII one.
                    "(?<![\\p{L}\\p{N}-])[a-zA-Z0-9][a-zA-Z0-9-]{0,62}" +
                    "\\.[a-zA-Z]{2,63}/[^\\s]{0,2048}",
            )

        // ── Spam Domain Blocklist ─────────────────────────────────────────
        // Community-reported phishing/spam domains. Loaded from GitHub's
        // spam_domains.json and refreshed every 30 minutes by HotListSyncWorker.
        @Volatile
        private var spamDomains: Set<String> = emptySet()

        fun updateSpamDomains(domains: Collection<String>) {
            spamDomains =
                domains
                    .asSequence()
                    .mapNotNull(::normalizeDomainCandidate)
                    .toSet()
        }

        fun hasSpamDomains(): Boolean = spamDomains.isNotEmpty()

        /**
         * True only for a short, bounded message that has both an explicit
         * verification/OTP phrase and a plausible one-time code. Requiring
         * both prevents ordinary "verify your account" phishing copy or an
         * arbitrary order number from receiving the safety exemption.
         */
        fun isVerificationMessage(body: String): Boolean {
            if (body.isBlank() || body.length > MAX_VERIFICATION_BODY_LENGTH) return false
            val phrase = verificationPhrasePattern.containsMatchIn(body)
            if (!phrase) return false

            val numericCode = numericVerificationCodePattern.containsMatchIn(body)
            val alphaNumericCode =
                alphaNumericVerificationCodePattern
                    .findAll(body)
                    .any { it.value.any(Char::isDigit) }
            return numericCode || alphaNumericCode
        }

        /** The host of a URL string, without scheme, user info, www., port, path or the root's dot. */
        private fun extractDomain(url: String): String {
            val lower =
                url
                    .lowercase()
                    .trim()
                    .trimEnd('.', ',', '!', '?', ';', ':', ')', ']', '}')
            // A browser reads a backslash in a web link as a slash, so the host
            // of evil.com\@paypal.com is evil.com.
            val authority =
                lower
                    .substringAfter("://", lower)
                    .substringBefore('/')
                    .substringBefore('\\')
                    .substringBefore('?')
                    .substringBefore('#')
            return authority
                .substringAfterLast('@')
                .substringBefore(':')
                .trimEnd('.')
                .removePrefix("www.")
        }

        fun isKnownSpamDomainUrl(url: String): Boolean {
            val domain = extractDomain(url)
            return domain.isNotEmpty() && isKnownSpamDomain(domain)
        }

        private data class ImpersonatedBrand(
            val keywords: List<String>,
            val realDomains: Set<String>,
        )

        private val impersonatedBrands =
            listOf(
                ImpersonatedBrand(listOf("usps"), setOf("usps.com")),
                ImpersonatedBrand(listOf("ezpass", "e-zpass"), setOf("e-zpassny.com", "e-zpassiag.com", "e-zpassnj.com", "e-zpassmd.com")),
                ImpersonatedBrand(listOf("sunpass"), setOf("sunpass.com")),
                ImpersonatedBrand(listOf("fastrak"), setOf("bayareafastrak.org", "thetollroads.com")),
                ImpersonatedBrand(listOf("txtag"), setOf("txtag.org")),
            )

        private val govLikePattern = Regex("(?:^|[.-])gov(?:[.-]|$)", RegexOption.IGNORE_CASE)

        internal fun isLookalikeHost(host: String): Boolean {
            if (host.isEmpty()) return false
            val lower = host.lowercase()
            for (brand in impersonatedBrands) {
                if (brand.realDomains.any { lower == it || lower.endsWith(".$it") }) continue
                val hasBrand = brand.keywords.any { kw -> lower.contains(kw) }
                if (hasBrand && govLikePattern.containsMatchIn(lower)) return true
            }
            return false
        }

        /**
         * Score a single extracted host. Returns the added score and the reason
         * tag, or `(0, null)` for a benign / empty host. Comparisons are on the
         * host, never the raw URL, so a shortener/TLD name appearing as a
         * substring of a legitimate domain or path does not false-positive.
         */
        private fun scoreUrlDomain(domain: String): Pair<Int, String?> =
            when {
                domain.isEmpty() -> 0 to null
                spamDomains.isNotEmpty() && isKnownSpamDomain(domain) -> 50 to "spam_domain"
                isLookalikeHost(domain) -> 45 to "lookalike_host"
                shortenerDomains.any { domain == it || domain.endsWith(".$it") } -> 35 to "shortened_url"
                suspiciousTlds.any { domain.endsWith(it) } -> 30 to "suspicious_tld"
                else -> 0 to null
            }

        private fun isKnownSpamDomain(domain: String): Boolean {
            var matched = false
            var candidate = if (spamDomains.isEmpty()) null else normalizeDomainCandidate(domain)
            while (!matched && !candidate.isNullOrBlank()) {
                if (candidate in spamDomains) {
                    matched = true
                } else {
                    val dotIndex = candidate.indexOf('.')
                    candidate = if (dotIndex < 0) "" else candidate.substring(dotIndex + 1)
                }
            }
            return matched
        }

        /**
         * Extract only privacy-preserving SMS report fields. This never returns
         * message text, URL paths, query strings, or fragments.
         */
        fun extractReportableIndicators(body: String): SmsReportIndicators {
            if (body.isBlank()) return SmsReportIndicators()

            val analysisBody =
                if (body.length > MAX_ANALYSIS_LENGTH) {
                    body.substring(0, MAX_ANALYSIS_LENGTH)
                } else {
                    body
                }
            val domains = linkedSetOf<String>()
            val indicators = linkedSetOf<String>()

            // Invisible characters would split a host and hide it from the pattern.
            urlPattern.findAll(SmsTextNormalizer.normalize(analysisBody).visible).forEach { match ->
                val url = match.value.lowercase()
                val domain = normalizeDomainCandidate(extractDomain(url))
                indicators.add("url_present")
                if (domain != null) {
                    if (domains.size < MAX_REPORT_DOMAINS) {
                        domains.add(domain)
                    }
                    if (shortenerDomains.any { domain == it || domain.endsWith(".$it") }) {
                        indicators.add("shortener")
                    }
                    if (suspiciousTlds.any { domain.endsWith(it) }) {
                        indicators.add("suspicious_tld")
                    }
                    if (isKnownSpamDomain(domain)) {
                        indicators.add("known_spam_domain")
                    }
                }
            }

            return SmsReportIndicators(
                domains = domains.toList(),
                urlIndicators = indicators.sorted(),
            )
        }

        /**
         * Hard cap on the body length we attempt to deep-analyze. A real SMS is
         * capped by the GSM/UCS-2 segment limit (~6 400 chars at 40 segments),
         * but RCS, MMS attachments, and inbox-scan paths can hand us much
         * larger blobs. Running 25+ regexes plus [urlPattern.findAll] over a
         * multi-MB string is a real ReDoS risk on the 5-second screening
         * deadline — at this length the message is almost certainly spam
         * anyway, so we sample the first 16 KB and stop.
         *
         * [firstContact] says it's the first text from a phone number the user
         * has never texted or heard from, the only place a friendly opener with
         * no link is suspicious (see [SmsReplyBait]).
         */
        fun analyze(
            body: String,
            firstContact: Boolean = false,
        ): SmsAnalysisResult {
            var score = 0
            val reasons = mutableListOf<String>()

            if (body.isBlank()) return SmsAnalysisResult(0, emptyList())

            // Length guard: cap the input we feed into the regex engine so a
            // hostile / malformed body can't pin the screening thread.
            val analysisBody =
                if (body.length > MAX_ANALYSIS_LENGTH) {
                    score += 10
                    reasons.add("oversized_body")
                    body.substring(0, MAX_ANALYSIS_LENGTH)
                } else {
                    body
                }
            // Hosts are read without invisible characters but keep their own
            // letters, so a Cyrillic look-alike of a real domain doesn't pass
            // for it. Every other rule reads look-alikes as Latin.
            val normalized = SmsTextNormalizer.normalize(analysisBody)
            val visibleText = normalized.visible
            val ruleText = normalized.folded

            if (CallForwardingCode.find(ruleText) != null) {
                score += CallForwardingCode.SCORE
                reasons.add(CallForwardingCode.SIGNAL)
            }

            // Check for URL shorteners (high spam signal)
            val urls =
                if ('.' in visibleText || visibleText.contains("http", ignoreCase = true)) {
                    urlPattern.findAll(visibleText).map { it.value.lowercase() }.toList()
                } else {
                    emptyList()
                }
            for (url in urls) {
                // Match against the extracted host, not the raw URL — a substring
                // check flags "t.co" inside microsoft.com / reddit.com / target.com
                // and ".info" inside any /file.info path. Mirror the correct logic
                // in extractReportableIndicators.
                val (urlScore, urlReason) = scoreUrlDomain(extractDomain(url))
                if (urlReason != null) {
                    score += urlScore
                    reasons.add(urlReason)
                }
            }

            if (normalized.disguised) {
                score += 30
                reasons.add("disguised_text")
            }

            // The signals scored below, worked out first so the footer decision
            // can see every one of them.
            val alphaChars = ruleText.filter { it.isLetter() }
            val shouting = alphaChars.length > 10 && alphaChars.count { it.isUpperCase() }.toFloat() / alphaChars.length > 0.5f
            val callback = phoneInBody.containsMatchIn(ruleText)
            val specialRatio =
                ruleText.count { !it.isLetterOrDigit() && !it.isWhitespace() }.toFloat() /
                    ruleText.length.coerceAtLeast(1)
            val specialHeavy = specialRatio > 0.15f && ruleText.length > 20
            val shortWithUrl = visibleText.length < 50 && urls.isNotEmpty()
            val replyBait = firstContact && SmsReplyBait.matches(ruleText, urls, visibleText.length)
            val otherSignal = score > 0 || shouting || callback || specialHeavy || shortWithUrl || replyBait

            // Check spam keyword patterns
            var patternHits = 0
            val countPatternHit = {
                patternHits++
                if (patternHits == 1) {
                    score += 25
                    reasons.add("spam_keywords")
                } else {
                    score += 15 // Each additional pattern is more damning
                }
            }
            for (pattern in spamPatterns) {
                if (patternHits >= 3) break // Cap pattern contribution
                if (pattern.containsMatchIn(ruleText)) countPatternHit()
            }
            val urgentWithStakes =
                urgentWord.containsMatchIn(ruleText) &&
                    !urgencyPattern.containsMatchIn(ruleText) &&
                    (urls.isNotEmpty() || callback || urgentStakes.containsMatchIn(ruleText))
            // Once per text, like the rule it came out of.
            if (patternHits < 3 && urgentWithStakes) countPatternHit()
            if (patternHits > 0 || otherSignal || !namesItsSender(ruleText)) {
                for (cue in responseCues) {
                    if (patternHits >= 3) break
                    if (cue.containsMatchIn(ruleText)) countPatternHit()
                }
            }

            // All caps text (>50% of message) — shouting is a spam signal
            if (shouting) {
                score += 15
                reasons.add("excessive_caps")
            }

            // Contains phone number in body (callback scam) — regex is
            // case-insensitive so we match the original body instead of lowercasing.
            if (callback) {
                score += 10
                reasons.add("callback_number")
            }

            // Excessive special characters / emoji (common in spam)
            if (specialHeavy) {
                score += 10
                reasons.add("special_chars")
            }

            // Very short message with URL (likely phishing). Counted without
            // invisible characters, which would otherwise pad it past the limit.
            if (shortWithUrl) {
                score += 20
                reasons.add("short_msg_with_url")
            }

            if (replyBait) {
                score += REPLY_BAIT_SCORE
                reasons.add("reply_bait")
            }

            return SmsAnalysisResult(score.coerceAtMost(100), reasons)
        }

        companion object {
            val shared: SmsContentAnalyzer = SmsContentAnalyzer()

            internal const val MAX_ANALYSIS_LENGTH = 16_384
            internal const val MAX_REPORT_DOMAINS = 10

            // On its own, reply bait clears aggressive mode's bar (25) but not
            // the default one (50): a real child with a new phone writes the
            // same words, so it takes a second signal to block by default.
            internal const val REPLY_BAIT_SCORE = 30

            // "won" as in a prize, never the start of "won't" or "wont".
            private const val WON = "won(?!['’]?t\\b)"
            private const val MAX_VERIFICATION_BODY_LENGTH = 1_024
            private const val MIN_REPORT_DOMAIN_LENGTH = 5
            private const val MAX_REPORT_DOMAIN_LENGTH = 253
            private const val MAX_REPORT_DOMAIN_LABEL_LENGTH = 63

            // ── Work-from-home job offers ─────────────────────────────────
            // Pay by the day or hour quoted in money, and work from home or
            // liking videos, in either order. It takes both halves: a shift or
            // payslip text quotes a rate, and "en casa" is everyday speech.
            // Word edges are \p{L} lookarounds, not \b, and a space is spelled
            // out with \p{Zs}: the JVM's regex engine and Android's ICU engine
            // disagree about accented letters in \b and no-break spaces in \s.
            private const val WORD_START = "(?<![\\p{L}\\p{N}])"
            private const val WORD_END = "(?![\\p{L}\\p{N}])"
            private const val SPACE = "[\\s\\p{Zs}]"
            private const val JOB_VERB =
                "(?:earn(?:s|ing)?|mak(?:e|ing)|get${SPACE}paid|getting${SPACE}paid|pa(?:y|ys|ying)|receiv(?:e|ing)" +
                    "|gan(?:a|e|ar(?:.s?)?|ando)|ganh(?:a|e|ar(?:.s?)?|ando)|recib(?:e|a|ir|iendo)|receb(?:a|e|er|endo)" +
                    "|guadagn(?:a|are|i|erai|ando)|ricev(?:i|ere|erai|endo))"
            private const val JOB_MONEY =
                "(?:(?:\\p{Sc}|r\\\$)$SPACE?\\d" +
                    "|\\d[\\d.,]*$SPACE?(?:\\p{Sc}|(?:usd|eur|euros?|reais|d.lares|dollars?|bucks|pounds?|gbp)$WORD_END))"

            // An amount next to one of these is a discount or a bonus, not pay.
            private const val JOB_NOT_PAY =
                "(?:cashback|desconto|descuento|sconto|discount|b.nus|bonus|pontos|puntos|punti|points|off$WORD_END)"

            // "per ora" is Italian for "for now", so the hourly forms there are
            // all'ora, l'ora, ogni ora and /ora.
            private const val JOB_RATE =
                "(?:/$SPACE?(?:day|d.a|giorno|hour|hora|ora|hr|h|week|semana|settimana)" +
                    "|$WORD_START(?:a|an|per|each|every)$SPACE(?:day|hour|hr|week)" +
                    "|$WORD_START(?:al|por|cada|la)$SPACE(?:d.a|hora|semana)" +
                    "|$WORD_START(?:ao|por|cada)$SPACE(?:dia|hora|semana)" +
                    "|$WORD_START(?:al|ogni)$SPACE(?:giorno|settimana)" +
                    "|$WORD_START(?:all'|l'|ogni$SPACE)ora" +
                    "|$WORD_START(?:daily|hourly|weekly|diari[oa]s?|di.rios?|giornalier[oi]))$WORD_END"
            private const val JOB_WORK =
                "$WORD_START(?:" +
                    "(?:work(?:ing)?|jobs?)$SPACE(?:from|at)$SPACE(?:home|your${SPACE}phone)" +
                    "|from$SPACE(?:home|your${SPACE}phone|the${SPACE}comfort${SPACE}of$SPACE(?:your|their)(?:${SPACE}own)?${SPACE}home)" +
                    "|at${SPACE}home" +
                    "|(?:liking|like|rating|reviewing|watching)$SPACE(?:[^\\s\\p{Zs}]+$SPACE){0,2}?(?:videos|posts|products|reviews)" +
                    "|desde(?:$SPACE(?:tu|su))?$SPACE(?:casa|celular|m.vil|tel.fono)|en(?:$SPACE(?:tu|su))?${SPACE}casa|teletrabajo" +
                    "|(?:dando|dar|poniendo)$SPACE(?:me${SPACE}gusta|likes?)|(?:viendo|ver)${SPACE}v.deos" +
                    "|de${SPACE}casa|em${SPACE}casa|(?:do|pelo)(?:${SPACE}seu)?${SPACE}celular|home${SPACE}office" +
                    "|(?:curtindo|curtir)$SPACE(?:[^\\s\\p{Zs}]+$SPACE)?(?:v.deos|posts|fotos)" +
                    "|(?:assistindo|assistir)$SPACE(?:a$SPACE)?v.deos" +
                    "|da${SPACE}casa|a${SPACE}casa|dal(?:${SPACE}tuo)?$SPACE(?:telefono|cellulare)" +
                    "|(?:mettendo|mettere)$SPACE(?:like|mi${SPACE}piace)|(?:guardando|guardare)${SPACE}video" +
                    ")$WORD_END"

            /** Both halves anywhere in the body, each checked from the start. */
            internal const val JOB_OFFER_PATTERN =
                "(?is)^(?=.*?$WORD_START$JOB_VERB$WORD_END(?:(?!$JOB_NOT_PAY).){0,40}?$JOB_MONEY" +
                    "(?:(?!$JOB_NOT_PAY).){0,25}?$JOB_RATE)(?=.*?$JOB_WORK)"

            private val verificationPhrasePattern =
                Regex(
                    "(?i)\\b(?:verification|security|login|authentication|confirmation)\\s+(?:code|number|token|pin)\\b" +
                        "|\\b(?:one[- ]time|otp|2fa|mfa)\\s*(?:password|passcode|code|pin|token)?\\b" +
                        "|\\bpasscode\\b",
                )
            private val numericVerificationCodePattern = Regex("(?<!\\d)\\d{4,8}(?!\\d)")
            private val alphaNumericVerificationCodePattern = Regex("(?i)(?<![a-z0-9])[a-z0-9]{4,10}(?![a-z0-9])")

            /**
             * Not part of a Latin word, a longer number or a link path. Spelled
             * out because Android's \w also covers Chinese and other letters,
             * which are written right against a number.
             */
            private const val NOT_AFTER = "(?<![0-9A-Za-z_/+])"

            /** Between the parts of a number: a space or two, or a dot or dash with spaces around it. */
            private const val NUMBER_GAP = "(?:[ \\t]{0,2}[.-][ \\t]{0,2}|[ \\t]{1,3})?"

            /**
             * An optional 1 or +1, then area code, exchange and line.
             */
            private val nanpNumber =
                Regex("$NOT_AFTER(\\+?1$NUMBER_GAP)?\\(?([2-9]\\d{2})\\)?$NUMBER_GAP([2-9]\\d{2})$NUMBER_GAP(\\d{4})(?!\\d)")
            private val internationalNumber = Regex("$NOT_AFTER\\+[1-9](?:[ \\t.()-]{0,3}\\d){6,14}(?!\\d)")

            /** The national trunk 0 some write after the country code: +44 (0)20. */
            private val trunkZero = Regex("(\\+\\d{1,3})[ \\t]*\\(0\\)")
            private const val MAX_CALLBACK_SCAN_CHARS = 2_048
            private const val MAX_CALLBACK_NUMBERS = 5
            private val INTERNATIONAL_DIGITS = 8..15

            /**
             * A number written the way people write one at home: 7 to 15 digits
             * with the usual gaps, which also covers one dialed with "00" in
             * front. Whether it's a line is for the home region's validator to
             * say. Not after a "#", "\u2116", "\u00BA", "\u00B0", a currency sign or a
             * decimal point, and not before a decimal part, a percent, or a
             * currency sign or code.
             */
            private val nationalNumber =
                Regex(
                    "(?<![0-9A-Za-z_/+#.,\u2116\u00BA\u00B0\u20AC\$\u00A3\u00A5\u20B9])\\(?\\d(?:[ \\t.()-]{0,2}\\d){6,14}\\)?" +
                        "(?![0-9]|[.,]\\d|[ \\t]?(?:[%\u20AC\$\u00A3\u00A5\u20B9]|(?i:$CURRENCY_CODES)\\b))",
                )
            private val dateShape = Regex("\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}|\\d{4}[./-]\\d{1,2}[./-]\\d{1,2}")
            private const val CURRENCY_CODES = "eur|usd|gbp|chf|sek|nok|dkk|pln|czk|huf|inr|aud|cad|kr|rs"
            private val currencyBefore = Regex("(?i)(?:\\b(?:$CURRENCY_CODES)\\.?|[\u20AC\$\u00A3\u00A5\u20B9])[ \\t]*$")

            /**
             * What a text asking to be called says before the number, in the
             * languages the reports come in. A bare national number counts only
             * after one of these: without the "+" there's nothing else to tell a
             * line from an account, a price or a parcel.
             */
            private val callWord =
                Regex(
                    "(?iu)\\b(?:call(?:ing)?|ring|phone|dial|tel|telephone|contact\\w*|helpline|hotline|free(?:phone|fone)|" +
                        "ll[a\u00E1]m\\w*|ligu\\w*|ligar|liga|marque|marcar|" +
                        "telef\\w*|tel[e\u00E9]fono|t[e\u00E9]l[e\u00E9]phon\\w*|t[e\u00E9]l|appel\\w*|rappel\\w*|joindre|compos(?:ez|er)|" +
                        "anruf\\w*|rufen|ruf|w[a\u00E4]hlen|chiam\\w*|contatt\\w*|kontakt\\w*|bel|bellen)\\b",
                )

            /** Words that only ever come before an identifier: "order", "Kundennummer". */
            private const val REFERENCE_WORDS =
                "order|ref|reference|code|pin|id|invoice|tracking|ticket|case|account|acct|confirmation|otp|serial|iban|bic|swift|" +
                    "pedido|factura|referencia|seguimiento|commande|suivi|facture|r[e\u00E9]f[e\u00E9]rence|dossier|" +
                    "bestell\\w*|rechnung\\w*|kunden-?(?:nummer|nr)|konto\\w*|kto|auftrag\\w*|aktenzeichen|vorgang\\w*|" +
                    "ordine|fattura|pratica|codice|encomenda|fatura|bestelling|factuur|klantnummer"

            /**
             * Words that come before a parcel, a booking or an amount, and as
             * often before a line to call: "your booking 020 7946 0018", "call
             * to claim 0906 170 1461".
             */
            private const val LOOSE_REFERENCE_WORDS =
                "parcel|package|shipment|booking|reservation|policy|claim|colis|sendung\\w*|" +
                    "amount|total|balance|sum|importe|monto|saldo|betrag|summe|montant|solde|importo|valor"

            /**
             * A reference, an account or a code right before a number, so it
             * isn't read as a line to call: "order 2079460018", "Tracking
             * number: 2079460018", "Kundennummer lautet 3012345678", "booking
             * ref 0207946001". Only "number" and "is" may sit between, so
             * "call our booking line on" still asks for a call.
             */
            private val referenceWord =
                Regex(
                    "(?iu)(?:[#\u2116\u00BA\u00B0]|\\b(?:$REFERENCE_WORDS)\\b|\\b(?:no\\.|nr\\b\\.?|n[\u00BA\u00B0])|" +
                        "\\b(?:$LOOSE_REFERENCE_WORDS)\\b[ \\t.:-]*" +
                        "(?:#|n[\u00BA\u00B0]|\\b(?:number|nummer|n[u\u00FA]mero|num[e\u00E9]ro|numero|nr|no|id|code|ref|reference)\\b))" +
                        "[ \\t.:#-]*(?:(?:number|nummer|n[u\u00FA]mero|num[e\u00E9]ro|numero|nr|no|n[\u00BA\u00B0]|id|code|is|ist|lautet|est|es|de|del)\\b\\.?[ \\t.:#-]*){0,3}$",
                )

            /**
             * A parcel, booking or amount word right before a number, not used
             * as a verb ("to claim"). It keeps out a number without the trunk 0
             * ("the amount 30123456"), but one dialed with it is a line.
             */
            private val looseReferenceWord =
                Regex(
                    "(?iu)(?<!\\bto[ \\t])\\b(?:$LOOSE_REFERENCE_WORDS)\\b[ \\t.:#-]*" +
                        "(?:(?:is|ist|lautet|est|es|de|del|of|von|du)\\b\\.?[ \\t.:#-]*){0,2}$",
                )

            /** Five or more digits in a row, give or take the usual gaps: another number, not a time. */
            private val otherNumber = Regex("\\d(?:[ \\t.,()-]?\\d){4,}")

            /** Opening hours: "9.00-5.30", "8.30". */
            private val timeShape = Regex("\\d{1,2}[.:]\\d{2}(?:-\\d{1,2}[.:]\\d{2})?")
            private const val CALLBACK_CONTEXT_CHARS = 48

            fun updateSpamDomains(domains: Collection<String>) {
                shared.updateSpamDomains(domains)
            }

            fun hasSpamDomains(): Boolean = shared.hasSpamDomains()

            fun isVerificationMessage(body: String): Boolean = shared.isVerificationMessage(body)

            /**
             * Phone numbers a flagged text asks the reader to call, in E.164. A
             * number written with "+" and its country code counts anywhere. A
             * ten-digit North American number counts on a phone in that plan
             * ([nanpHome]); elsewhere only with +1 in front, since 1 and ten
             * digits is also how a Chinese mobile number looks. Off North
             * America, [nationalToE164] reads a number written the way people
             * write one where the phone lives, as E.164 when it's a valid line
             * there. Such a bare number counts only right after a word asking
             * to be called, and never after a reference, an account or a price.
             */
            fun extractCallbackNumbers(
                body: String,
                nanpHome: Boolean,
                nationalToE164: ((String) -> String?)? = null,
            ): List<String> {
                val text = plainNumberText(body.take(MAX_CALLBACK_SCAN_CHARS))
                val found = LinkedHashSet<String>()
                val taken = mutableListOf<IntRange>()

                fun IntRange.overlapsTaken() = taken.any { it.first <= last && first <= it.last }
                internationalNumber.findAll(text).forEach { match ->
                    val digits = match.value.filter { it in '0'..'9' }
                    if (!digits.startsWith("1") && digits.length in INTERNATIONAL_DIGITS) {
                        found += "+$digits"
                        taken += match.range
                    }
                }
                nanpNumber.findAll(text).forEach { match ->
                    val groups = match.groupValues
                    // "+49 301 234 5678" holds a NANP-shaped run that isn't one.
                    if (!match.range.overlapsTaken() && (nanpHome || groups[1].startsWith("+"))) {
                        found += "+1" + groups.drop(2).joinToString("")
                        taken += match.range
                    }
                }
                if (nationalToE164 != null && !nanpHome) {
                    nationalNumber.findAll(text).forEach { match ->
                        if (match.range.overlapsTaken() || dateShape.matches(match.value)) return@forEach
                        if (!asksToCall(text, match.range.first, taken)) return@forEach
                        nationalToE164(match.value)?.takeIf { it.startsWith("+") }?.let {
                            found += it
                            taken += match.range
                        }
                    }
                }
                return found.take(MAX_CALLBACK_NUMBERS)
            }

            /**
             * Whether [text] asks to call the bare number at [start]: a call word
             * close in front, and between the two no reference word, no price
             * and no other number that isn't one of the [lines] already read.
             * "Call about order 2079460018" is an order, "Call 020 7946 0018,
             * amount 30123456" an amount, "about your order, ring 020 7946 0018"
             * and "call to confirm your booking 020 7946 0018" lines.
             */
            private fun asksToCall(
                text: String,
                start: Int,
                lines: List<IntRange>,
            ): Boolean {
                val window = maxOf(0, start - CALLBACK_CONTEXT_CHARS)
                val call = callWord.findAll(text.substring(window, start)).lastOrNull() ?: return false
                val from = window + call.range.last + 1
                val between = text.substring(from, start)
                val another =
                    otherNumber.findAll(between).any { run ->
                        val first = from + run.range.first
                        val last = from + run.range.last
                        !dateShape.matches(run.value) && !timeShape.matches(run.value) && lines.none { it.first <= last && first <= it.last }
                    }
                val trunkLed = text.startsWith("0", start) || text.startsWith("(0", start)
                return !another &&
                    !currencyBefore.containsMatchIn(between) &&
                    !referenceWord.containsMatchIn(between) &&
                    (trunkLed || !looseReferenceWord.containsMatchIn(between))
            }

            /** Fullwidth digits, typographic dashes and odd spaces as plain ones, without a trunk "(0)". */
            private fun plainNumberText(text: String): String {
                val plain =
                    buildString(text.length) {
                        text.forEach { c ->
                            append(
                                when (c) {
                                    in '\uFF10'..'\uFF19' -> '0' + (c - '\uFF10')
                                    '\uFF0B' -> '+'
                                    '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015', '\u2212' -> '-'
                                    '\u00A0', '\u2007', '\u2009', '\u202F' -> ' '
                                    else -> c
                                },
                            )
                        }
                    }
                return trunkZero.replace(plain, "$1 ")
            }

            fun isKnownSpamDomainUrl(url: String): Boolean = shared.isKnownSpamDomainUrl(url)

            internal fun isLookalikeHost(host: String): Boolean = shared.isLookalikeHost(host)

            internal fun normalizeDomainCandidate(rawDomain: String): String? {
                val normalized =
                    rawDomain
                        .trim()
                        .trimEnd('.', ',', '!', '?', ';', ':', ')', ']', '}')
                        .lowercase()
                        .substringAfter("://")
                        .removePrefix("www.")
                        .substringBefore('/')
                        .substringBefore('?')
                        .substringBefore('#')
                        .substringBefore(':')
                        .trim('.')
                val labels = normalized.split(".")
                val isValid =
                    listOf(
                        normalized.length in MIN_REPORT_DOMAIN_LENGTH..MAX_REPORT_DOMAIN_LENGTH,
                        "." in normalized,
                        normalized.all(::isReportDomainChar),
                        labels.all(::isValidReportDomainLabel),
                    ).all { it }
                return normalized.takeIf { isValid }
            }

            fun analyze(
                body: String,
                firstContact: Boolean = false,
            ): SmsAnalysisResult = shared.analyze(body, firstContact)

            fun extractReportableIndicators(body: String): SmsReportIndicators = shared.extractReportableIndicators(body)

            private fun isReportDomainChar(char: Char): Boolean =
                when {
                    char in 'a'..'z' -> true
                    char in '0'..'9' -> true
                    char == '-' -> true
                    char == '.' -> true
                    else -> false
                }

            private fun isValidReportDomainLabel(label: String): Boolean =
                label.isNotBlank() &&
                    label.length <= MAX_REPORT_DOMAIN_LABEL_LENGTH &&
                    !label.startsWith("-") &&
                    !label.endsWith("-")
        }
    }
