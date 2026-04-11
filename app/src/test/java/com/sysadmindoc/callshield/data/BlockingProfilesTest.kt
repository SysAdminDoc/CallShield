package com.sysadmindoc.callshield.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for BlockingProfiles — profile enum values and properties.
 */
class BlockingProfilesTest {

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
    fun profile_enumHasExactly5Values() {
        assertEquals(5, BlockingProfiles.Profile.values().size)
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
        val labels = BlockingProfiles.Profile.values().map { it.label }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun profile_descriptions_areAllDistinct() {
        val descriptions = BlockingProfiles.Profile.values().map { it.description }
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
        assertEquals("Work", BlockingProfiles.Profile.WORK.label)
    }

    @Test
    fun profile_PERSONAL_hasCorrectLabel() {
        assertEquals("Personal", BlockingProfiles.Profile.PERSONAL.label)
    }

    @Test
    fun profile_SLEEP_hasCorrectLabel() {
        assertEquals("Sleep", BlockingProfiles.Profile.SLEEP.label)
    }

    @Test
    fun profile_MAX_hasCorrectLabel() {
        assertEquals("Maximum", BlockingProfiles.Profile.MAX.label)
    }

    @Test
    fun profile_OFF_hasCorrectLabel() {
        assertEquals("Off", BlockingProfiles.Profile.OFF.label)
    }

    @Test
    fun profile_WORK_descriptionMentionsSpam() {
        assertTrue(BlockingProfiles.Profile.WORK.description.contains("spam", ignoreCase = true))
    }

    @Test
    fun profile_OFF_descriptionMentionsDisable() {
        assertTrue(BlockingProfiles.Profile.OFF.description.contains("disable", ignoreCase = true))
    }

    @Test
    fun profile_SLEEP_descriptionMentionsContacts() {
        assertTrue(BlockingProfiles.Profile.SLEEP.description.contains("contacts", ignoreCase = true))
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
    }
}
