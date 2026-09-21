package com.sysadmindoc.callshield.data

import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.domain.model.CallerIdentity
import com.sysadmindoc.callshield.domain.model.SpamCheckResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The whole call pipeline, for a caller the carrier verified (PASSED) whose
 * number is in the downloaded database. The trust allow ranks above the
 * database to protect the real owners of numbers spoofed in old complaint
 * data, so it must win against stale rows and lose against current ones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StirShakenDatabaseEvidencePipelineTest {
    private val fixture = IsolatedRepositoryFixture(ApplicationProvider.getApplicationContext())
    private val today = LocalDate.now(ZoneOffset.UTC)

    @After
    fun tearDown() = fixture.close()

    @Test
    fun `a verified call from a number reported this year is still blocked`() {
        databaseRow("+12125550140", lastSeen = today.minusDays(30))

        val result = callFrom("+12125550140", verificationStatus = PASSED)

        assertTrue(result.isSpam)
        assertEquals("database", result.matchSource)
    }

    @Test
    fun `a verified call from a number last reported years ago rings through`() {
        databaseRow("+12125550141", lastSeen = today.minusDays(900))

        val result = callFrom("+12125550141", verificationStatus = PASSED)

        assertFalse(result.isSpam)
        assertEquals("stir_shaken_trusted", result.matchSource)
    }

    @Test
    fun `the same stale row still blocks a call the carrier did not verify`() {
        databaseRow("+12125550142", lastSeen = today.minusDays(900))

        val result = callFrom("+12125550142", verificationStatus = NOT_VERIFIED)

        assertTrue(result.isSpam)
        assertEquals("database", result.matchSource)
    }

    @Test
    fun `an explicit user block still beats a verified call`() {
        databaseRow("+12125550143", lastSeen = today.minusDays(900), isUserBlocked = true)

        val result = callFrom("+12125550143", verificationStatus = PASSED)

        assertTrue(result.isSpam)
        assertEquals("user_blocklist", result.matchSource)
    }

    private fun databaseRow(
        number: String,
        lastSeen: LocalDate,
        isUserBlocked: Boolean = false,
    ) = runBlocking {
        fixture.dao.insertNumber(
            SpamNumber(
                number = number,
                type = "robocall",
                reports = 3,
                lastSeen = lastSeen.toString(),
                source = "github",
                isUserBlocked = isUserBlocked,
            ),
        )
    }

    private fun callFrom(
        number: String,
        verificationStatus: Int,
    ): SpamCheckResult =
        runBlocking {
            fixture.repository.isSpam(number, callerIdentity = CallerIdentity(verificationStatus = verificationStatus))
        }

    private companion object {
        const val NOT_VERIFIED = 0
        const val PASSED = 1
    }
}
