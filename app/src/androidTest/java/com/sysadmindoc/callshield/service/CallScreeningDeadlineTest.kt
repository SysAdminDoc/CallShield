package com.sysadmindoc.callshield.service

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sysadmindoc.callshield.data.SpamHeuristics
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.repository.SpamRepositoryAdapter
import com.sysadmindoc.callshield.domain.usecase.CheckSpamUseCase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallScreeningDeadlineTest {
    @Test
    fun serviceLifecycleAndColdDecisionPipeline_finishBeforeTelecomDeadline() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val serviceIntent = Intent(context, CallShieldScreeningService::class.java)
            val startedAt = SystemClock.elapsedRealtime()

            try {
                val component = context.startService(serviceIntent)
                assertNotNull("Call screening service must be startable by its own process", component)
                assertEquals(CallShieldScreeningService::class.java.name, component?.className)

                // A new facade leaves checker/prefix/rule caches cold while using
                // the same Room/DataStore path as onScreenCall.
                val repository = SpamRepository(context)
                val checkSpam = CheckSpamUseCase(SpamRepositoryAdapter(repository))
                val number = "+12125550199"

                val result =
                    withTimeout(MAX_ACCEPTABLE_LATENCY_MILLIS) {
                        val prefs = repository.readPrefsSnapshot()
                        if ((prefs[SpamRepository.KEY_CONTACT_WHITELIST] ?: true) &&
                            SpamHeuristics.shared.isInContacts(context, number)
                        ) {
                            null
                        } else {
                            checkSpam(
                                number = number,
                                prefsSnapshot = prefs,
                                realtimeCall = true,
                            )
                        }
                    }

                val elapsed = SystemClock.elapsedRealtime() - startedAt
                Log.i(TAG, "cold screening lifecycle + verdict completed in ${elapsed}ms; result=$result")
                assertTrue(
                    "Cold screening lifecycle + verdict took ${elapsed}ms; " +
                        "Telecom allows ${TELECOM_DEADLINE_MILLIS}ms",
                    elapsed < MAX_ACCEPTABLE_LATENCY_MILLIS,
                )
            } finally {
                context.stopService(serviceIntent)
            }
        }

    private companion object {
        const val TAG = "ScreeningDeadlineTest"
        const val TELECOM_DEADLINE_MILLIS = 5_000L
        const val MAX_ACCEPTABLE_LATENCY_MILLIS = 4_000L
    }
}
