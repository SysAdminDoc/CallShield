package com.sysadmindoc.callshield.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.sysadmindoc.callshield.data.SpamRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsThemeTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `theme preference rejects unknown values`() {
        assertEquals("amoled", sanitizeAppTheme(null))
        assertEquals("amoled", sanitizeAppTheme("neon"))
        assertEquals("system", sanitizeAppTheme("system"))
        assertEquals("light", sanitizeAppTheme("light"))
        assertEquals("graphite", sanitizeAppTheme("graphite"))
        assertEquals("amoled", sanitizeAppTheme("amoled"))
    }

    @Test
    fun `every theme choice reads back, Light included`() =
        runBlocking {
            // Light used to be saved as a missing key, which now reads as AMOLED,
            // so choosing Light in the picker did nothing. One store per value:
            // on Windows DataStore can't rename its temp file over an existing file,
            // so a second write to the same store fails in this JVM test.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                listOf("light", "graphite", "system", "amoled").forEach { theme ->
                    val settings =
                        SettingsRepository(
                            PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings-$theme.preferences_pb") },
                            PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "private-$theme.preferences_pb") },
                        )
                    settings.setAppTheme(theme)
                    assertEquals(theme, settings.appTheme.first())
                }
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `an install upgraded from 1_8_1 keeps the Light it was showing`() =
        runBlocking {
            // v1.7.37 to v1.8.1 saved Light, the default then, as a missing key.
            val migration = KeepShownThemeOnUpgrade { "light" }
            val before = preferencesOf(SpamRepository.KEY_ONBOARDING_DONE to true)
            assertTrue(migration.shouldMigrate(before))

            val after = migration.migrate(before)

            assertEquals("light", after[SpamRepository.KEY_APP_THEME])
            assertFalse(migration.shouldMigrate(after))
        }

    @Test
    fun `an install last run on 1_7_29 keeps AMOLED`() =
        runBlocking {
            // Up to v1.7.29 a missing key meant AMOLED, and choosing AMOLED removed it.
            val after = KeepShownThemeOnUpgrade { "amoled" }.migrate(preferencesOf(SpamRepository.KEY_ONBOARDING_DONE to true))

            assertEquals("amoled", sanitizeAppTheme(after[SpamRepository.KEY_APP_THEME]))
        }

    @Test
    fun `a fresh install keeps AMOLED and is not migrated again`() =
        runBlocking {
            val migration = KeepShownThemeOnUpgrade { null }
            val after = migration.migrate(emptyPreferences())

            assertNull(after[SpamRepository.KEY_APP_THEME])
            assertEquals("amoled", sanitizeAppTheme(after[SpamRepository.KEY_APP_THEME]))
            // Light cached later by this install's own starts must not be read back as an old default.
            val finishedSetup = after.toMutablePreferences().apply { this[SpamRepository.KEY_ONBOARDING_DONE] = true }
            assertFalse(KeepShownThemeOnUpgrade { "light" }.shouldMigrate(finishedSetup))
        }

    @Test
    fun `a saved theme survives the migration and a junk cache is ignored`() =
        runBlocking {
            val saved = preferencesOf(SpamRepository.KEY_ONBOARDING_DONE to true, SpamRepository.KEY_APP_THEME to "graphite")
            assertEquals("graphite", KeepShownThemeOnUpgrade { "light" }.migrate(saved)[SpamRepository.KEY_APP_THEME])

            val junk = KeepShownThemeOnUpgrade { "neon" }.migrate(emptyPreferences())
            assertNull(junk[SpamRepository.KEY_APP_THEME])
        }
}
