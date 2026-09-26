package com.sysadmindoc.callshield.data

import android.content.Context
import com.sysadmindoc.callshield.R

/** Presets for the call and message controls changed together by a profile. */
object BlockingProfiles {
    data class Settings(
        val blockCalls: Boolean = true,
        val analyzeSms: Boolean = true,
        val blockHidden: Boolean = false,
        val aggressive: Boolean = false,
        val quietHours: Boolean = false,
        val contactsOnly: Boolean = false,
    )

    data class Snapshot(
        val settings: Settings,
        val activeProfileName: String?,
    )

    enum class Profile(
        val labelRes: Int,
        val descriptionRes: Int,
        val settings: Settings,
    ) {
        WORK(R.string.onboarding_profile_recommended, R.string.profile_work_description, Settings()),
        PERSONAL(
            R.string.dashboard_profile_personal,
            R.string.profile_personal_description,
            Settings(blockHidden = true),
        ),
        SLEEP(
            R.string.dashboard_profile_sleep,
            R.string.profile_sleep_description,
            Settings(blockHidden = true, quietHours = true),
        ),
        MAX(
            R.string.onboarding_profile_strict,
            R.string.profile_maximum_description,
            Settings(blockHidden = true, aggressive = true, quietHours = true),
        ),
        OFF(
            R.string.dashboard_profile_off,
            R.string.profile_off_description,
            Settings(blockCalls = false, analyzeSms = false),
        ),
        CONTACTS_ONLY(
            R.string.dashboard_profile_contacts_only,
            R.string.profile_contacts_only_description,
            Settings(blockHidden = true, contactsOnly = true),
        ),
    }

    suspend fun apply(
        context: Context,
        profile: Profile,
    ): Snapshot = SpamRepository.getInstance(context).replaceBlockingSettings(profile.settings, profile.name)

    suspend fun restore(
        context: Context,
        snapshot: Snapshot,
    ) {
        SpamRepository.getInstance(context).replaceBlockingSettings(snapshot.settings, snapshot.activeProfileName)
    }
}
