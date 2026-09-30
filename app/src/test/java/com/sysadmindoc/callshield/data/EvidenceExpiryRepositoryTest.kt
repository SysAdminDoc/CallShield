package com.sysadmindoc.callshield.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.model.SourceEvidenceJson
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.SpamPrefix
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class EvidenceExpiryRepositoryTest {
    // 2028-03-01: past the FCC evidence, well inside the database evidence.
    private var now = 1_835_481_600_000L
    private lateinit var fixture: IsolatedRepositoryFixture

    @Before
    fun setUp() {
        fixture = IsolatedRepositoryFixture(ApplicationProvider.getApplicationContext(), wallClock = { now })
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `rows stored under the earliest-expiry rule block again after the one-time fix`() =
        runBlocking {
            val evidenceJson = SourceEvidenceCodec.encode(listOf(evidence("fcc_complaints", FCC_2027), evidence("github_database", DATABASE_2036)))
            fixture.dao.insertNumbers(
                listOf(
                    SpamNumber(
                        number = NUMBER,
                        type = "robocall",
                        source = "github",
                        evidenceJson = evidenceJson,
                        evidenceExpiresAt = FCC_2027,
                    ),
                ),
            )
            fixture.dao.insertPrefixes(
                listOf(SpamPrefix(prefix = "+1312555", type = "robocall", evidenceJson = evidenceJson, evidenceExpiresAt = FCC_2027)),
            )
            fixture.repository.warmScreeningCaches()
            assertNotEquals("database", fixture.repository.isSpam(NUMBER).matchSource)
            assertNotEquals("prefix", fixture.repository.isSpam("+13125550199").matchSource)

            assertEquals(2, fixture.repository.applyLatestEvidenceExpiryRule())
            fixture.repository.warmScreeningCaches()

            assertEquals("database", fixture.repository.isSpam(NUMBER).matchSource)
            assertEquals("prefix", fixture.repository.isSpam("+13125550199").matchSource)
            assertEquals(
                DATABASE_2036,
                fixture.dao
                    .getNumbersByNumbers(listOf(NUMBER))
                    .single()
                    .evidenceExpiresAt,
            )
            // It runs once per install.
            assertEquals(0, fixture.repository.applyLatestEvidenceExpiryRule())
        }

    @Test
    fun `a hot entry blocks again once the number's database row has fully expired`() =
        runBlocking {
            fixture.dao.insertNumbers(
                listOf(
                    SpamNumber(
                        number = NUMBER,
                        type = "robocall",
                        source = "github",
                        evidenceJson = SourceEvidenceCodec.encode(listOf(evidence("fcc_complaints", FCC_2027))),
                        evidenceExpiresAt = FCC_2027,
                    ),
                ),
            )
            assertNotEquals("database", fixture.repository.isSpam(NUMBER).matchSource)

            // appliedAt is the feed's publish time, before the row expired; expiry is judged now.
            fixture.repository.replaceHotList(
                listOf(SpamNumber(number = NUMBER, type = "robocall", source = "hot_list", evidenceExpiresAt = now + DAY_MS)),
                recordTrending = false,
                appliedAt = FCC_2027 - DAY_MS,
            )

            assertEquals("database", fixture.repository.isSpam(NUMBER).matchSource)
            assertEquals(
                "hot_list",
                fixture.dao
                    .getNumbersByNumbers(listOf(NUMBER))
                    .single()
                    .source,
            )
        }

    private fun evidence(
        sourceId: String,
        expiresAt: Long,
    ) = SourceEvidenceJson(
        sourceId = sourceId,
        evidenceType = "unverified_complaint",
        license = "test",
        attribution = "test",
        expiresAtEpochMs = expiresAt,
    )

    private companion object {
        const val NUMBER = "+13152328257"
        const val FCC_2027 = 1_821_916_800_000L
        const val DATABASE_2036 = 2_106_000_000_000L
        const val DAY_MS = 86_400_000L
    }
}
