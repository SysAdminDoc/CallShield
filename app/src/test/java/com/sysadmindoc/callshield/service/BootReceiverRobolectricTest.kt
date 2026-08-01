package com.sysadmindoc.callshield.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootReceiverRobolectricTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `boot and package replacement reassert protection while unrelated broadcasts do not`() {
        var reassertions = 0
        var lockedBootPreparations = 0
        val receiver =
            BootReceiver().apply {
                protectionReassertion = { receivedContext ->
                    assertSame(context, receivedContext)
                    reassertions++
                }
                lockedBootPreparation = { receivedContext ->
                    assertSame(context, receivedContext)
                    lockedBootPreparations++
                }
            }

        receiver.onReceive(context, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        receiver.onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))

        assertEquals(2, reassertions)
        assertEquals(1, lockedBootPreparations)
    }
}
