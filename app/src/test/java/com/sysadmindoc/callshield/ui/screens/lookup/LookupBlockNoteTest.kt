package com.sysadmindoc.callshield.ui.screens.lookup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.domain.model.SpamCheckResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LookupBlockNoteTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fixture: IsolatedRepositoryFixture
    private val number = "+12125550142"

    @Before
    fun setUp() {
        fixture = IsolatedRepositoryFixture(context)
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    /** What Lookup's Block button does with a result. */
    private suspend fun blockFromLookup(result: SpamCheckResult) {
        fixture.repository.blockNumber(number, result.type.ifEmpty { "spam" }, lookupBlockNote(result))
    }

    @Test
    fun `making a temporary block permanent saves no stand-in note`() =
        runBlocking {
            fixture.repository.temporaryBlockNumber(number, System.currentTimeMillis() + 3_600_000L)
            val result = fixture.repository.isSpam(number)
            // Lookup shows the label in place of a note the block doesn't have.
            assertEquals("temporary_block", result.matchSource)
            assertEquals(context.getString(R.string.block_reason_temporary_block), result.description)

            blockFromLookup(result)

            val row = requireNotNull(fixture.dao.findByNumber(number))
            assertEquals("", row.description)
            assertNull(row.expiresAt)
        }

    @Test
    fun `a block's own note is kept`() =
        runBlocking {
            fixture.repository.blockNumber(number, "spam", "landlord's old line")

            blockFromLookup(fixture.repository.isSpam(number))

            assertEquals("landlord's old line", fixture.dao.findByNumber(number)?.description)
        }

    @Test
    fun `a match describes the number, an allowed result does not`() {
        assertEquals(
            "IRS impersonation",
            lookupBlockNote(SpamCheckResult(isSpam = true, matchSource = "database", type = "scam", description = "IRS impersonation")),
        )
        assertEquals("", lookupBlockNote(SpamCheckResult(isSpam = false, matchSource = "manual_whitelist", description = "pharmacy")))
        assertEquals("", lookupBlockNote(SpamCheckResult(isSpam = false, matchSource = "regulatory_allow", description = "India 1600 series")))
    }
}
