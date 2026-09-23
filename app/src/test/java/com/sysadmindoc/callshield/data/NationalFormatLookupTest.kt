package com.sysadmindoc.callshield.data

import android.content.Context
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.SpamPrefix
import com.sysadmindoc.callshield.data.model.WhitelistEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A caller ID in national form that libphonenumber rejects (here a fictional
 * Turks and Caicos exchange) keeps no "+" after canonicalization, while the
 * spam database and prefix list are keyed by E.164.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NationalFormatLookupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fixture: IsolatedRepositoryFixture

    @Before
    fun setUp() {
        shadowOf(context.getSystemService(TelephonyManager::class.java)).setSimCountryIso("us")
        fixture = IsolatedRepositoryFixture(context)
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `the scenario really leaves the number without a plus`() {
        // Without this the other cases would pass for the wrong reason.
        assertEquals("6495550123", fixture.repository.normalizeNumber("649-555-0123"))
        assertEquals("16495550123", fixture.repository.normalizeNumber("1 649 555 0123"))
        assertEquals(listOf("6495550123", "+16495550123"), fixture.repository.lookupForms("6495550123"))
    }

    @Test
    fun `a national-format number matches its E164 database row`() =
        runBlocking {
            fixture.dao.insertNumbers(listOf(SpamNumber(number = "+16495550123", type = "robocall", reports = 9, source = "github")))

            assertEquals("database", fixture.repository.isSpam("6495550123").matchSource)
            assertEquals("database", fixture.repository.isSpam("16495550123").matchSource)
        }

    @Test
    fun `a national-format number matches the prefix list`() =
        runBlocking {
            fixture.dao.insertPrefixes(listOf(SpamPrefix(prefix = "+1649", type = "wangiri_scam", description = "Turks and Caicos")))

            assertEquals("prefix", fixture.repository.isSpam("6495550199").matchSource)
        }

    @Test
    fun `a block saved in E164 form applies to the national form`() =
        runBlocking {
            fixture.dao.insertNumbers(
                listOf(SpamNumber(number = "+16495550177", type = "spam", source = "user", isUserBlocked = true)),
            )

            assertEquals("user_blocklist", fixture.repository.isSpam("6495550177").matchSource)
        }

    @Test
    fun `an allow saved in E164 form still wins over a database match`() =
        runBlocking {
            // Matching only the block side in both forms would newly block a
            // number the user had allowed.
            fixture.dao.insertNumbers(listOf(SpamNumber(number = "+16495550123", type = "robocall", reports = 9, source = "github")))
            fixture.dao.insertWhitelistEntry(WhitelistEntry(number = "+16495550123"))

            val result = fixture.repository.isSpam("6495550123")

            assertFalse(result.isSpam)
            assertEquals("manual_whitelist", result.matchSource)
        }
}
