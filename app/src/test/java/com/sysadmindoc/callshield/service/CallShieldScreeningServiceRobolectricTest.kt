package com.sysadmindoc.callshield.service

import android.content.Context
import android.net.Uri
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.SpamHeuristics
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.repository.SpamRepositoryAdapter
import com.sysadmindoc.callshield.domain.usecase.CheckSpamUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
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
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallShieldScreeningServiceRobolectricTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = SpamRepository.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var service: CallShieldScreeningService
    private lateinit var shadowService: ShadowCallScreeningService

    @Before
    fun setUp() {
        runBlocking {
            repository.setBlockCalls(true)
            repository.setBlockUnknown(false)
            repository.setSilentVoicemail(false)
            repository.setAutoMuteLowConfidence(false)
            repository.setContactWhitelist(false)
            repository.setContactsOnly(false)
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
        shadowService = Shadow.extract(service)
    }

    @After
    fun tearDown() {
        scope.cancel()
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
    fun `onScreenCall ignores outgoing calls without responding`() {
        val number = "+12125550184"
        runBlocking { repository.blockNumber(number, type = "test") }

        service.onScreenCall(callDetails(number, Call.Details.DIRECTION_OUTGOING))

        awaitScopeIdle()
        assertTrue(shadowService.lastRespondToCallInput.isEmpty)
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
