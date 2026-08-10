package com.sysadmindoc.callshield.data

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
) {
    fun format(): String =
        buildString {
            appendLine("SMS evaluation corpus ${SmsEvaluationCorpus.manifest.version}")
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
