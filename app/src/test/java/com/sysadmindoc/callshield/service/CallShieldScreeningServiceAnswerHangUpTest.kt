package com.sysadmindoc.callshield.service

import org.junit.Assert.assertEquals
import org.junit.Test

class CallShieldScreeningServiceAnswerHangUpTest {
    @Test
    fun `answer and hang up decision has full truth table`() {
        listOf(false, true).forEach { enabled ->
            listOf(false, true).forEach { silenceWins ->
                listOf(false, true).forEach { permissionsGranted ->
                    listOf(false, true).forEach { busy ->
                        assertEquals(
                            enabled && !silenceWins && permissionsGranted && !busy,
                            CallShieldScreeningService.shouldAnswerAndHangUp(
                                enabled = enabled,
                                silenceWins = silenceWins,
                                permissionsGranted = permissionsGranted,
                                busy = busy,
                            ),
                        )
                    }
                }
            }
        }
    }
}
