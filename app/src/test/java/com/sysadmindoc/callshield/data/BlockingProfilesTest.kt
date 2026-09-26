package com.sysadmindoc.callshield.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for BlockingProfiles — profile enum values and properties.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BlockingProfilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    // ─── All Profile enum values exist ───────────────────────────────

    @Test
    fun profile_WORK_exists() {
        assertNotNull(BlockingProfiles.Profile.WORK)
    }

    @Test
    fun profile_PERSONAL_exists() {
        assertNotNull(BlockingProfiles.Profile.PERSONAL)
    }

    @Test
    fun profile_SLEEP_exists() {
        assertNotNull(BlockingProfiles.Profile.SLEEP)
    }

    @Test
    fun profile_MAX_exists() {
        assertNotNull(BlockingProfiles.Profile.MAX)
    }

    @Test
    fun profile_OFF_exists() {
        assertNotNull(BlockingProfiles.Profile.OFF)
    }

    @Test
    fun profile_CONTACTS_ONLY_exists() {
        assertNotNull(BlockingProfiles.Profile.CONTACTS_ONLY)
    }

    @Test
    fun profile_enumHasExactly6Values() {
        assertEquals(6, BlockingProfiles.Profile.values().size)
    }

    @Test
    fun profile_valueOf_roundTrips() {
        for (profile in BlockingProfiles.Profile.values()) {
            assertEquals(profile, BlockingProfiles.Profile.valueOf(profile.name))
        }
    }

    // ─── Profile properties are distinct ─────────────────────────────

    @Test
    fun profile_labels_areAllDistinct() {
        val labels = BlockingProfiles.Profile.values().map { it.labelRes }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun profile_descriptions_areAllDistinct() {
        val descriptions = BlockingProfiles.Profile.values().map { it.descriptionRes }
        assertEquals(descriptions.size, descriptions.toSet().size)
    }

    @Test
    fun profile_names_areAllDistinct() {
        val names = BlockingProfiles.Profile.values().map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    // ─── Specific property values ────────────────────────────────────

    @Test
    fun profile_WORK_hasCorrectLabel() {
        assertEquals("Recommended", context.getString(BlockingProfiles.Profile.WORK.labelRes))
    }

    @Test
    fun profile_PERSONAL_hasCorrectLabel() {
        assertEquals("Personal", context.getString(BlockingProfiles.Profile.PERSONAL.labelRes))
    }

    @Test
    fun profile_SLEEP_hasCorrectLabel() {
        assertEquals("Sleep", context.getString(BlockingProfiles.Profile.SLEEP.labelRes))
    }

    @Test
    fun profile_MAX_hasCorrectLabel() {
        assertEquals("Strict", context.getString(BlockingProfiles.Profile.MAX.labelRes))
    }

    @Test
    fun profile_OFF_hasCorrectLabel() {
        assertEquals("Off", context.getString(BlockingProfiles.Profile.OFF.labelRes))
    }

    @Test
    fun profile_WORK_descriptionMentionsSpam() {
        assertTrue(
            context.getString(BlockingProfiles.Profile.WORK.descriptionRes).contains("spam", ignoreCase = true),
        )
    }

    @Test
    fun profile_OFF_descriptionMentionsOff() {
        assertTrue(
            context.getString(BlockingProfiles.Profile.OFF.descriptionRes).contains("off", ignoreCase = true),
        )
    }

    @Test
    fun profile_SLEEP_descriptionMentionsQuietHours() {
        assertTrue(
            context.getString(BlockingProfiles.Profile.SLEEP.descriptionRes).contains("quiet hours on", ignoreCase = true),
        )
    }

    @Test
    fun profile_descriptions_match_the_controls_they_write() =
        runBlocking {
            IsolatedRepositoryFixture(context).use { fixture ->
                for (profile in BlockingProfiles.Profile.entries) {
                    fixture.repository.replaceBlockingSettings(profile.settings, profile.name)
                    val prefs = fixture.repository.readPrefsSnapshot()
                    val settings =
                        BlockingProfiles.Settings(
                            blockCalls = prefs[SpamRepository.KEY_BLOCK_CALLS] ?: true,
                            analyzeSms = prefs[SpamRepository.KEY_BLOCK_SMS] ?: true,
                            blockHidden = prefs[SpamRepository.KEY_BLOCK_UNKNOWN] ?: false,
                            aggressive = prefs[SpamRepository.KEY_AGGRESSIVE_MODE] ?: false,
                            quietHours = prefs[SpamRepository.KEY_TIME_BLOCK] ?: false,
                            contactsOnly = prefs[SpamRepository.KEY_CONTACTS_ONLY] ?: false,
                        )
                    assertEquals(profile.name, profile.settings, settings)
                    val description = context.getString(profile.descriptionRes)
                    assertEquals(profile.name, settings.blockCalls, description.contains("blocked") || description.contains("Aggressive call checks"))
                    assertEquals(profile.name, settings.analyzeSms, description.contains("Risky texts flagged"))
                    assertEquals(profile.name, settings.blockHidden, description.contains("hidden callers blocked", ignoreCase = true))
                    assertEquals(profile.name, settings.aggressive, description.contains("Aggressive call checks"))
                    assertEquals(profile.name, settings.quietHours, description.contains("quiet hours on"))
                    assertEquals(profile.name, settings.contactsOnly, description.contains("Only contacts and trusted callers ring"))
                }
            }
        }

    @Test
    fun replacing_then_restoring_a_profile_preserves_all_six_controls() =
        runBlocking {
            IsolatedRepositoryFixture(context).use { fixture ->
                val before = BlockingProfiles.Settings(false, false, true, true, true, true)
                fixture.repository.replaceBlockingSettings(before, null)
                val snapshot = fixture.repository.replaceBlockingSettings(BlockingProfiles.Profile.WORK.settings, "WORK")
                assertEquals(before, snapshot.settings)
                assertNull(snapshot.activeProfileName)

                fixture.repository.replaceBlockingSettings(snapshot.settings, snapshot.activeProfileName)
                val prefs = fixture.repository.readPrefsSnapshot()
                assertFalse(prefs[SpamRepository.KEY_BLOCK_CALLS] ?: true)
                assertFalse(prefs[SpamRepository.KEY_BLOCK_SMS] ?: true)
                assertTrue(prefs[SpamRepository.KEY_BLOCK_UNKNOWN] ?: false)
                assertTrue(prefs[SpamRepository.KEY_AGGRESSIVE_MODE] ?: false)
                assertTrue(prefs[SpamRepository.KEY_TIME_BLOCK] ?: false)
                assertTrue(prefs[SpamRepository.KEY_CONTACTS_ONLY] ?: false)
                assertNull(prefs[SpamRepository.KEY_ACTIVE_PROFILE])
            }
        }

    // ─── Ordinal ordering ────────────────────────────────────────────

    @Test
    fun profile_ordinalOrder_isCorrect() {
        val values = BlockingProfiles.Profile.values()
        assertEquals(BlockingProfiles.Profile.WORK, values[0])
        assertEquals(BlockingProfiles.Profile.PERSONAL, values[1])
        assertEquals(BlockingProfiles.Profile.SLEEP, values[2])
        assertEquals(BlockingProfiles.Profile.MAX, values[3])
        assertEquals(BlockingProfiles.Profile.OFF, values[4])
        assertEquals(BlockingProfiles.Profile.CONTACTS_ONLY, values[5])
    }
}
