package com.sysadmindoc.callshield.data

import java.util.Locale

/** Evidence state for an SMS sender within a specific numbering jurisdiction. */
enum class SenderProvenanceState {
    REGISTERED,
    ALLOCATED,
    UNVERIFIED,
    UNASSIGNED,
    UNAVAILABLE,
}

enum class SenderProvenanceKind {
    SENDER_ID,
    PHONE_NUMBER,
}

/**
 * Local-only provenance evidence. It contains a source prefix or sender-ID
 * state, never a copy of the message body.
 */
data class SenderProvenance(
    val state: SenderProvenanceState,
    val kind: SenderProvenanceKind,
    val regionIso: String?,
    val sourceId: String,
    val sourceVersion: String,
    val matchedPrefix: String? = null,
) {
    /** Small advisory contribution; no provenance state blocks by itself. */
    val riskPoints: Int
        get() =
            when (state) {
                SenderProvenanceState.UNVERIFIED -> 8

                SenderProvenanceState.UNASSIGNED -> 14

                SenderProvenanceState.REGISTERED,
                SenderProvenanceState.ALLOCATED,
                SenderProvenanceState.UNAVAILABLE,
                -> 0
            }

    /** Stable reason token used by the local heuristic explanation. */
    val reasonToken: String
        get() =
            when (state) {
                SenderProvenanceState.UNVERIFIED -> "sender_provenance_unverified"
                SenderProvenanceState.UNASSIGNED -> "sender_provenance_unassigned"
                SenderProvenanceState.REGISTERED -> "sender_provenance_registered"
                SenderProvenanceState.ALLOCATED -> "sender_provenance_allocated"
                SenderProvenanceState.UNAVAILABLE -> ""
            }
}

/** A bounded, region-owned snapshot that can be replaced by a future feed. */
data class SenderProvenanceDataset(
    val regionIso: String,
    val sourceId: String,
    val sourceVersion: String,
    val senderIdRegistryAvailable: Boolean = false,
    val registeredSenderIds: Set<String> = emptySet(),
    val numberingPlanAvailable: Boolean = false,
    val allocatedPrefixes: Set<String> = emptySet(),
    val unassignedPrefixes: Set<String> = emptySet(),
) {
    init {
        require(regionIso.length == 2 && regionIso.all { it in 'A'..'Z' })
        require(sourceId.isNotBlank())
        require(sourceVersion.isNotBlank())
    }
}

/** Public source metadata for the provenance snapshot and diagnostics. */
data class SenderProvenanceSource(
    val sourceId: String,
    val regionIso: String,
    val sourceVersion: String,
    val scope: String,
    val sourceUrl: String,
    val licenseNote: String,
)

/**
 * Pure resolver. Regional rules are applied only when both a region and a
 * source snapshot are available; no locale or country is guessed from digits.
 */
class SenderProvenanceResolver(
    datasets: Collection<SenderProvenanceDataset> = SenderProvenanceCatalog.datasets,
) {
    private val datasetsByRegion = datasets.associateBy { it.regionIso }

    fun resolve(
        rawSender: String,
        regionIso: String?,
    ): SenderProvenance {
        val region = normalizeRegion(regionIso)
        val dataset = region?.let(datasetsByRegion::get)
        if (dataset == null) return unavailable(senderKind(rawSender), region)

        return if (rawSender.any(Char::isLetter)) {
            resolveSenderId(rawSender, dataset)
        } else {
            resolveNumber(rawSender, dataset)
        }
    }

    private fun resolveSenderId(
        rawSender: String,
        dataset: SenderProvenanceDataset,
    ): SenderProvenance {
        if (!dataset.senderIdRegistryAvailable) {
            return unavailable(SenderProvenanceKind.SENDER_ID, dataset.regionIso)
        }
        val senderId = normalizeSenderId(rawSender)
        if (senderId.isBlank()) return unavailable(SenderProvenanceKind.SENDER_ID, dataset.regionIso)
        val registered = normalizeSenderIds(dataset.registeredSenderIds)
        return SenderProvenance(
            state = if (senderId in registered) SenderProvenanceState.REGISTERED else SenderProvenanceState.UNVERIFIED,
            kind = SenderProvenanceKind.SENDER_ID,
            regionIso = dataset.regionIso,
            sourceId = dataset.sourceId,
            sourceVersion = dataset.sourceVersion,
        )
    }

    private fun resolveNumber(
        rawSender: String,
        dataset: SenderProvenanceDataset,
    ): SenderProvenance {
        if (!dataset.numberingPlanAvailable) {
            return unavailable(SenderProvenanceKind.PHONE_NUMBER, dataset.regionIso)
        }
        val number = normalizeNumber(rawSender)
        if (!number.startsWith("+")) {
            return unavailable(SenderProvenanceKind.PHONE_NUMBER, dataset.regionIso)
        }
        val unassignedPrefix = longestPrefix(number, dataset.unassignedPrefixes)
        val allocatedPrefix = longestPrefix(number, dataset.allocatedPrefixes)
        val bestMatch =
            listOfNotNull(
                unassignedPrefix?.let { it to SenderProvenanceState.UNASSIGNED },
                allocatedPrefix?.let { it to SenderProvenanceState.ALLOCATED },
            ).maxByOrNull { it.first.length }
        return SenderProvenance(
            state = bestMatch?.second ?: SenderProvenanceState.UNVERIFIED,
            kind = SenderProvenanceKind.PHONE_NUMBER,
            regionIso = dataset.regionIso,
            sourceId = dataset.sourceId,
            sourceVersion = dataset.sourceVersion,
            matchedPrefix = bestMatch?.first,
        )
    }

    private fun unavailable(
        kind: SenderProvenanceKind,
        regionIso: String?,
    ): SenderProvenance =
        SenderProvenance(
            state = SenderProvenanceState.UNAVAILABLE,
            kind = kind,
            regionIso = regionIso,
            sourceId = "none",
            sourceVersion = "none",
        )

    private fun longestPrefix(
        number: String,
        prefixes: Set<String>,
    ): String? =
        prefixes
            .asSequence()
            .map(::normalizeNumberPrefix)
            .filter { it.isNotBlank() && number.startsWith(it) }
            .maxByOrNull(String::length)

    companion object {
        private const val MAX_SENDER_ID_LENGTH = 32

        internal fun normalizeRegion(value: String?): String? =
            value
                ?.trim()
                ?.uppercase(Locale.ROOT)
                ?.takeIf { it.length == 2 && it.all { character -> character in 'A'..'Z' } }

        internal fun normalizeSenderId(value: String): String {
            val normalized =
                value
                    .trim()
                    .uppercase(Locale.ROOT)
                    .filter(Char::isLetterOrDigit)
            return normalized.takeIf { it.length <= MAX_SENDER_ID_LENGTH }.orEmpty()
        }

        internal fun normalizeSenderIds(values: Iterable<String>): Set<String> = values.map(::normalizeSenderId).filter(String::isNotBlank).toSet()

        internal fun normalizeNumber(value: String): String {
            val digits = value.filter { it in '0'..'9' }
            return if (value.trimStart().startsWith("+")) "+$digits" else digits
        }

        internal fun normalizeNumberPrefix(value: String): String = normalizeNumber(value)

        private fun senderKind(rawSender: String): SenderProvenanceKind = if (rawSender.any(Char::isLetter)) SenderProvenanceKind.SENDER_ID else SenderProvenanceKind.PHONE_NUMBER
    }
}

/**
 * Source registry for the bundled, conservative numbering-plan snapshot.
 * Prefixes mean "allocated in the regulator's plan", not "this exact number
 * belongs to a trusted organisation". A later feed can replace each dataset.
 */
object SenderProvenanceCatalog {
    val sources: List<SenderProvenanceSource> =
        listOf(
            SenderProvenanceSource(
                sourceId = "acma_sender_id_register",
                regionIso = "AU",
                sourceVersion = "acma-sender-id-2026-01",
                scope = "SMS sender IDs",
                sourceUrl = "https://www.acma.gov.au/sms-sender-id-register",
                licenseNote = "Australian regulator guidance; registration status is jurisdiction-specific",
            ),
            SenderProvenanceSource(
                sourceId = "ofcom_numbering_plan",
                regionIso = "GB",
                sourceVersion = "ofcom-numbering-2026-07",
                scope = "UK number ranges and valid CLI",
                sourceUrl = "https://www.ofcom.org.uk/phones-and-broadband/phone-numbers/numbering",
                licenseNote = "Ofcom numbering data; refresh before treating a range as current",
            ),
            SenderProvenanceSource(
                sourceId = "arcep_numbering_plan",
                regionIso = "FR",
                sourceVersion = "arcep-numbering-2026-01",
                scope = "French numbering plan and verified prefixes",
                sourceUrl = "https://www.arcep.fr/la-regulation/grands-dossiers-thematiques-transverses/la-numerotation.html",
                licenseNote = "ARCEP numbering plan; allocation is not identity proof",
            ),
            SenderProvenanceSource(
                sourceId = "bundesnetzagentur_number_blocks",
                regionIso = "DE",
                sourceVersion = "bnetza-number-blocks-2026-01",
                scope = "German assigned number blocks",
                sourceUrl = "https://www.bundesnetzagentur.de/DE/Fachthemen/Telekommunikation/Nummerierung/ONRufnr/Verzeichnisse/start.html",
                licenseNote = "Bundesnetzagentur data is refreshable and not bundled as a full assignment archive",
            ),
            SenderProvenanceSource(
                sourceId = "scamwatch_sms_patterns",
                regionIso = "AU",
                sourceVersion = "scamwatch-sms-2026-01",
                scope = "SMS scam warning context",
                sourceUrl = "https://www.scamwatch.gov.au/types-of-scams/text-or-sms-scams",
                licenseNote = "Consumer guidance; pattern context is advisory, not sender registration proof",
            ),
        )

    /**
     * Only structural regulator-plan data is bundled. The AU register is
     * intentionally conservative: an unknown branded sender is unverified;
     * no organisation is asserted registered without a local register row.
     */
    val datasets: List<SenderProvenanceDataset> =
        listOf(
            SenderProvenanceDataset(
                regionIso = "AU",
                sourceId = "acma_sender_id_register",
                sourceVersion = "acma-sender-id-2026-01",
                senderIdRegistryAvailable = true,
            ),
            SenderProvenanceDataset(
                regionIso = "GB",
                sourceId = "ofcom_numbering_plan",
                sourceVersion = "ofcom-numbering-2026-07",
                numberingPlanAvailable = true,
                allocatedPrefixes = setOf("+441", "+442", "+443", "+447", "+448", "+449"),
                unassignedPrefixes = setOf("+440"),
            ),
            SenderProvenanceDataset(
                regionIso = "FR",
                sourceId = "arcep_numbering_plan",
                sourceVersion = "arcep-numbering-2026-01",
                numberingPlanAvailable = true,
                allocatedPrefixes = setOf("+331", "+332", "+333", "+334", "+335", "+336", "+337", "+338", "+339"),
                unassignedPrefixes = setOf("+330"),
            ),
            SenderProvenanceDataset(
                regionIso = "DE",
                sourceId = "bundesnetzagentur_number_blocks",
                sourceVersion = "bnetza-number-blocks-2026-01",
            ),
        )
}
