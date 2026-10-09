package com.sysadmindoc.callshield.ui.screens.more

import android.app.ApplicationExitInfo
import com.sysadmindoc.callshield.R
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessExitHistoryTest {
    @Test
    fun `every exit reason Android names gets its own label`() {
        val reasons =
            listOf(
                ApplicationExitInfo.REASON_UNKNOWN,
                ApplicationExitInfo.REASON_EXIT_SELF,
                ApplicationExitInfo.REASON_SIGNALED,
                ApplicationExitInfo.REASON_LOW_MEMORY,
                ApplicationExitInfo.REASON_CRASH,
                ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR,
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
                ApplicationExitInfo.REASON_PERMISSION_CHANGE,
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
                ApplicationExitInfo.REASON_USER_REQUESTED,
                ApplicationExitInfo.REASON_USER_STOPPED,
                ApplicationExitInfo.REASON_DEPENDENCY_DIED,
                ApplicationExitInfo.REASON_OTHER,
                ApplicationExitInfo.REASON_FREEZER,
                ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
                ApplicationExitInfo.REASON_PACKAGE_UPDATED,
            )

        val labels = reasons.map(::processExitReasonLabelRes)

        assertEquals(reasons.size, labels.distinct().size)
        assertEquals(R.string.protection_test_exit_low_memory, processExitReasonLabelRes(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals(R.string.protection_test_exit_user_requested, processExitReasonLabelRes(ApplicationExitInfo.REASON_USER_REQUESTED))
    }

    @Test
    fun `a reason a newer Android adds reads as another reason, not a missing one`() {
        assertEquals(R.string.protection_test_exit_other, processExitReasonLabelRes(99))
        assertEquals(R.string.protection_test_exit_unknown, processExitReasonLabelRes(ApplicationExitInfo.REASON_UNKNOWN))
    }

    @Test
    fun `only the newest five show, newest first`() {
        val exits = (1L..7L).map { ProcessExit(timestamp = it * 1_000L, reason = ApplicationExitInfo.REASON_LOW_MEMORY) }.shuffled()

        val shown = latestProcessExits(exits)

        assertEquals(listOf(7_000L, 6_000L, 5_000L, 4_000L, 3_000L), shown.map { it.timestamp })
    }
}
