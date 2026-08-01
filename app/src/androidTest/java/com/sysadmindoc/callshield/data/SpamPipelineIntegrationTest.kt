package com.sysadmindoc.callshield.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.data.local.SpamDao
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.data.model.HashWildcardRule
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.model.SpamPrefix
import com.sysadmindoc.callshield.data.model.WhitelistEntry
import com.sysadmindoc.callshield.data.model.WildcardRule
import com.sysadmindoc.callshield.domain.model.CallerIdentity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpamPipelineIntegrationTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: SpamDao
    private lateinit var repo: SpamRepository

    @Before
    fun setUp() =
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            db =
                Room
                    .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            dao = db.spamDao()
            repo = SpamRepository(context, db)
            resetHotPathSettings()
        }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun manualWhitelistOverridesDatabaseAndFailedStirSignal() =
        runBlocking {
            val number = "+12125550101"
            dao.insertNumber(
                SpamNumber(
                    number = number,
                    type = "robocall",
                    description = "Known spam seed",
                    source = "github",
                ),
            )
            dao.insertWhitelistEntry(
                WhitelistEntry(
                    number = number,
                    description = "Doctor",
                    isEmergency = true,
                ),
            )

            @Suppress("DEPRECATION")
            val result =
                repo.isSpam(
                    number = number,
                    callerIdentity =
                        CallerIdentity(android.telecom.Connection.VERIFICATION_STATUS_FAILED),
                )

            assertFalse(result.isSpam)
            assertEquals("emergency_contact", result.matchSource)
        }

    @Test
    fun failedStirSignalBeatsDatabaseMatch() =
        runBlocking {
            val number = "+12125550102"
            dao.insertNumber(
                SpamNumber(
                    number = number,
                    type = "robocall",
                    description = "Known spam seed",
                    source = "github",
                ),
            )

            @Suppress("DEPRECATION")
            val result =
                repo.isSpam(
                    number = number,
                    callerIdentity =
                        CallerIdentity(android.telecom.Connection.VERIFICATION_STATUS_FAILED),
                )

            assertTrue(result.isSpam)
            assertEquals("stir_shaken_failed", result.matchSource)
        }

    @Test
    fun userBlocklistBeatsTrustedStirSignal() =
        runBlocking {
            val number = "+12125550103"
            dao.insertNumber(
                SpamNumber(
                    number = number,
                    type = "scam",
                    description = "Blocked by user",
                    source = "github",
                    isUserBlocked = true,
                ),
            )

            val result =
                repo.isSpam(
                    number = number,
                    callerIdentity = CallerIdentity(verificationStatus = 1),
                )

            assertTrue(result.isSpam)
            assertEquals("user_blocklist", result.matchSource)
        }

    @Test
    fun nationalAndE164FormsShareOneInjectedRegionalIdentity() =
        runBlocking {
            val canonicalizer =
                PhoneIdentityCanonicalizer("GB") { number, _ ->
                    if (number == "02079460018") "+442079460018" else null
                }
            val regionalRepo =
                SpamRepository(
                    context = context,
                    database = db,
                    phoneIdentityCanonicalizer = canonicalizer,
                )
            regionalRepo.blockNumber("020 7946 0018", type = "test")

            val result = regionalRepo.isSpam("+44 20 7946 0018")

            assertTrue(result.isSpam)
            assertEquals("user_blocklist", result.matchSource)
            assertEquals(1, dao.getSpamCount())
        }

    @Test
    fun explicitWildcardRulesBeatDownloadedPrefixesAndHashWildcardRules() =
        runBlocking {
            val number = "+15085550123"
            dao.insertPrefixes(
                listOf(
                    SpamPrefix(
                        prefix = "+1508555",
                        type = "campaign",
                        description = "Hot campaign prefix",
                    ),
                ),
            )
            dao.insertWildcardRule(WildcardRule(pattern = "+1508555*", description = "Wildcard rule"))
            dao.insertHashWildcardRule(HashWildcardRule(pattern = "+1508555####", description = "Hash rule"))

            val result = repo.isSpam(number)

            assertTrue(result.isSpam)
            assertEquals("wildcard", result.matchSource)
        }

    @Test
    fun wildcardRulesBeatHashWildcardRules() =
        runBlocking {
            val number = "+15085550124"
            dao.insertWildcardRule(WildcardRule(pattern = "+1508555*", description = "Wildcard rule"))
            dao.insertHashWildcardRule(HashWildcardRule(pattern = "+1508555####", description = "Hash rule"))

            val result = repo.isSpam(number)

            assertTrue(result.isSpam)
            assertEquals("wildcard", result.matchSource)
        }

    @Test
    fun hashWildcardRulesBlockWhenHigherPriorityRulesPass() =
        runBlocking {
            val number = "+15085550125"
            dao.insertHashWildcardRule(HashWildcardRule(pattern = "+1508555####", description = "Hash rule"))

            val result = repo.isSpam(number)

            assertTrue(result.isSpam)
            assertEquals("hash_wildcard", result.matchSource)
        }

    @Test
    fun frequencyEscalationRunsAfterExplicitRuleTiers() =
        runBlocking {
            repo.setFreqEscalation(true)
            val number = "+14155550123"
            repeat(3) {
                dao.insertBlockedCall(
                    BlockedCall(
                        number = number,
                        timestamp = System.currentTimeMillis() - (it + 1) * 1_000L,
                        matchReason = "missed_call",
                    ),
                )
            }

            val result = repo.isSpam(number)

            assertTrue(result.isSpam)
            assertEquals("frequency", result.matchSource)
        }

    @Test
    fun temporaryAllowBeatsDownloadedDatabaseButNotPermanentUserBlock() =
        runBlocking {
            val number = "+12125550126"
            dao.insertNumber(
                SpamNumber(
                    number = number,
                    type = "robocall",
                    description = "Downloaded spam",
                    source = "github",
                ),
            )

            repo.temporaryAllowNumber(
                number = number,
                expiresAt = System.currentTimeMillis() + 60_000L,
                description = "One-off recovery",
            )

            val allowed = repo.isSpam(number)
            assertFalse(allowed.isSpam)
            assertEquals("temporary_allow", allowed.matchSource)

            repo.blockNumber(number, "scam", "Permanent user block")

            val blocked = repo.isSpam(number)
            assertTrue(blocked.isSpam)
            assertEquals("user_blocklist", blocked.matchSource)
        }

    @Test
    fun temporaryBlockExpiresWithoutManualCleanup() =
        runBlocking {
            val number = "+14155550127"
            val now = System.currentTimeMillis()

            repo.temporaryBlockNumber(
                number = number,
                expiresAt = now + 60_000L,
                type = "spam",
                description = "One-off block",
            )

            val active = repo.isSpam(number)
            assertTrue(active.isSpam)
            assertEquals("temporary_block", active.matchSource)

            repo.temporaryBlockNumber(
                number = "+14155550128",
                expiresAt = now - 1L,
                type = "spam",
                description = "Expired block",
            )

            val expired = repo.isSpam("+14155550128")
            assertFalse(expired.isSpam)
        }

    @Test
    fun expiredTemporaryAllowFallsBackToDownloadedDatabase() =
        runBlocking {
            val number = "+12125550129"
            dao.insertNumber(
                SpamNumber(
                    number = number,
                    type = "robocall",
                    description = "Downloaded spam",
                    source = "github",
                ),
            )

            repo.temporaryAllowNumber(
                number = number,
                expiresAt = System.currentTimeMillis() - 1L,
                description = "Expired allow",
            )

            val result = repo.isSpam(number)
            assertTrue(result.isSpam)
            assertEquals("database", result.matchSource)
        }

    private suspend fun resetHotPathSettings() {
        repo.setContactWhitelist(false)
        repo.setStirShaken(true)
        repo.setStirTrustedAllow(true)
        repo.setTimeBlock(false)
        repo.setFreqEscalation(false)
        repo.setHeuristics(false)
        repo.setMlScorer(false)
        repo.setPushAlert(false)
        repo.setSmsContent(true)
    }
}
