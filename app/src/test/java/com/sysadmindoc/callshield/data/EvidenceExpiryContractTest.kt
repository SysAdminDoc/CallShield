package com.sysadmindoc.callshield.data

import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.sysadmindoc.callshield.data.model.SourceEvidenceJson
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.SpamNumberJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/** Keeps the app's row expiry and the pipeline's liveness check on one rule and one fixture. */
class EvidenceExpiryContractTest {
    @Test
    fun `every shared case expires the synced row when the pipeline says it does`() {
        val cases = loadFixture().cases
        assertTrue(cases.size >= 5)
        cases.forEach { case ->
            assertEquals(case.name, case.rowExpiresAtEpochMs, SourceEvidenceCodec.rowExpiry(case.row.evidence))
            val stored = synced(case.row)
            assertEquals(case.name, case.rowExpiresAtEpochMs, stored.evidenceExpiresAt)
            case.checks.forEach { check ->
                assertEquals(case.name, check.atEpochMs, Instant.parse(check.at).toEpochMilli())
                assertEquals("${case.name} at ${check.at}", check.blocks, stored.activeDecision(check.atEpochMs) != null)
            }
        }
    }

    @Test
    fun `FCC evidence ending 2027-09 no longer cuts short database evidence ending 2036-09`() {
        val case = loadFixture().cases.first { it.name.startsWith("FCC evidence ending 2027-09") }
        val stored = synced(case.row)
        assertNotNull(stored.activeDecision(Instant.parse("2028-03-01T00:00:00Z").toEpochMilli()))
    }

    @Test
    fun `stored rows from the earliest-expiry rule are corrected once and unreadable ones are left alone`() {
        val fcc = evidence("fcc_complaints", FCC_2027)
        val database = evidence("github_database", DATABASE_2036)
        val corrections =
            evidenceExpiryCorrections(
                listOf(
                    // What 1.10.0 stored for a corroborated row.
                    Triple(1L, SourceEvidenceCodec.encode(listOf(fcc, database)), FCC_2027),
                    Triple(2L, SourceEvidenceCodec.encode(listOf(database)), DATABASE_2036),
                    Triple(3L, SourceEvidenceCodec.encode(listOf(fcc, database.copy(expiresAtEpochMs = null))), FCC_2027),
                    Triple(4L, "not json", FCC_2027),
                    Triple(5L, "[]", FCC_2027),
                ),
            )
        assertEquals(mapOf(1L to DATABASE_2036, 3L to null), corrections)
    }

    private fun synced(row: SpamNumberJson): SpamNumber =
        sanitizeDatabaseNumbers(
            databaseNumbers = listOf(row),
            normalizeNumber = { it },
            preservedUserBlockedNumbers = emptyMap(),
        ).single()

    private fun evidence(
        sourceId: String,
        expiresAt: Long?,
    ) = SourceEvidenceJson(
        sourceId = sourceId,
        evidenceType = "unverified_complaint",
        license = "test",
        attribution = "test",
        expiresAtEpochMs = expiresAt,
    )

    private fun loadFixture(): ExpiryFixture {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        return checkNotNull(moshi.adapter(ExpiryFixture::class.java).fromJson(locate("scripts/evidence_expiry_fixtures.json").readText()))
    }

    private fun locate(relativePath: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("Could not find $relativePath walking up from ${File("").absolutePath}")
    }

    internal data class ExpiryFixture(
        val cases: List<ExpiryCase>,
    )

    internal data class ExpiryCase(
        val name: String,
        val row: SpamNumberJson,
        @param:Json(name = "row_expires_at_epoch_ms") val rowExpiresAtEpochMs: Long?,
        val checks: List<ExpiryCheck>,
    )

    internal data class ExpiryCheck(
        val at: String,
        @param:Json(name = "at_epoch_ms") val atEpochMs: Long,
        val blocks: Boolean,
    )

    private companion object {
        const val FCC_2027 = 1_821_916_800_000L
        const val DATABASE_2036 = 2_106_000_000_000L
    }
}
