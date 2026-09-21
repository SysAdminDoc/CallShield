package com.sysadmindoc.callshield.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A signed model proves who made it, not that it's the latest. A genuine older
 * copy served by a mirror, or by anyone once pinning fails, must not replace
 * the model already in use.
 */
class ModelFreshnessTest {
    private fun model(generated: String?) =
        buildString {
            append("""{"version": 3, """)
            if (generated != null) append(""""generated": "$generated", """)
            append(""""model_type": "gbt"}""")
        }

    @Test
    fun `an older model is refused and a newer or identical one is not`() {
        val installed = model("2026-10-01T00:00:00+00:00")

        assertTrue(isOlderModel(model("2026-09-01T00:00:00+00:00"), installed))
        assertFalse(isOlderModel(model("2026-11-01T00:00:00+00:00"), installed))
        assertFalse(isOlderModel(model("2026-10-01T00:00:00+00:00"), installed))
    }

    @Test
    fun `an unstamped model predates every stamped one`() {
        assertTrue(isOlderModel(model(null), model("2026-10-01T00:00:00+00:00")))
    }

    @Test
    fun `with no stamp on the installed model there is nothing to compare against`() {
        assertFalse(isOlderModel(model(null), model(null)))
        assertFalse(isOlderModel(model("2020-01-01T00:00:00+00:00"), model(null)))
    }
}
