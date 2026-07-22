package com.sysadmindoc.callshield.ui.screens.more

import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.ModelHealth
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelHealthUiStateTest {
    @Test
    fun `maps every model health to an explicit visible severity`() {
        val expectations =
            mapOf(
                ModelHealth.GBT_ACTIVE to (R.string.model_health_gbt_active to ModelHealthSeverity.Healthy),
                ModelHealth.LR_ACTIVE to (R.string.model_health_lr_active to ModelHealthSeverity.Healthy),
                ModelHealth.DEGRADED_TO_LR to (R.string.model_health_degraded to ModelHealthSeverity.Warning),
                ModelHealth.PARSE_FAILED to (R.string.model_health_parse_failed to ModelHealthSeverity.Error),
                ModelHealth.DEFAULTS to (R.string.model_health_defaults to ModelHealthSeverity.Warning),
                ModelHealth.UNINITIALIZED to (R.string.model_health_loading to ModelHealthSeverity.Info),
            )

        expectations.forEach { (health, expected) ->
            val actual = modelHealthUiState(health)
            assertEquals(expected.first, actual.statusRes)
            assertEquals(expected.second, actual.severity)
        }
    }
}
