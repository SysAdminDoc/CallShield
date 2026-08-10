package com.sysadmindoc.callshield.service

import android.content.Context
import android.net.Uri
import android.os.UserManager
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.CallCategory
import com.sysadmindoc.callshield.data.CategoryCallAction
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.OutgoingRiskPolicy
import com.sysadmindoc.callshield.data.OutgoingRiskWarning
import com.sysadmindoc.callshield.data.SpamHeuristics
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.model.SpamNumber
import com.sysadmindoc.callshield.data.repository.SpamRepositoryAdapter
import com.sysadmindoc.callshield.domain.model.CallerIdentity
import com.sysadmindoc.callshield.domain.model.SpamCheckResult
import com.sysadmindoc.callshield.domain.repository.SpamCheckRepository
import com.sysadmindoc.callshield.domain.usecase.CheckSpamUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowCallScreeningService
import org.robolectric.shadows.ShadowUserManager
import org.robolectric.util.ReflectionHelpers
import javax.inject.Provider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallShieldScreeningServiceRobolectricTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var fixture: IsolatedRepositoryFixture
    private lateinit var repository: SpamRepository
    private lateinit var service: CallShieldScreeningService
    private lateinit var shadowService: ShadowCallScreeningService
    private val outgoingWarnings = mutableListOf<OutgoingRiskWarning>()

    @Before
    fun setUp() {
        // Robolectric keeps the shadow user manager across tests; start every
        // ordinary screening case unlocked so direct-boot state from a prior
        // test cannot divert this class into the device-encrypted mirror.
        Shadow.extract<ShadowUserManager>(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        fixture = IsolatedRepositoryFixture(context)
        repository = fixture.repository
        runBlocking {
            repository.setBlockCalls(true)
            repository.setBlockUnknown(false)
            repository.setSilentVoicemail(false)
            repository.setAutoMuteLowConfidence(false)
            repository.setContactWhitelist(false)
            repository.setContactsOnly(false)
            repository.setOutgoingRiskWarning(false)
            repository.setStirShaken(false)
            repository.setNeighborSpoof(false)
            repository.setRegionBlock(false)
            repository.setHeuristics(false)
            repository.setMlScorer(false)
            repository.setAggressiveMode(false)
            repository.setTimeBlock(false)
            repository.setFreqEscalation(false)
        }

        service = Robolectric.setupService(CallShieldScreeningService::class.java)
        service.repo = repository
        service.checkSpam = CheckSpamUseCase(SpamRepositoryAdapter(repository))
        service.spamHeuristics = SpamHeuristics.shared
        service.applicationScope = scope
        outgoingWarnings.clear()
        OutgoingRiskPolicy.resetForTests()
        service.outgoingWarningLauncher = { _, warning -> outgoingWarnings += warning }
        shadowService = Shadow.extract(service)
    }

    @After
    fun tearDown() {
        Shadow.extract<ShadowUserManager>(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        DirectBootScreeningStore.clearForTest(context)
        scope.cancel()
        fixture.close()
    }

    @Test
    fun `direct boot rejects an explicitly blocked mirrored number without credential storage`() {
        val number = "+12125550179"
        runBlocking {
            DirectBootScreeningStore.write(
                context = context,
                blockedNumbers = listOf(SpamNumber(number = number, type = "test", isUserBlocked = true)),
                blockCallsEnabled = true,
                blockUnknownEnabled = false,
                silentVoicemailEnabled = false,
            )
        }
        Shadow.extract<ShadowUserManager>(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)

        service.onScreenCall(callDetails(number))

        val response = awaitResponse()
        assertTrue(response.disallowCall)
        assertTrue(response.rejectCall)
        assertFalse(response.silenceCall)
    }

    @Test
    fun `direct boot fails open when no mirror has been initialized`() {
        DirectBootScreeningStore.clearForTest(context)
        Shadow.extract<ShadowUserManager>(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)

        service.onScreenCall(callDetails("+12125550178"))

        val response = awaitResponse()
        assertFalse(response.disallowCall)
        assertFalse(response.rejectCall)
        assertFalse(response.silenceCall)
    }

    @Test
    fun `onScreenCall allows when call blocking is disabled`() {
        runBlocking { repository.setBlockCalls(false) }

        service.onScreenCall(callDetails("+12125550181"))

        val response = awaitResponse()
        assertFalse(response.disallowCall)
        assertFalse(response.rejectCall)
        assertFalse(response.silenceCall)
    }

    @Test
    fun `onScreenCall fails open when lazy repository creation throws`() {
        val failingService =
            Robolectric.setupService(CallShieldScreeningService::class.java).also {
                it.applicationScope = scope
                it.repoProvider =
                    object : Provider<SpamRepository> {
                        override fun get(): SpamRepository = error("repository initialization failed")
                    }
            }
        shadowService = Shadow.extract(failingService)

        failingService.onScreenCall(callDetails("+12125550180"))

        val response = awaitResponse()
        assertFalse(response.disallowCall)
        assertFalse(response.rejectCall)
        assertFalse(response.silenceCall)
    }

    @Test
    fun `onScreenCall fails open when database startup is locked`() {
        val lockedService =
            Robolectric.setupService(CallShieldScreeningService::class.java).also {
                it.applicationScope = scope
                it.repoProvider =
                    object : Provider<SpamRepository> {
                        override fun get(): SpamRepository = error("database is locked")
                    }
            }
        shadowService = Shadow.extract(lockedService)

        lockedService.onScreenCall(callDetails("+12125550180"))

        val response = awaitResponse()
        assertFalse(response.disallowCall)
        assertFalse(response.rejectCall)
        assertFalse(response.silenceCall)
    }

    @Test
    fun `contact fast path allows without invoking the checker`() {
        var checkerCalled = false
        runBlocking { repository.setContactWhitelist(true) }
        service.contactLookup = { _, _, _ -> true }
        service.checkSpam =
            CheckSpamUseCase(
                object : SpamCheckRepository {
                    override suspend fun checkSpam(
                        number: String,
                        smsBody: String?,
                        realtimeCall: Boolean,
                        prefsSnapshot: androidx.datastore.preferences.core.Preferences?,
                        callerIdentity: CallerIdentity?,
                    ): SpamCheckResult {
                        checkerCalled = true
                        return SpamCheckResult(isSpam = true, matchSource = "test")
                    }

                    override suspend fun checkSpamSms(
                        number: String,
                        body: String,
                        realtimeCall: Boolean,
                        prefsSnapshot: androidx.datastore.preferences.core.Preferences?,
                    ): SpamCheckResult = SpamCheckResult(isSpam = false)
                },
            )

        service.onScreenCall(callDetails("+12125550182"))

        val response = awaitResponse()
        assertFalse(response.disallowCall)
        assertFalse(response.rejectCall)
        assertFalse(response.silenceCall)
        assertFalse(checkerCalled)
    }

    @Test
    fun `cancellation during screening still receives one explicit allow`() {
        service.checkSpam =
            CheckSpamUseCase(
                object : SpamCheckRepository {
                    override suspend fun checkSpam(
                        number: String,
                        smsBody: String?,
                        realtimeCall: Boolean,
                        prefsSnapshot: androidx.datastore.preferences.core.Preferences?,
                        callerIdentity: CallerIdentity?,
                    ): SpamCheckResult = throw CancellationException("screening cancelled")

                    override suspend fun checkSpamSms(
                        number: String,
                        body: String,
                        realtimeCall: Boolean,
                        prefsSnapshot: androidx.datastore.preferences.core.Preferences?,
                    ): SpamCheckResult = SpamCheckResult(isSpam = false)
                },
            )

        service.onScreenCall(callDetails("+12125550183"))

        val response = awaitResponse()
        assertFalse(response.disallowCall)
        assertFalse(response.rejectCall)
        assertFalse(response.silenceCall)
    }

    @Test
    fun `checker that exceeds the budget fails open before telecom timeout`() {
        service.checkSpam =
            CheckSpamUseCase(
                object : SpamCheckRepository {
                    override suspend fun checkSpam(
                        number: String,
                        smsBody: String?,
                        realtimeCall: Boolean,
                        prefsSnapshot: androidx.datastore.preferences.core.Preferences?,
                        callerIdentity: CallerIdentity?,
                    ): SpamCheckResult {
                        delay(10_000L)
                        return SpamCheckResult(isSpam = true, matchSource = "too_slow")
                    }

                    override suspend fun checkSpamSms(
                        number: String,
                        body: String,
                        realtimeCall: Boolean,
                        prefsSnapshot: androidx.datastore.preferences.core.Preferences?,
                    ): SpamCheckResult = SpamCheckResult(isSpam = false)
                },
            )
        val startedAt = System.nanoTime()

        service.onScreenCall(callDetails("+12125550184"))

        val response = awaitResponse()
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L
        assertTrue("response took ${elapsedMillis}ms", elapsedMillis < 5_000L)
        assertFalse(response.disallowCall)
        assertFalse(response.rejectCall)
        assertFalse(response.silenceCall)
    }

    @Test
    fun `onScreenCall silences an explicitly blocked number in voicemail mode`() {
        val number = "+12125550182"
        runBlocking {
            repository.blockNumber(number, type = "test")
            repository.setSilentVoicemail(true)
        }

        service.onScreenCall(callDetails(number))

        val response = awaitResponse()
        assertTrue(response.silenceCall)
        assertFalse(response.disallowCall)
        assertFalse(response.rejectCall)
        awaitScopeIdle()
    }

    @Test
    fun `onScreenCall rejects an explicitly blocked number by default`() {
        val number = "+12125550183"
        runBlocking { repository.blockNumber(number, type = "test") }

        service.onScreenCall(callDetails(number))

        val response = awaitResponse()
        assertTrue(response.disallowCall)
        assertTrue(response.rejectCall)
        assertFalse(response.silenceCall)
        awaitScopeIdle()
    }

    @Test
    fun `category allow lets a categorized database hit ring`() {
        val number = "+12125550190"
        runBlocking {
            fixture.dao.insertNumber(SpamNumber(number = number, type = "scam"))
            repository.setCategoryCallAction(CallCategory.Scam, CategoryCallAction.ALLOW)
        }

        service.onScreenCall(callDetails(number))

        val response = awaitResponse()
        assertFalse(response.disallowCall)
        assertFalse(response.rejectCall)
        assertFalse(response.silenceCall)
    }

    @Test
    fun `category voicemail silences a categorized database hit`() {
        val number = "+12125550191"
        runBlocking {
            fixture.dao.insertNumber(SpamNumber(number = number, type = "telemarketer"))
            repository.setCategoryCallAction(CallCategory.Telemarketer, CategoryCallAction.SILENCE)
        }

        service.onScreenCall(callDetails(number))

        val response = awaitResponse()
        assertTrue(response.silenceCall)
        assertFalse(response.disallowCall)
        assertFalse(response.rejectCall)
        awaitScopeIdle()
    }

    @Test
    fun `category block overrides global silent voicemail`() {
        val number = "+12125550192"
        runBlocking {
            fixture.dao.insertNumber(SpamNumber(number = number, type = "political"))
            repository.setCategoryCallAction(CallCategory.Political, CategoryCallAction.BLOCK)
            repository.setSilentVoicemail(true)
        }

        service.onScreenCall(callDetails(number))

        val response = awaitResponse()
        assertTrue(response.disallowCall)
        assertTrue(response.rejectCall)
        assertFalse(response.silenceCall)
        awaitScopeIdle()
    }

    @Test
    fun `category allow never overrides an explicit personal block`() {
        val number = "+12125550193"
        runBlocking {
            repository.blockNumber(number, type = "scam")
            repository.setCategoryCallAction(CallCategory.Scam, CategoryCallAction.ALLOW)
        }

        service.onScreenCall(callDetails(number))

        val response = awaitResponse()
        assertTrue(response.disallowCall)
        assertTrue(response.rejectCall)
        assertFalse(response.silenceCall)
        awaitScopeIdle()
    }

    @Test
    fun `category block never overrides a manual emergency whitelist`() {
        val number = "+12125550194"
        runBlocking {
            fixture.dao.insertNumber(SpamNumber(number = number, type = "robocall"))
            repository.addToWhitelist(number, description = "Family", isEmergency = true)
            repository.setCategoryCallAction(CallCategory.Robocall, CategoryCallAction.BLOCK)
        }

        service.onScreenCall(callDetails(number))

        val response = awaitResponse()
        assertFalse(response.disallowCall)
        assertFalse(response.rejectCall)
        assertFalse(response.silenceCall)
    }

    @Test
    fun `onScreenCall treats malformed nonempty caller IDs as unknown`() {
        runBlocking { repository.setBlockUnknown(true) }

        service.onScreenCall(callDetails("PRIVATE"))

        val response = awaitResponse()
        assertTrue(response.disallowCall)
        assertTrue(response.rejectCall)
        assertFalse(response.silenceCall)
        awaitScopeIdle()
    }

    @Test
    fun `onScreenCall applies the unknown-caller policy when no number is present`() {
        runBlocking { repository.setBlockUnknown(true) }

        service.onScreenCall(callDetails(""))

        val response = awaitResponse()
        assertTrue(response.disallowCall)
        assertTrue(response.rejectCall)
        assertFalse(response.silenceCall)
    }

    @Test
    fun `onScreenCall ignores outgoing calls without responding`() {
        val number = "+12125550184"
        runBlocking { repository.blockNumber(number, type = "test") }

        service.onScreenCall(callDetails(number, Call.Details.DIRECTION_OUTGOING))

        awaitScopeIdle()
        assertTrue(shadowService.lastRespondToCallInput.isEmpty)
        assertTrue(outgoingWarnings.isEmpty())
        assertTrue(
            runBlocking {
                fixture.dao
                    .getBlockedCalls()
                    .first()
                    .isEmpty()
            },
        )
    }

    @Test
    fun `outgoing warning shows once for an exact known risk without call side effects`() {
        val number = "+12125550185"
        runBlocking {
            fixture.dao.insertNumber(
                SpamNumber(
                    number = number,
                    type = "scam",
                    reports = 12,
                    description = "Impersonation scam",
                ),
            )
            repository.setOutgoingRiskWarning(true)
        }

        service.onScreenCall(callDetails(number, Call.Details.DIRECTION_OUTGOING))
        awaitScopeIdle()
        service.onScreenCall(callDetails(number, Call.Details.DIRECTION_OUTGOING))
        awaitScopeIdle()

        assertTrue(shadowService.lastRespondToCallInput.isEmpty)
        assertEquals(
            listOf(OutgoingRiskWarning(number, "Impersonation scam", 90)),
            outgoingWarnings,
        )
        assertTrue(
            runBlocking {
                fixture.dao
                    .getBlockedCalls()
                    .first()
                    .isEmpty()
            },
        )
    }

    @Test
    fun `onScreenCall fails open with an explicit allow for unknown direction`() {
        val number = "+12125550188"
        runBlocking { repository.blockNumber(number, type = "test") }

        service.onScreenCall(callDetails(number, Call.Details.DIRECTION_UNKNOWN))

        // Unknown-direction calls must get an explicit non-blocking response;
        // staying silent would make Android hold the call until its timeout.
        val response = awaitResponse()
        assertFalse(response.disallowCall)
        assertFalse(response.rejectCall)
        assertFalse(response.silenceCall)
        awaitScopeIdle()
    }

    @Test
    fun `outgoing warning suppressed for a whitelisted known risk`() {
        val number = "+12125550189"
        runBlocking {
            fixture.dao.insertNumber(
                SpamNumber(
                    number = number,
                    type = "scam",
                    reports = 12,
                    description = "Impersonation scam",
                ),
            )
            repository.addToWhitelist(number, "Verified business")
            repository.setOutgoingRiskWarning(true)
        }

        service.onScreenCall(callDetails(number, Call.Details.DIRECTION_OUTGOING))
        awaitScopeIdle()

        assertTrue(shadowService.lastRespondToCallInput.isEmpty)
        assertTrue(outgoingWarnings.isEmpty())
    }

    @Test
    fun `outgoing warning ignores an unknown number`() {
        runBlocking { repository.setOutgoingRiskWarning(true) }

        service.onScreenCall(callDetails("+12125550186", Call.Details.DIRECTION_OUTGOING))
        awaitScopeIdle()

        assertTrue(shadowService.lastRespondToCallInput.isEmpty)
        assertTrue(outgoingWarnings.isEmpty())
        assertTrue(
            runBlocking {
                fixture.dao
                    .getBlockedCalls()
                    .first()
                    .isEmpty()
            },
        )
    }

    @Test
    fun `outgoing warning intent is explicitly warning only`() {
        val warning = OutgoingRiskWarning("+12125550187", "Known scam", 75)

        val intent = CallerIdOverlayService.outgoingRiskIntent(context, warning)

        assertEquals("+12125550187", intent.getStringExtra("number"))
        assertEquals("Known scam", intent.getStringExtra("reason"))
        assertEquals(75, intent.getIntExtra("confidence", 0))
        assertTrue(intent.getBooleanExtra(CallerIdOverlayService.EXTRA_OUTGOING_RISK_WARNING, false))
    }

    private fun callDetails(
        number: String,
        direction: Int = Call.Details.DIRECTION_INCOMING,
    ): Call.Details =
        ReflectionHelpers.callConstructor(Call.Details::class.java).also { details ->
            ReflectionHelpers.setField(details, "mHandle", Uri.parse("tel:$number"))
            ReflectionHelpers.setField(details, "mCreationTimeMillis", 1_753_094_800_000L)
            ReflectionHelpers.setField(details, "mCallDirection", direction)
        }

    private fun awaitResponse(): CallScreeningService.CallResponse =
        runBlocking {
            withTimeout(5_000L) {
                while (shadowService.lastRespondToCallInput.isEmpty) delay(10L)
                shadowService.lastRespondToCallInput.get().callResponse
            }
        }

    private fun awaitScopeIdle() {
        runBlocking {
            withTimeout(5_000L) {
                val rootJob = scope.coroutineContext[Job] ?: return@withTimeout
                while (rootJob.children.any()) delay(10L)
            }
        }
    }
}
