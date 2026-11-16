package com.sysadmindoc.callshield.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.ui.platform.AndroidUiDispatcher
import org.robolectric.Shadows.shadowOf
import kotlin.coroutines.ContinuationInterceptor

/**
 * Gets AndroidUiDispatcher.Main running again if an earlier test left it stuck.
 *
 * The dispatcher is one per Robolectric sandbox, so every class on the same SDK
 * in a Gradle fork shares it. It posts one main-looper message for a batch of
 * work and won't post another until that message runs. Robolectric empties the
 * main looper after each test, so a write that lands after a test's last idle
 * loses the message and the dispatcher queues everything after it forever.
 * Paging Compose shows each page through it, so a paged list then stays on its
 * loading state. Posting the dispatcher's own callback again runs the queue and
 * clears the flag; on a healthy dispatcher it finds nothing to run.
 *
 * Call it in setUp, before setContent, in any test that needs a paged list.
 */
internal fun reviveAndroidUiDispatcher() {
    val dispatcher = AndroidUiDispatcher.Main[ContinuationInterceptor] as AndroidUiDispatcher
    val callback =
        AndroidUiDispatcher::class.java
            .getDeclaredField("dispatchCallback")
            .apply { isAccessible = true }
            .get(dispatcher) as Runnable
    Handler(Looper.getMainLooper()).post(callback)
    shadowOf(Looper.getMainLooper()).idle()
}
