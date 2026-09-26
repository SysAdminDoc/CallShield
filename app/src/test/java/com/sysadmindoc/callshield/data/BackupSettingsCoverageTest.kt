package com.sysadmindoc.callshield.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.sysadmindoc.callshield.data.BackupRestore.BackupSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Every DataStore key SpamRepository declares is either carried by a backup or
 * left out on purpose, with the reason here. The outgoing-call hold, the feed
 * mirror, the theme and the update check were once missing and nothing noticed.
 */
class BackupSettingsCoverageTest {
    private val excluded =
        mapOf(
            "KEY_ABSTRACT_API_KEY" to "a retired API key the app deletes on start",
            "KEY_APP_UPDATE_CHECKED_AT" to "the last update check's result, not a setting",
            "KEY_APP_UPDATE_CHECKSUM_URL" to "the last update check's result, not a setting",
            "KEY_APP_UPDATE_RELEASE_URL" to "the last update check's result, not a setting",
            "KEY_APP_UPDATE_STATUS" to "the last update check's result, not a setting",
            "KEY_APP_UPDATE_TAG" to "the last update check's result, not a setting",
            "KEY_CNAP_SCREENED_WITH" to "a counter of this phone's calls",
            "KEY_CNAP_SCREENED_WITHOUT" to "a counter of this phone's calls",
            "KEY_COMMUNITY_REPORT_LEDGER" to "this phone's report outbox; restoring it would resend or drop reports",
            "KEY_DB_UPDATED" to "sync state of this phone's copy of the database",
            "KEY_DB_VERSION" to "sync state of this phone's copy of the database",
            "KEY_DISMISSED_RULE_CONFLICTS" to "notices this phone already showed",
            "KEY_EXTERNAL_BLOCKLIST_SUBSCRIPTIONS" to
                "subscriptions carry this phone's fetch state, and a restored URL would download a list nobody re-reviewed",
            "KEY_FEED_TRUST_FAILED_AT" to "sync state of this phone's feeds",
            "KEY_FEED_TRUST_FAILED_VERSION" to "sync state of this phone's feeds",
            "KEY_FEED_TRUST_NOTICE_VERSION" to "a notice this phone already showed",
            "KEY_HOT_DATA_CLEARED" to "sync state of this phone's trending feeds",
            "KEY_HOT_DATA_DIGESTS" to "sync state of this phone's trending feeds",
            "KEY_HOT_DATA_GENERATED_AT" to "sync state of this phone's trending feeds",
            "KEY_HOT_DATA_LAST_GOOD" to "sync state of this phone's trending feeds",
            "KEY_HOT_DATA_REFUSED" to "sync state of this phone's trending feeds",
            "KEY_HOT_DATA_UNAVAILABLE" to "sync state of this phone's trending feeds",
            "KEY_HOT_DATA_UNREACHABLE" to "sync state of this phone's trending feeds",
            "KEY_LAST_MANIFEST_DIGEST" to "sync state of this phone's copy of the database",
            "KEY_LAST_SHA" to "sync state of this phone's copy of the database",
            "KEY_LAST_SHARD_HASHES" to "sync state of this phone's copy of the database",
            "KEY_LAST_SYNC" to "sync state of this phone's copy of the database",
            "KEY_LAST_SYNC_SOURCE" to "sync state of this phone's copy of the database",
            "KEY_NOTIFICATION_CAPABILITY_API" to "a measurement of this phone",
            "KEY_NOTIFICATION_CAPABILITY_LATENCY" to "a measurement of this phone",
            "KEY_NOTIFICATION_CAPABILITY_OBSERVED_AT" to "a measurement of this phone",
            "KEY_NOTIFICATION_CAPABILITY_STATE" to "a measurement of this phone",
            "KEY_ONBOARDING_DONE" to "this install's setup progress",
            "KEY_PROTECTION_ROLE_EVER_HELD" to "this install's role history",
            "KEY_PROTECTION_ROLE_LOSS_NOTICE_SHOWN" to "a notice this phone already showed",
            "KEY_SMS_CAPABILITY_API" to "a measurement of this phone",
            "KEY_SMS_CAPABILITY_LATENCY" to "a measurement of this phone",
            "KEY_SMS_CAPABILITY_OBSERVED_AT" to "a measurement of this phone",
            "KEY_SMS_CAPABILITY_STATE" to "a measurement of this phone",
            "KEY_THEME_DEFAULT_SETTLED" to "the theme migration's record; the theme itself is backed up",
            "KEY_TRENDING_APPLIED_AT" to "sync state of this phone's trending feeds",
            "KEY_TRENDING_NUMBERS" to "sync state of this phone's trending feeds",
        )

    /** SpamRepository's DataStore keys by their Kotlin names. */
    private fun declaredKeys(): Map<String, Preferences.Key<*>> =
        SpamRepository::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && Preferences.Key::class.java.isAssignableFrom(it.type) }
            .associate { field ->
                field.isAccessible = true
                field.name to field.get(null) as Preferences.Key<*>
            }

    /** The keys a backup writes when every setting has a value. */
    private fun keysABackupWrites(): Set<Preferences.Key<*>> {
        val preferences = mutablePreferencesOf()
        BackupSettings(
            pushAlertDisabledPackages = listOf("com.example.chat"),
            regionBlockEnabled = true,
            allowedRegions = listOf("NY"),
            cnapTrustPatterns = listOf("PHARMACY"),
            cnapBlockPatterns = listOf("SURVEY"),
            categoryCallActions = listOf("robocall=block"),
            selectedContactGroups = listOf("group:1"),
            activeProfileName = "recommended",
            notificationScreeningPackages = listOf("com.google.android.apps.messaging"),
            meetingModeApps = listOf("us.zoom.videomeetings"),
            outgoingCallHoldEnabled = true,
            appTheme = "light",
            appUpdateChecksEnabled = true,
            feedMirrorUrl = "https://mirror.example/callshield/",
        ).writeTo(preferences)
        return preferences.asMap().keys
    }

    @Test
    fun `every settings key is backed up or left out with a reason`() {
        val keys = declaredKeys()
        assertTrue("found only ${keys.size} keys; the reflection broke", keys.size >= 90)
        val written = keysABackupWrites()

        val neither = keys.filter { (name, key) -> key !in written && name !in excluded }.keys.sorted()
        assertEquals("keys neither backed up nor excluded", emptyList<String>(), neither)
        val both = keys.filter { (name, key) -> key in written && name in excluded }.keys.sorted()
        assertEquals("keys backed up but still listed as excluded", emptyList<String>(), both)
        assertEquals("exclusions for keys that no longer exist", emptySet<String>(), excluded.keys - keys.keys)
        assertTrue(excluded.values.all { it.isNotBlank() })
    }

    @Test
    fun `the settings that were missing round-trip`() {
        val source =
            mutablePreferencesOf(
                SpamRepository.KEY_OUTGOING_CALL_HOLD to true,
                SpamRepository.KEY_APP_THEME to "light",
                SpamRepository.KEY_APP_UPDATE_CHECKS to true,
                SpamRepository.KEY_FEED_MIRROR_URL to "https://mirror.example/callshield/",
            )
        val restored = mutablePreferencesOf()

        source.toBackupSettings().sanitized().writeTo(restored)

        assertEquals(true, restored[SpamRepository.KEY_OUTGOING_CALL_HOLD])
        assertEquals("light", restored[SpamRepository.KEY_APP_THEME])
        assertEquals(true, restored[SpamRepository.KEY_APP_UPDATE_CHECKS])
        assertEquals("https://mirror.example/callshield/", restored[SpamRepository.KEY_FEED_MIRROR_URL])
    }

    @Test
    fun `a phone without a mirror or update checks restores without them`() {
        val restored =
            mutablePreferencesOf(
                SpamRepository.KEY_APP_UPDATE_CHECKS to true,
                SpamRepository.KEY_FEED_MIRROR_URL to "https://old.example/",
            )

        mutablePreferencesOf().toBackupSettings().sanitized().writeTo(restored)

        assertEquals(null, restored[SpamRepository.KEY_APP_UPDATE_CHECKS])
        assertEquals(null, restored[SpamRepository.KEY_FEED_MIRROR_URL])
        assertEquals("amoled", restored[SpamRepository.KEY_APP_THEME])
    }

    @Test
    fun `an older backup without these settings leaves them alone`() {
        val current =
            mutablePreferencesOf(
                SpamRepository.KEY_OUTGOING_CALL_HOLD to true,
                SpamRepository.KEY_APP_THEME to "graphite",
                SpamRepository.KEY_FEED_MIRROR_URL to "https://mirror.example/",
            )

        BackupSettings().sanitized().writeTo(current)

        assertEquals(true, current[SpamRepository.KEY_OUTGOING_CALL_HOLD])
        assertEquals("graphite", current[SpamRepository.KEY_APP_THEME])
        assertEquals("https://mirror.example/", current[SpamRepository.KEY_FEED_MIRROR_URL])
    }

    @Test
    fun `a theme or mirror this version can't use is dropped`() {
        val sanitized = BackupSettings(appTheme = "neon", feedMirrorUrl = "http://mirror.example/").sanitized()

        assertEquals(null, sanitized.appTheme)
        assertEquals(null, sanitized.feedMirrorUrl)
    }
}
