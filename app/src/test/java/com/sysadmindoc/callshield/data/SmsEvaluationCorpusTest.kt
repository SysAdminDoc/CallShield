package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.SmsCorpusCategory.HARD_NEGATIVE
import com.sysadmindoc.callshield.data.SmsCorpusCategory.LEGITIMATE
import com.sysadmindoc.callshield.data.SmsCorpusCategory.SCAM
import com.sysadmindoc.callshield.data.SmsCorpusCategory.SPAM
import com.sysadmindoc.callshield.data.SmsCorpusLinkKind.BENIGN_DOMAIN
import com.sysadmindoc.callshield.data.SmsCorpusLinkKind.NONE
import com.sysadmindoc.callshield.data.SmsCorpusLinkKind.SHORTENER
import com.sysadmindoc.callshield.data.SmsCorpusLinkKind.SUSPICIOUS_TLD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

private enum class SmsCorpusCategory {
    SCAM,
    SPAM,
    LEGITIMATE,
    HARD_NEGATIVE,
}

private enum class SmsCorpusSenderForm {
    SHORT_CODE,
    PHONE_NUMBER,
    ALPHANUMERIC,
}

private enum class SmsCorpusLinkKind {
    NONE,
    BENIGN_DOMAIN,
    SUSPICIOUS_TLD,
    SHORTENER,
}

private data class SmsCorpusExample(
    val id: String,
    val languageTag: String,
    val category: SmsCorpusCategory,
    val senderRegion: String,
    val senderForm: SmsCorpusSenderForm,
    val linkKind: SmsCorpusLinkKind,
    val body: String,
    val license: String,
    val provenance: String,
) {
    val expectedSpam: Boolean
        get() = category == SmsCorpusCategory.SCAM || category == SmsCorpusCategory.SPAM
}

private data class SmsCorpusMetrics(
    val examples: Int,
    val actualPositives: Int,
    val predictedPositives: Int,
    val truePositives: Int,
    val falsePositives: Int,
    val trueNegatives: Int,
    val falseNegatives: Int,
) {
    val precision: Double?
        get() = (truePositives + falsePositives).takeIf { it > 0 }?.let { truePositives.toDouble() / it }

    val recall: Double?
        get() = (truePositives + falseNegatives).takeIf { it > 0 }?.let { truePositives.toDouble() / it }

    val falsePositiveRate: Double
        get() = falsePositives.toDouble() / (falsePositives + trueNegatives).coerceAtLeast(1)
}

private data class SmsCorpusReport(
    val byLanguage: Map<String, SmsCorpusMetrics>,
    val byCategory: Map<SmsCorpusCategory, SmsCorpusMetrics>,
    /** Ids of spam the analyzer missed and of clean messages it flagged. */
    val missed: List<String> = emptyList(),
    val falseAlarms: List<String> = emptyList(),
) {
    fun format(): String =
        buildString {
            appendLine("SMS evaluation corpus ${SmsEvaluationCorpus.manifest.version}")
            appendLine("missed=${missed.joinToString(",")}")
            appendLine("false_alarms=${falseAlarms.joinToString(",")}")
            byLanguage.forEach { (language, metrics) ->
                appendMetrics("language=$language", metrics)
            }
            byCategory.forEach { (category, metrics) ->
                appendMetrics("category=${category.name.lowercase(Locale.ROOT)}", metrics)
            }
        }

    private fun StringBuilder.appendMetrics(
        label: String,
        metrics: SmsCorpusMetrics,
    ) {
        append(label)
        append(" examples=")
        append(metrics.examples)
        append(" precision=")
        append(metrics.precision?.formatMetric() ?: "n/a")
        append(" recall=")
        append(metrics.recall?.formatMetric() ?: "n/a")
        append(" fpr=")
        appendLine(metrics.falsePositiveRate.formatMetric())
    }

    private fun Double.formatMetric(): String = String.format(Locale.ROOT, "%.4f", this)
}

private object SmsEvaluationCorpus {
    data class Manifest(
        val version: String,
        val license: String,
        val provenance: String,
        val containsPersonalData: Boolean,
    )

    val manifest =
        Manifest(
            version = "synthetic-sms-v1",
            license = "CC0-1.0",
            provenance = "CallShield-authored synthetic and redacted fixtures",
            containsPersonalData = false,
        )

    /**
     * These messages are authored fixtures, not copied from a live corpus.
     * Reserved example domains, symbolic codes, and abstract sender forms keep
     * the evaluator useful without shipping personal or provider data.
     */
    val examples =
        listOf(
            example(
                id = "en_scam_account_no_link",
                languageTag = "en",
                category = SmsCorpusCategory.SCAM,
                region = "US",
                senderForm = SmsCorpusSenderForm.ALPHANUMERIC,
                linkKind = SmsCorpusLinkKind.NONE,
                body = "Urgent: your account is suspended. Verify your identity today.",
            ),
            example(
                id = "en_spam_prize_shortener",
                languageTag = "en",
                category = SmsCorpusCategory.SPAM,
                region = "US",
                senderForm = SmsCorpusSenderForm.SHORT_CODE,
                linkKind = SmsCorpusLinkKind.SHORTENER,
                body = "Congratulations! You have won a free gift. Claim your prize at https://bit.ly/example.",
            ),
            example(
                id = "en_legitimate_otp",
                languageTag = "en",
                category = SmsCorpusCategory.LEGITIMATE,
                region = "US",
                senderForm = SmsCorpusSenderForm.SHORT_CODE,
                linkKind = SmsCorpusLinkKind.NONE,
                body = "Your verification code is [CODE]. Do not share it.",
            ),
            example(
                id = "en_hard_negative_delivery",
                languageTag = "en",
                category = SmsCorpusCategory.HARD_NEGATIVE,
                region = "US",
                senderForm = SmsCorpusSenderForm.PHONE_NUMBER,
                linkKind = SmsCorpusLinkKind.BENIGN_DOMAIN,
                body = "Your package has arrived. Track it at https://store.example.invalid/track.",
            ),
            example(
                id = "es_scam_account_no_link",
                languageTag = "es",
                category = SmsCorpusCategory.SCAM,
                region = "ES",
                senderForm = SmsCorpusSenderForm.ALPHANUMERIC,
                linkKind = SmsCorpusLinkKind.NONE,
                body = "Aviso urgente: su cuenta esta suspendida. Verifique su identidad hoy.",
            ),
            example(
                id = "es_spam_prize_tld",
                languageTag = "es",
                category = SmsCorpusCategory.SPAM,
                region = "ES",
                senderForm = SmsCorpusSenderForm.SHORT_CODE,
                linkKind = SmsCorpusLinkKind.SUSPICIOUS_TLD,
                body = "Felicidades: ha ganado un premio. Reclamelo en https://premio.example.xyz/oferta.",
            ),
            example(
                id = "es_legitimate_otp",
                languageTag = "es",
                category = SmsCorpusCategory.LEGITIMATE,
                region = "ES",
                senderForm = SmsCorpusSenderForm.SHORT_CODE,
                linkKind = SmsCorpusLinkKind.NONE,
                body = "Su codigo de verificacion es [CODE]. No lo comparta.",
            ),
            example(
                id = "es_hard_negative_delivery",
                languageTag = "es",
                category = SmsCorpusCategory.HARD_NEGATIVE,
                region = "ES",
                senderForm = SmsCorpusSenderForm.PHONE_NUMBER,
                linkKind = SmsCorpusLinkKind.BENIGN_DOMAIN,
                body = "Su paquete ha llegado. Consulte el estado en https://tienda.example.invalid/envio.",
            ),
            example(
                id = "fr_scam_account_no_link",
                languageTag = "fr",
                category = SmsCorpusCategory.SCAM,
                region = "FR",
                senderForm = SmsCorpusSenderForm.ALPHANUMERIC,
                linkKind = SmsCorpusLinkKind.NONE,
                body = "Alerte urgente: votre compte est suspendu. Verifiez votre identite aujourd'hui.",
            ),
            example(
                id = "fr_spam_prize_tld",
                languageTag = "fr",
                category = SmsCorpusCategory.SPAM,
                region = "FR",
                senderForm = SmsCorpusSenderForm.SHORT_CODE,
                linkKind = SmsCorpusLinkKind.SUSPICIOUS_TLD,
                body = "Felicitation: vous avez gagne un cadeau. Reclamez-le sur https://cadeau.example.xyz/offre.",
            ),
            example(
                id = "fr_legitimate_otp",
                languageTag = "fr",
                category = SmsCorpusCategory.LEGITIMATE,
                region = "FR",
                senderForm = SmsCorpusSenderForm.SHORT_CODE,
                linkKind = SmsCorpusLinkKind.NONE,
                body = "Votre code de verification est [CODE]. Ne le partagez pas.",
            ),
            example(
                id = "fr_hard_negative_delivery",
                languageTag = "fr",
                category = SmsCorpusCategory.HARD_NEGATIVE,
                region = "FR",
                senderForm = SmsCorpusSenderForm.PHONE_NUMBER,
                linkKind = SmsCorpusLinkKind.BENIGN_DOMAIN,
                body = "Votre colis est arrive. Suivez-le sur https://boutique.example.invalid/suivi.",
            ),
            example(
                id = "de_scam_account_no_link",
                languageTag = "de",
                category = SmsCorpusCategory.SCAM,
                region = "DE",
                senderForm = SmsCorpusSenderForm.ALPHANUMERIC,
                linkKind = SmsCorpusLinkKind.NONE,
                body = "Dringend: Ihr Konto ist gesperrt. Bestatigen Sie heute Ihre Identitat.",
            ),
            example(
                id = "de_spam_prize_tld",
                languageTag = "de",
                category = SmsCorpusCategory.SPAM,
                region = "DE",
                senderForm = SmsCorpusSenderForm.SHORT_CODE,
                linkKind = SmsCorpusLinkKind.SUSPICIOUS_TLD,
                body = "Glueckwunsch: Sie haben einen Preis gewonnen. Holen Sie ihn bei https://preis.example.xyz/ab.",
            ),
            example(
                id = "de_legitimate_otp",
                languageTag = "de",
                category = SmsCorpusCategory.LEGITIMATE,
                region = "DE",
                senderForm = SmsCorpusSenderForm.SHORT_CODE,
                linkKind = SmsCorpusLinkKind.NONE,
                body = "Ihr Bestaetigungscode ist [CODE]. Bitte nicht weitergeben.",
            ),
            example(
                id = "de_hard_negative_delivery",
                languageTag = "de",
                category = SmsCorpusCategory.HARD_NEGATIVE,
                region = "DE",
                senderForm = SmsCorpusSenderForm.PHONE_NUMBER,
                linkKind = SmsCorpusLinkKind.BENIGN_DOMAIN,
                body = "Ihr Paket ist angekommen. Status unter https://laden.example.invalid/status.",
            ),
            example(
                id = "pt_scam_account_no_link",
                languageTag = "pt",
                category = SmsCorpusCategory.SCAM,
                region = "BR",
                senderForm = SmsCorpusSenderForm.ALPHANUMERIC,
                linkKind = SmsCorpusLinkKind.NONE,
                body = "Alerta urgente: sua conta foi suspensa. Confirme sua identidade hoje.",
            ),
            example(
                id = "pt_spam_prize_tld",
                languageTag = "pt",
                category = SmsCorpusCategory.SPAM,
                region = "BR",
                senderForm = SmsCorpusSenderForm.SHORT_CODE,
                linkKind = SmsCorpusLinkKind.SUSPICIOUS_TLD,
                body = "Parabens: voce ganhou um premio. Resgate em https://premio.example.xyz/oferta.",
            ),
            example(
                id = "pt_legitimate_otp",
                languageTag = "pt",
                category = SmsCorpusCategory.LEGITIMATE,
                region = "BR",
                senderForm = SmsCorpusSenderForm.SHORT_CODE,
                linkKind = SmsCorpusLinkKind.NONE,
                body = "Seu codigo de verificacao e [CODE]. Nao compartilhe.",
            ),
            example(
                id = "pt_hard_negative_delivery",
                languageTag = "pt",
                category = SmsCorpusCategory.HARD_NEGATIVE,
                region = "BR",
                senderForm = SmsCorpusSenderForm.PHONE_NUMBER,
                linkKind = SmsCorpusLinkKind.BENIGN_DOMAIN,
                body = "Seu pacote chegou. Acompanhe em https://loja.example.invalid/rastreio.",
            ),
            // ── Spanish, Portuguese and Italian: 20+ examples each ─────────────
            // Authored in the style of the scams that circulate in each market
            // (parcel fees, bank alerts, "new number" family texts, fines, tax
            // refunds, jobs, crypto) next to the ordinary texts they resemble.
            spanish(SCAM, SUSPICIOUS_TLD, "parcel_customs_fee", "Su envío está retenido en aduanas. Pague 1,99 EUR de tasas para liberarlo en https://envios-es.example.xyz/pago"),
            spanish(SCAM, SUSPICIOUS_TLD, "bank_unauthorized_access", "Hemos detectado un acceso no autorizado a su cuenta. Si no ha sido usted, confirme sus datos en https://seguridad-banco.example.xyz/acceso"),
            spanish(SCAM, NONE, "family_new_number", "Hola mamá, se me ha roto el móvil y este es mi número nuevo. Escríbeme por WhatsApp, necesito un favor urgente."),
            spanish(SCAM, SUSPICIOUS_TLD, "traffic_fine", "Tiene una multa de tráfico pendiente de pago. Evite el recargo abonándola hoy en https://multas.example.xyz/pagar"),
            spanish(SCAM, SUSPICIOUS_TLD, "tax_refund", "Agencia tributaria: tiene un reembolso de 245,60 EUR pendiente. Solicítelo antes de 48 horas en https://reembolso.example.xyz/solicitud"),
            spanish(SPAM, NONE, "job_offer", "Oferta de empleo: gane 300 EUR al día desde casa trabajando solo una hora. Escríbanos por WhatsApp para empezar."),
            spanish(SCAM, SUSPICIOUS_TLD, "crypto_investment", "Invierta 250 EUR en bitcoin hoy y reciba ganancias garantizadas cada semana. Plazas limitadas en https://inversion.example.xyz/registro"),
            spanish(SCAM, SUSPICIOUS_TLD, "power_cut", "Aviso: su suministro eléctrico será cortado hoy por una factura impagada. Regularice el pago en https://luz-pagos.example.xyz/factura"),
            spanish(SCAM, SHORTENER, "subscription_payment", "Su suscripción ha sido suspendida por un problema con el pago. Actualice su tarjeta en https://bit.ly/example"),
            spanish(SPAM, SUSPICIOUS_TLD, "preapproved_loan", "Préstamo preaprobado de hasta 5.000 EUR sin papeleo ni aval. Consígalo en 10 minutos en https://prestamo.example.xyz/solicitar"),
            spanish(SCAM, NONE, "card_blocked_call", "Su tarjeta ha sido bloqueada por seguridad. Llame hoy mismo al servicio de atención para desbloquearla."),
            spanish(LEGITIMATE, NONE, "appointment", "Recordatorio: tiene cita con su médico mañana a las 10:30 en el centro de salud. Para cancelar, llame al centro."),
            spanish(LEGITIMATE, NONE, "order_delivered", "Su pedido ha sido entregado en su buzón. Gracias por comprar con nosotros."),
            spanish(LEGITIMATE, NONE, "family_running_late", "Llego un poco tarde, empezad a cenar sin mí."),
            spanish(LEGITIMATE, NONE, "pharmacy_ready", "Su receta está lista para recoger en la farmacia a partir de las 17:00."),
            spanish(LEGITIMATE, NONE, "restaurant_booking", "Reserva confirmada para 4 personas el sábado a las 21:00. Le esperamos."),
            spanish(LEGITIMATE, NONE, "login_code", "Código para iniciar sesión: [CODE]. Caduca en 5 minutos."),
            spanish(HARD_NEGATIVE, NONE, "card_purchase_alert", "Compra de 23,40 EUR con su tarjeta terminada en 4821. Si no la reconoce, contacte con su banco desde la app."),
            spanish(HARD_NEGATIVE, NONE, "parcel_out_for_delivery", "Su paquete está pendiente de entrega. El repartidor pasará mañana entre las 9:00 y las 14:00."),
            spanish(HARD_NEGATIVE, NONE, "school_meeting", "El colegio informa: la reunión de padres es el jueves a las 18:00. Confirme su asistencia respondiendo a este mensaje."),
            spanish(HARD_NEGATIVE, NONE, "bank_safety_notice", "Su banco le recuerda que nunca le pedirá claves ni códigos por SMS."),
            spanish(HARD_NEGATIVE, BENIGN_DOMAIN, "store_order_tracking", "Su pedido ya está en camino. Puede seguirlo en https://tienda.example.invalid/pedido"),
            portuguese(SCAM, SUSPICIOUS_TLD, "parcel_customs_fee", "Sua encomenda está retida na alfândega. Pague a taxa de R$ 12,90 para liberar a entrega em https://rastreio-br.example.xyz/taxa"),
            portuguese(SCAM, SUSPICIOUS_TLD, "bank_unauthorized_access", "Detectamos um acesso suspeito na sua conta. Se não foi você, confirme seus dados em https://seguranca-banco.example.xyz/acesso"),
            portuguese(SCAM, NONE, "family_new_number", "Oi mãe, troquei de número. Salva esse aqui e me chama no WhatsApp, preciso de ajuda urgente."),
            portuguese(SCAM, SUSPICIOUS_TLD, "tax_id_irregular", "Seu CPF está irregular e será bloqueado hoje. Regularize a situação em https://cpf-regular.example.xyz/consulta"),
            portuguese(SPAM, NONE, "job_offer", "Vaga de emprego: ganhe R$ 500 por dia trabalhando de casa curtindo vídeos. Chame no WhatsApp para começar."),
            portuguese(SCAM, SHORTENER, "pix_confirmation", "Você recebeu um Pix de R$ 1.250,00 que precisa ser confirmado. Acesse https://bit.ly/example para liberar."),
            portuguese(SCAM, SUSPICIOUS_TLD, "crypto_investment", "Invista R$ 200 em criptomoedas e receba lucro garantido toda semana. Vagas limitadas em https://invest.example.xyz/cadastro"),
            portuguese(SCAM, SUSPICIOUS_TLD, "traffic_fine", "Você possui uma multa de trânsito pendente com 40% de desconto só hoje. Pague em https://multas-br.example.xyz/pagar"),
            portuguese(SCAM, NONE, "card_blocked_call", "Seu cartão foi bloqueado por segurança. Ligue para a central ainda hoje para desbloquear."),
            portuguese(SPAM, SUSPICIOUS_TLD, "preapproved_loan", "Empréstimo pré-aprovado de até R$ 5.000 sem consulta. Libere agora em https://credito.example.xyz/simular"),
            portuguese(SCAM, SUSPICIOUS_TLD, "points_expiring", "Seus pontos do cartão vencem hoje. Resgate agora seus prêmios em https://pontos.example.xyz/resgate"),
            portuguese(LEGITIMATE, NONE, "appointment", "Lembrete: sua consulta está marcada para amanhã às 14h. Para remarcar, ligue para a clínica."),
            portuguese(LEGITIMATE, NONE, "order_delivered", "Seu pedido foi entregue. Obrigado por comprar conosco!"),
            portuguese(LEGITIMATE, NONE, "family_running_late", "Chego em casa por volta das 19h, pode ir jantando."),
            portuguese(LEGITIMATE, NONE, "pharmacy_ready", "Seu medicamento já está disponível para retirada na farmácia."),
            portuguese(LEGITIMATE, NONE, "school_meeting", "A escola informa: a reunião de pais será na quinta-feira às 18h."),
            portuguese(LEGITIMATE, NONE, "login_code", "Código de acesso: [CODE]. Ele expira em 5 minutos."),
            portuguese(HARD_NEGATIVE, NONE, "birthday_greeting", "Parabéns pelo seu aniversário! Aproveite 10% de desconto na loja durante todo o mês."),
            portuguese(HARD_NEGATIVE, NONE, "insurance_premium_due", "Seguro auto: o prêmio da sua apólice vence dia 10. O boleto está disponível no aplicativo."),
            portuguese(HARD_NEGATIVE, NONE, "card_purchase_alert", "Compra aprovada de R$ 58,90 no cartão final 4821. Não reconhece? Fale com o seu banco pelo aplicativo."),
            portuguese(HARD_NEGATIVE, NONE, "parcel_out_for_delivery", "Sua entrega está pendente: o entregador tentará novamente amanhã entre 8h e 12h."),
            italian(SCAM, SUSPICIOUS_TLD, "parcel_customs_fee", "Il tuo pacco è in giacenza. Paga 1,99 EUR di spese di spedizione per riceverlo su https://spedizioni-it.example.xyz/pagamento"),
            italian(SCAM, SUSPICIOUS_TLD, "bank_unusual_access", "Abbiamo rilevato un accesso anomalo al tuo conto. Se non sei stato tu, verifica i tuoi dati su https://sicurezza-banca.example.xyz/accesso"),
            italian(SCAM, NONE, "family_new_number", "Ciao mamma, ho cambiato numero perché il telefono si è rotto. Scrivimi su WhatsApp, ho bisogno di un favore urgente."),
            italian(SCAM, SUSPICIOUS_TLD, "tax_refund", "Agenzia delle entrate: hai diritto a un rimborso di 214,50 EUR. Richiedilo entro oggi su https://rimborso.example.xyz/richiesta"),
            italian(SPAM, SHORTENER, "prize_selected", "Congratulazioni! Sei stato selezionato per vincere un nuovo smartphone. Ritira il premio su https://bit.ly/example"),
            italian(SPAM, NONE, "job_offer", "Offerta di lavoro: guadagna 300 EUR al giorno da casa con un'ora di impegno. Contattaci su WhatsApp."),
            italian(SCAM, SUSPICIOUS_TLD, "crypto_investment", "Investi 250 EUR in bitcoin oggi e ricevi rendimenti garantiti ogni settimana. Posti limitati su https://investimenti.example.xyz/iscrizione"),
            italian(SCAM, SUSPICIOUS_TLD, "traffic_fine", "Hai una multa non pagata. Evita la maggiorazione pagando oggi su https://multe.example.xyz/paga"),
            italian(SCAM, NONE, "card_blocked_call", "La tua carta è stata bloccata per motivi di sicurezza. Chiama subito il servizio clienti per sbloccarla."),
            italian(SCAM, SUSPICIOUS_TLD, "account_suspended", "Il tuo account è stato sospeso per attività sospetta. Conferma la tua identità entro 24 ore su https://verifica-account.example.xyz/login"),
            italian(SCAM, SUSPICIOUS_TLD, "power_cut", "Avviso: la fornitura di luce sarà sospesa oggi per una bolletta non pagata. Regolarizza su https://bollette.example.xyz/paga"),
            italian(SPAM, SUSPICIOUS_TLD, "preapproved_loan", "Prestito pre-approvato fino a 5.000 EUR senza garanzie. Ottienilo in 10 minuti su https://prestiti.example.xyz/richiedi"),
            italian(LEGITIMATE, NONE, "verification_code", "Il tuo codice di verifica è [CODE]. Non condividerlo con nessuno."),
            italian(LEGITIMATE, NONE, "appointment", "Promemoria: domani alle 10:30 hai un appuntamento dal dentista. Per disdire chiama lo studio."),
            italian(LEGITIMATE, NONE, "order_delivered", "Il tuo ordine è stato consegnato. Grazie per aver acquistato da noi."),
            italian(LEGITIMATE, NONE, "family_running_late", "Arrivo tra venti minuti, iniziate pure a cenare."),
            italian(LEGITIMATE, NONE, "pharmacy_ready", "La tua ricetta è pronta per il ritiro in farmacia."),
            italian(LEGITIMATE, NONE, "school_meeting", "La scuola informa: il colloquio con i genitori è giovedì alle 17:00."),
            italian(HARD_NEGATIVE, NONE, "insurance_premium_due", "Il premio della tua polizza auto scade il 15. Puoi pagarlo dall'app o in agenzia."),
            italian(HARD_NEGATIVE, BENIGN_DOMAIN, "parcel_out_for_delivery", "Il tuo pacco è in consegna oggi. Traccia la spedizione su https://negozio.example.invalid/traccia"),
            italian(HARD_NEGATIVE, NONE, "card_payment_alert", "Pagamento di 32,10 EUR con carta terminante 4821 autorizzato. Se non lo riconosci, contatta la tua banca dall'app."),
            italian(HARD_NEGATIVE, NONE, "bank_safety_notice", "La tua banca ti ricorda: non ti chiederemo mai codici o password via SMS."),
            example(
                id = "ar_scam_account_no_link",
                languageTag = "ar",
                category = SmsCorpusCategory.SCAM,
                region = "AU",
                senderForm = SmsCorpusSenderForm.ALPHANUMERIC,
                linkKind = SmsCorpusLinkKind.NONE,
                body = "تنبيه عاجل: تم تعليق حسابك. تحقق من هويتك اليوم.",
            ),
            example(
                id = "ar_spam_prize_tld",
                languageTag = "ar",
                category = SmsCorpusCategory.SPAM,
                region = "AU",
                senderForm = SmsCorpusSenderForm.SHORT_CODE,
                linkKind = SmsCorpusLinkKind.SUSPICIOUS_TLD,
                body = "مبروك: ربحت جائزة. استلمها عبر https://جائزة.example.xyz/عرض.",
            ),
            example(
                id = "ar_legitimate_otp",
                languageTag = "ar",
                category = SmsCorpusCategory.LEGITIMATE,
                region = "AU",
                senderForm = SmsCorpusSenderForm.SHORT_CODE,
                linkKind = SmsCorpusLinkKind.NONE,
                body = "رمز التحقق هو [CODE]. لا تشاركه.",
            ),
            example(
                id = "ar_hard_negative_delivery",
                languageTag = "ar",
                category = SmsCorpusCategory.HARD_NEGATIVE,
                region = "AU",
                senderForm = SmsCorpusSenderForm.PHONE_NUMBER,
                linkKind = SmsCorpusLinkKind.BENIGN_DOMAIN,
                body = "وصلت شحنتك. تابع الحالة عبر https://متجر.example.invalid/حالة.",
            ),
            example(
                id = "zh_scam_account_no_link",
                languageTag = "zh",
                category = SmsCorpusCategory.SCAM,
                region = "GB",
                senderForm = SmsCorpusSenderForm.ALPHANUMERIC,
                linkKind = SmsCorpusLinkKind.NONE,
                body = "紧急提醒：您的账户已暂停。请立即验证身份。",
            ),
            example(
                id = "zh_spam_prize_tld",
                languageTag = "zh",
                category = SmsCorpusCategory.SPAM,
                region = "GB",
                senderForm = SmsCorpusSenderForm.SHORT_CODE,
                linkKind = SmsCorpusLinkKind.SUSPICIOUS_TLD,
                body = "恭喜您赢得奖品，请通过 https://奖品.example.xyz/领取。",
            ),
            example(
                id = "zh_legitimate_otp",
                languageTag = "zh",
                category = SmsCorpusCategory.LEGITIMATE,
                region = "GB",
                senderForm = SmsCorpusSenderForm.SHORT_CODE,
                linkKind = SmsCorpusLinkKind.NONE,
                body = "您的验证码是 [CODE]，请勿分享。",
            ),
            example(
                id = "zh_hard_negative_delivery",
                languageTag = "zh",
                category = SmsCorpusCategory.HARD_NEGATIVE,
                region = "GB",
                senderForm = SmsCorpusSenderForm.PHONE_NUMBER,
                linkKind = SmsCorpusLinkKind.BENIGN_DOMAIN,
                body = "您的包裹已经安全送达。您可以在应用内查看配送状态，也可以访问 https://商店.example.invalid/状态 了解详情。",
            ),
        )

    val falsePositiveBudgetByLanguage =
        examples
            .map(SmsCorpusExample::languageTag)
            .distinct()
            .associateWith { 0.10 }

    /**
     * Recall each full-size language set must keep, measured on this corpus
     * at the evaluator threshold (2026-09-22: es and pt 12 of 13, it 11 of 12;
     * the misses are the work-from-home job offers). A floor only moves up.
     */
    val recallFloorByLanguage =
        mapOf(
            "es" to 0.92,
            "pt" to 0.92,
            "it" to 0.91,
        )

    val falsePositiveBudgetByCategory =
        mapOf(
            SmsCorpusCategory.LEGITIMATE to 0.0,
            SmsCorpusCategory.HARD_NEGATIVE to 0.10,
        )

    fun evaluate(analyzer: SmsContentAnalyzer = SmsContentAnalyzer()): SmsCorpusReport {
        val predictions =
            examples.associate { example ->
                example.id to (analyzer.analyze(example.body).score >= SCORE_THRESHOLD)
            }
        return SmsCorpusReport(
            byLanguage = examples.groupMetrics(predictions) { it.languageTag },
            byCategory = examples.groupMetrics(predictions) { it.category },
            missed = examples.filter { it.expectedSpam && !predictions.getValue(it.id) }.map { it.id },
            falseAlarms = examples.filter { !it.expectedSpam && predictions.getValue(it.id) }.map { it.id },
        )
    }

    private fun <K> List<SmsCorpusExample>.groupMetrics(
        predictions: Map<String, Boolean>,
        key: (SmsCorpusExample) -> K,
    ): Map<K, SmsCorpusMetrics> =
        groupBy(key).mapValues { (_, examples) ->
            var truePositives = 0
            var falsePositives = 0
            var trueNegatives = 0
            var falseNegatives = 0
            examples.forEach { example ->
                val predicted = predictions.getValue(example.id)
                when {
                    predicted && example.expectedSpam -> truePositives++
                    predicted -> falsePositives++
                    example.expectedSpam -> falseNegatives++
                    else -> trueNegatives++
                }
            }
            SmsCorpusMetrics(
                examples = examples.size,
                actualPositives = examples.count(SmsCorpusExample::expectedSpam),
                predictedPositives = examples.count { predictions.getValue(it.id) },
                truePositives = truePositives,
                falsePositives = falsePositives,
                trueNegatives = trueNegatives,
                falseNegatives = falseNegatives,
            )
        }

    private fun spanish(
        category: SmsCorpusCategory,
        linkKind: SmsCorpusLinkKind,
        name: String,
        body: String,
    ) = localized("es", "ES", category, linkKind, name, body)

    private fun portuguese(
        category: SmsCorpusCategory,
        linkKind: SmsCorpusLinkKind,
        name: String,
        body: String,
    ) = localized("pt", "BR", category, linkKind, name, body)

    private fun italian(
        category: SmsCorpusCategory,
        linkKind: SmsCorpusLinkKind,
        name: String,
        body: String,
    ) = localized("it", "IT", category, linkKind, name, body)

    @Suppress("LongParameterList")
    private fun localized(
        languageTag: String,
        region: String,
        category: SmsCorpusCategory,
        linkKind: SmsCorpusLinkKind,
        name: String,
        body: String,
    ) = example(
        id = "${languageTag}_${category.name.lowercase(Locale.ROOT)}_$name",
        languageTag = languageTag,
        category = category,
        region = region,
        senderForm = if (category == SCAM || category == SPAM) SmsCorpusSenderForm.PHONE_NUMBER else SmsCorpusSenderForm.ALPHANUMERIC,
        linkKind = linkKind,
        body = body,
    )

    private fun example(
        id: String,
        languageTag: String,
        category: SmsCorpusCategory,
        region: String,
        senderForm: SmsCorpusSenderForm,
        linkKind: SmsCorpusLinkKind,
        body: String,
    ): SmsCorpusExample =
        SmsCorpusExample(
            id = id,
            languageTag = languageTag,
            category = category,
            senderRegion = region,
            senderForm = senderForm,
            linkKind = linkKind,
            body = body,
            license = manifest.license,
            provenance = manifest.provenance,
        )

    private const val SCORE_THRESHOLD = 25
}

class SmsEvaluationCorpusTest {
    @Test
    fun `manifest covers multilingual licensed redacted examples`() {
        assertEquals("CC0-1.0", SmsEvaluationCorpus.manifest.license)
        assertFalse(SmsEvaluationCorpus.manifest.containsPersonalData)
        assertTrue(SmsEvaluationCorpus.examples.size >= 20)
        assertTrue(
            SmsEvaluationCorpus.examples
                .map { it.languageTag }
                .toSet()
                .size >= 5,
        )

        SmsEvaluationCorpus.examples.forEach { example ->
            assertTrue(example.id.isNotBlank())
            assertTrue(example.license.isNotBlank())
            assertTrue(example.provenance.isNotBlank())
            assertTrue(example.languageTag.matches(Regex("[a-z]{2}")))
            assertTrue(example.senderRegion.matches(Regex("[A-Z]{2}")))
            assertFalse(Regex("(?<!\\d)\\d{7,}(?!\\d)").containsMatchIn(example.body))
            assertFalse(Regex("\\+\\d{7,}").containsMatchIn(example.body))
            assertFalse(Regex("[?&][A-Za-z]+=\\S+").containsMatchIn(example.body))
            if (example.linkKind == SmsCorpusLinkKind.NONE) {
                assertFalse(example.body.contains("http", ignoreCase = true))
            } else {
                assertTrue(example.body.contains("http", ignoreCase = true))
            }
        }
    }

    @Test
    fun `Spanish Portuguese and Italian each carry at least twenty distinct examples`() {
        val counts =
            SmsEvaluationCorpus.examples
                .groupingBy { it.languageTag }
                .eachCount()
        SmsEvaluationCorpus.recallFloorByLanguage.keys.forEach { language ->
            assertTrue("$language has ${counts[language]} examples", (counts[language] ?: 0) >= 20)
        }
        val ids = SmsEvaluationCorpus.examples.map { it.id }
        assertEquals("example ids must be unique", ids.size, ids.toSet().size)
    }

    @Test
    fun `Spanish Portuguese and Italian recall stays at or above its floor`() {
        val report = SmsEvaluationCorpus.evaluate()
        SmsEvaluationCorpus.recallFloorByLanguage.forEach { (language, floor) ->
            val recall = requireNotNull(report.byLanguage.getValue(language).recall)
            assertTrue("$language recall $recall fell below its floor $floor", recall >= floor)
        }
    }

    @Test
    fun `Spanish Portuguese and Italian raise no false alarms`() {
        // Every clean es/pt/it example passes today. The 10% budgets below would
        // still let one new false alarm through per language, so these are pinned.
        val byId = SmsEvaluationCorpus.examples.associateBy { it.id }
        val flagged =
            SmsEvaluationCorpus
                .evaluate()
                .falseAlarms
                .filter { byId.getValue(it).languageTag in SmsEvaluationCorpus.recallFloorByLanguage.keys }

        assertEquals(emptyList<String>(), flagged)
    }

    @Test
    fun `evaluator reports locale and message type metrics within false positive budgets`() {
        val report = SmsEvaluationCorpus.evaluate()
        println(report.format())

        assertEquals(SmsEvaluationCorpus.falsePositiveBudgetByLanguage.keys, report.byLanguage.keys)
        SmsEvaluationCorpus.falsePositiveBudgetByLanguage.forEach { (language, budget) ->
            assertTrue(
                "$language false-positive rate exceeded budget",
                report.byLanguage.getValue(language).falsePositiveRate <= budget,
            )
        }
        SmsEvaluationCorpus.falsePositiveBudgetByCategory.forEach { (category, budget) ->
            assertTrue(
                "$category false-positive rate exceeded budget",
                report.byCategory.getValue(category).falsePositiveRate <= budget,
            )
        }
        report.byLanguage.values.forEach { metrics ->
            val precision = metrics.precision
            val recall = metrics.recall
            assertNotNull(recall)
            assertTrue(precision == null || precision in 0.0..1.0)
            assertTrue(recall == null || recall in 0.0..1.0)
        }
    }
}
