package com.sysadmindoc.callshield.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.core.app.ActivityOptionsCompat
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.ui.theme.CallShieldTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

/**
 * Android's "won't ask again" hints used to be a toast, which stops at two
 * lines on Android 12 and later, with App info opened on top of it. They're
 * a snackbar now that holds the whole hint and opens the page when tapped.
 * Native graphics, since legacy mode measures text at one pixel per character.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = "en-rUS-w360dp-h640dp")
class PermissionHintTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val restoration = StateRestorationTester(composeRule)

    // Answers every request at once with everything refused, the way Android
    // does once it has stopped showing its dialog.
    private val refusesWithoutAsking =
        object : ActivityResultRegistryOwner {
            override val activityResultRegistry =
                object : ActivityResultRegistry() {
                    override fun <I, O> onLaunch(
                        requestCode: Int,
                        contract: ActivityResultContract<I, O>,
                        input: I,
                        options: ActivityOptionsCompat?,
                    ) {
                        val answer: Any =
                            if (input is Array<*>) {
                                input.associate { it as String to false }
                            } else {
                                ActivityResult(Activity.RESULT_CANCELED, null)
                            }
                        dispatchResult(requestCode, answer)
                    }
                }
        }

    // The host holds the hints the way MainActivity does, outside the content
    // that asks, so the content can leave while a hint shows.
    private fun show(
        withHost: Boolean = true,
        content: @Composable () -> Unit,
    ) {
        restoration.setContent {
            CallShieldTheme {
                val hints = rememberPermissionHints()
                CompositionLocalProvider(
                    LocalActivityResultRegistryOwner provides refusesWithoutAsking,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale = LARGEST_FONT_SCALE),
                    LocalPermissionHints provides hints.takeIf { withHost },
                ) {
                    Box(Modifier.fillMaxSize()) {
                        content()
                        if (withHost) PermissionHintsHost(hints, Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(TIMEOUT_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitStartedActivity(): Intent {
        composeRule.waitUntil(TIMEOUT_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(composeRule.activity).peekNextStartedActivity() != null
        }
        return shadowOf(composeRule.activity).nextStartedActivity
    }

    private fun assertShownInFull(text: String) {
        val node = composeRule.onNodeWithText(text)
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertFalse(
            "hint text is cut off: ${layout.lineCount} lines, size ${layout.size}, needs ${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
                "width overflow ${layout.didOverflowWidth}, height overflow ${layout.didOverflowHeight}, constraints ${layout.layoutInput.constraints}",
            layout.hasVisualOverflow,
        )
        assertTrue("a two-line toast couldn't hold this hint", layout.lineCount > 2)
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val screen = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("hint runs off the screen: $bounds in $screen", bounds.left >= screen.left && bounds.right <= screen.right && bounds.top >= screen.top && bounds.bottom <= screen.bottom)
    }

    @Test
    fun `the App info hint shows in full and App info opens only from its action`() {
        show {
            val request = rememberPermissionRequest(R.string.permission_core_in_app_info)
            Button(onClick = { request(listOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.READ_SMS)) }) {
                Text("Ask")
            }
        }
        val hint = composeRule.activity.getString(R.string.permission_core_in_app_info)

        composeRule.onNodeWithText("Ask").performClick()
        awaitText(hint)

        assertShownInFull(hint)
        assertEquals(0, ShadowToast.shownToastCount())
        assertNull(shadowOf(composeRule.activity).peekNextStartedActivity())

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.permission_hint_app_info)).performClick()
        val opened = awaitStartedActivity()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened.action)
        assertEquals("package:${composeRule.activity.packageName}", opened.dataString)
    }

    @Test
    fun `the Default apps hint shows in full and Default apps opens only from its action`() {
        var result: RoleRequestResult? = null
        show {
            val request = rememberRoleRequest(R.string.role_call_screening_in_default_apps, roleHeld = { false }) { result = it }
            Button(onClick = { request(Intent("test.REQUEST_ROLE")) }) { Text("Ask") }
        }
        val hint = composeRule.activity.getString(R.string.role_call_screening_in_default_apps)

        composeRule.onNodeWithText("Ask").performClick()
        awaitText(hint)

        assertEquals(RoleRequestResult(held = false, defaultAppsHintShown = true), result)
        assertShownInFull(hint)
        assertEquals(0, ShadowToast.shownToastCount())
        assertNull(shadowOf(composeRule.activity).peekNextStartedActivity())

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.permission_hint_default_apps)).performClick()
        assertEquals(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, awaitStartedActivity().action)
    }

    @Test
    fun `the App info hint and its action outlast a tab change and a rotation`() {
        var tab by mutableIntStateOf(0)
        show {
            if (tab == 0) {
                val request = rememberPermissionRequest(R.string.permission_core_in_app_info)
                Button(onClick = { request(listOf(Manifest.permission.READ_CALL_LOG)) }) { Text("Ask") }
            } else {
                Text("Another tab")
            }
        }
        val hint = composeRule.activity.getString(R.string.permission_core_in_app_info)
        composeRule.onNodeWithText("Ask").performClick()
        awaitText(hint)

        tab = 1
        awaitText("Another tab")
        composeRule.onNodeWithText(hint).assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()
        awaitText(hint)
        composeRule.onNodeWithText("Another tab").assertIsDisplayed()
        assertNull(shadowOf(composeRule.activity).peekNextStartedActivity())

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.permission_hint_app_info)).performClick()
        val opened = awaitStartedActivity()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened.action)
        assertEquals("package:${composeRule.activity.packageName}", opened.dataString)
    }

    @Test
    fun `the Default apps hint and its action outlast a rotation`() {
        show {
            val request = rememberRoleRequest(R.string.role_call_screening_in_default_apps, roleHeld = { false })
            Button(onClick = { request(Intent("test.REQUEST_ROLE")) }) { Text("Ask") }
        }
        val hint = composeRule.activity.getString(R.string.role_call_screening_in_default_apps)
        composeRule.onNodeWithText("Ask").performClick()
        awaitText(hint)

        restoration.emulateSavedInstanceStateRestore()
        awaitText(hint)
        assertShownInFull(hint)

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.permission_hint_default_apps)).performClick()
        assertEquals(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, awaitStartedActivity().action)
    }

    @Test
    fun `a hint asked for just as the last one closes still shows`() {
        var hints: PermissionHints? = null
        var askForRole: () -> Unit = {}
        show {
            hints = LocalPermissionHints.current
            val appInfo = rememberPermissionRequest(R.string.permission_core_in_app_info)
            val role = rememberRoleRequest(R.string.role_call_screening_in_default_apps, roleHeld = { false })
            askForRole = { role(Intent("test.REQUEST_ROLE")) }
            Button(onClick = { appInfo(listOf(Manifest.permission.READ_CALL_LOG)) }) { Text("Ask") }
        }
        composeRule.onNodeWithText("Ask").performClick()
        awaitText(composeRule.activity.getString(R.string.permission_core_in_app_info))

        // The first snackbar closes, and the second hint is asked for before
        // the first one's effect gets to run again.
        composeRule.runOnUiThread {
            hints!!.snackbars.currentSnackbarData!!.dismiss()
            askForRole()
        }

        awaitText(composeRule.activity.getString(R.string.role_call_screening_in_default_apps))
    }

    @Test
    fun `with no hint host App info opens straight away`() {
        show(withHost = false) {
            val request = rememberPermissionRequest(R.string.permission_sms_in_app_info)
            Button(onClick = { request(listOf(Manifest.permission.READ_SMS)) }) { Text("Ask") }
        }

        composeRule.onNodeWithText("Ask").performClick()

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, awaitStartedActivity().action)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.permission_sms_in_app_info)).assertDoesNotExist()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L

        // Android's largest default font size.
        const val LARGEST_FONT_SCALE = 2f
    }
}
