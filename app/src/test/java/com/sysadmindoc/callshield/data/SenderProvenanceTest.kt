package com.sysadmindoc.callshield.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SenderProvenanceTest {
    @Test
    fun `resolver models registered allocated unverified and unassigned states`() {
        val resolver =
            SenderProvenanceResolver(
                listOf(
                    SenderProvenanceDataset(
                        regionIso = "AU",
                        sourceId = "acma-test",
                        sourceVersion = "fixture-v1",
                        senderIdRegistryAvailable = true,
                        registeredSenderIds = setOf("Acme Alert"),
                    ),
                    SenderProvenanceDataset(
                        regionIso = "GB",
                        sourceId = "ofcom-test",
                        sourceVersion = "fixture-v1",
                        numberingPlanAvailable = true,
                        allocatedPrefixes = setOf("+447"),
                        unassignedPrefixes = setOf("+440"),
                    ),
                ),
            )

        val registered = resolver.resolve("acme-alert", "au")
        val allocated = resolver.resolve("+447700900123", "GB")
        val unverified = resolver.resolve("+449123456789", "GB")
        val unassigned = resolver.resolve("+440123456789", "GB")

        assertEquals(SenderProvenanceState.REGISTERED, registered.state)
        assertEquals(SenderProvenanceKind.SENDER_ID, registered.kind)
        assertEquals(SenderProvenanceState.ALLOCATED, allocated.state)
        assertEquals("+447", allocated.matchedPrefix)
        assertEquals(SenderProvenanceState.UNVERIFIED, unverified.state)
        assertEquals(SenderProvenanceState.UNASSIGNED, unassigned.state)
        assertEquals("+440", unassigned.matchedPrefix)
    }

    @Test
    fun `regional rules fail open when locale or source data is unavailable`() {
        val resolver = SenderProvenanceResolver()

        val noRegion = resolver.resolve("+447700900123", null)
        val unsupportedRegion = resolver.resolve("+4915123456789", "DE")
        val nationalNumber = resolver.resolve("020 7946 0018", "GB")

        assertEquals(SenderProvenanceState.UNAVAILABLE, noRegion.state)
        assertEquals(SenderProvenanceState.UNAVAILABLE, unsupportedRegion.state)
        assertEquals(SenderProvenanceState.UNAVAILABLE, nationalNumber.state)
        assertEquals(0, noRegion.riskPoints)
        assertTrue(SenderProvenanceCatalog.sources.any { it.sourceId == "bundesnetzagentur_number_blocks" })
    }

    @Test
    fun `bundled plans provide conservative Australian and French evidence`() {
        val resolver = SenderProvenanceResolver()

        val australianId = resolver.resolve("UnknownBank", "AU")
        val frenchAllocated = resolver.resolve("+33142567890", "FR")
        val frenchUnassigned = resolver.resolve("+33012345678", "FR")

        assertEquals(SenderProvenanceState.UNVERIFIED, australianId.state)
        assertEquals("acma_sender_id_register", australianId.sourceId)
        assertEquals(SenderProvenanceState.ALLOCATED, frenchAllocated.state)
        assertEquals(SenderProvenanceState.UNASSIGNED, frenchUnassigned.state)
    }

    @Test
    fun `provenance is advisory and cannot meet an SMS block threshold alone`() {
        val analyzer = SpamHeuristics()
        val unverified =
            SenderProvenance(
                state = SenderProvenanceState.UNVERIFIED,
                kind = SenderProvenanceKind.SENDER_ID,
                regionIso = "AU",
                sourceId = "acma-test",
                sourceVersion = "fixture-v1",
            )
        val unassigned =
            unverified.copy(
                state = SenderProvenanceState.UNASSIGNED,
                kind = SenderProvenanceKind.PHONE_NUMBER,
                matchedPrefix = "+440",
            )

        val unverifiedResult =
            analyzer.analyze(
                context = ApplicationProvider.getApplicationContext(),
                number = "+447700900123",
                enableNeighborSpoof = false,
                senderProvenance = unverified,
            )
        val unassignedResult =
            analyzer.analyze(
                context = ApplicationProvider.getApplicationContext(),
                number = "+440123456789",
                enableNeighborSpoof = false,
                senderProvenance = unassigned,
            )

        assertTrue(unverifiedResult.reasons.contains("sender_provenance_unverified"))
        assertTrue(unassignedResult.reasons.contains("sender_provenance_unassigned"))
        assertFalse(unverifiedResult.isSpam)
        assertFalse(unassignedResult.isSpam)
        assertTrue(unverified.riskPoints < 25)
        assertTrue(unassigned.riskPoints < 25)
    }

    @Test
    fun `source versions remain attached to every resolved state`() {
        val dataset =
            SenderProvenanceDataset(
                regionIso = "FR",
                sourceId = "arcep-test",
                sourceVersion = "fixture-2026-01",
                numberingPlanAvailable = true,
                allocatedPrefixes = setOf("+331"),
            )
        val result = SenderProvenanceResolver(listOf(dataset)).resolve("+33142567890", "FR")

        assertEquals("arcep-test", result.sourceId)
        assertEquals("fixture-2026-01", result.sourceVersion)
    }
}
