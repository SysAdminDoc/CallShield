package com.sysadmindoc.callshield.ui

import android.app.Application
import android.content.Intent
import android.telecom.DisconnectCause
import android.telecom.TelecomManager
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sysadmindoc.callshield.ui.theme.CallShieldTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #43 asked for a way to call a number back once the call ends or drops. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "en-rUS")
class PostCallScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `call back is offered on the post-call screen and only fires its own action`() {
        val tapped = mutableListOf<String>()
        composeRule.setContent {
            CallShieldTheme {
                PostCallScreen(
                    details = PostCallDetails("+12125550123", TelecomManager.DURATION_SHORT, DisconnectCause.REMOTE),
                    onMarkSpam = { tapped += "spam" },
                    onCallBack = { tapped += "call back" },
                    onAddContact = { tapped += "contact" },
                    onDismiss = { tapped += "dismiss" },
                )
            }
        }

        composeRule.onNodeWithText("Call back").performClick()

        composeRule.runOnIdle { assertEquals(listOf("call back"), tapped) }
    }

    @Test
    fun `call back opens the dialer with the caller's number instead of placing the call`() {
        val intent = PostCallIntents.callBack("+12125550123")

        assertEquals(Intent.ACTION_DIAL, intent.action)
        assertEquals("tel", intent.data?.scheme)
        assertEquals("+12125550123", intent.data?.schemeSpecificPart)
    }
}
