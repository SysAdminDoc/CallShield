package com.sysadmindoc.callshield.service

import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sysadmindoc.callshield.CallShieldApp
import com.sysadmindoc.callshield.data.RestoreSentinel
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.checker.CheckerDependencies
import com.sysadmindoc.callshield.data.readOnlyCampaignDetector
import com.sysadmindoc.callshield.data.repository.SpamRepositoryAdapter
import com.sysadmindoc.callshield.domain.usecase.CheckSpamUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

/**
 * Records what an incoming call pays when it starts the process: the time from
 * process start until unlocked initialization finishes, plus a first verdict on
 * a fresh repository with cold caches. The restore check and the cache load
 * each have to stay under 500 ms, a tenth of Telecom's five-second budget,
 * except on the one start after an install, update or restore that looks in
 * Room for a journal, which only has to leave [RestoreSentinel]'s proof behind.
 *
 * The first part comes from [CallShieldApp.startupTimings], so the numbers are
 * the same whether this class runs alone or late in the suite. The run output
 * carries them in its status stream.
 */
@RunWith(AndroidJUnit4::class)
class ColdStartVerdictTest {
    @Test
    fun processStartToFirstVerdictStaysInsideTheScreeningBudget() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val timings = checkNotNull(CallShieldApp.startupTimings) { "unlocked initialization never ran" }
            val processToReady = timings.readyAtElapsedRealtime - Process.getStartElapsedRealtime()

            // A new facade leaves every cache cold, as for the first call after a start.
            // Its campaign detector reads the app's observations but records none.
            val dependencies = CheckerDependencies(campaignDetector = readOnlyCampaignDetector(context))
            val cold = SpamRepository(context, checkerDependencies = dependencies)
            val cacheLoadMillis = measureTimeMillis { cold.warmScreeningCaches() }
            val verdictRepository = SpamRepository(context, checkerDependencies = dependencies)
            val coldVerdictMillis =
                measureTimeMillis {
                    CheckSpamUseCase(SpamRepositoryAdapter(verdictRepository))(
                        number = NUMBER,
                        prefsSnapshot = verdictRepository.readPrefsSnapshot(),
                        realtimeCall = true,
                    )
                }
            val firstVerdict = processToReady + coldVerdictMillis

            val report =
                "process start to first verdict ${firstVerdict}ms (to ready ${processToReady}ms, " +
                    "startup on the main thread ${timings.initMillis}ms, restore check ${timings.restoreCheckMillis}ms" +
                    (if (timings.lookedForRestore) " with a full look" else "") + ", " +
                    "cold verdict ${coldVerdictMillis}ms, cache load ${cacheLoadMillis}ms)"
            Log.i(TAG, report)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "$TAG: $report\n") })

            if (timings.lookedForRestore) {
                // The one start after an install, an update or a restore opens Room to
                // look for a journal; it must leave proof behind so the next start doesn't.
                assertTrue(report, RestoreSentinel.isClean(context))
            } else {
                assertTrue(report, timings.restoreCheckMillis < TENTH_OF_BUDGET_MILLIS)
            }
            assertTrue(report, cacheLoadMillis < TENTH_OF_BUDGET_MILLIS)
            assertTrue(report, firstVerdict < TELECOM_BUDGET_MILLIS)
        }

    private companion object {
        const val TAG = "ColdStartVerdict"
        const val NUMBER = "+12125550199"
        const val TELECOM_BUDGET_MILLIS = 5_000L
        const val TENTH_OF_BUDGET_MILLIS = 500L
    }
}
