package com.sysadmindoc.callshield.data

import androidx.datastore.core.CorruptionException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceCorruptionHandlerTest {
    @Test
    fun `corrupt preference file is replaced with safe defaults`() {
        val recovered =
            runBlocking {
                replaceCorruptPreferences().handleCorruption(
                    CorruptionException("invalid preferences protobuf", IllegalArgumentException()),
                )
            }

        assertTrue(recovered.asMap().isEmpty())
    }
}
