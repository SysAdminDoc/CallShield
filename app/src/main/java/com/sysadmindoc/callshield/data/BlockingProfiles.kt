package com.sysadmindoc.callshield.data

import android.content.Context

/**
 * Blocking profiles — preset configurations for different scenarios.
 * Work: block all spam, allow unknown (clients may call)
 * Personal: block spam + unknown numbers
 * Sleep: block everything except contacts
 * Off: disable all blocking
 */
object BlockingProfiles {

    enum class Profile(val label: String, val description: String) {
        WORK("Work", "Block spam, allow unknown callers"),
        PERSONAL("Personal", "Block spam + unknown numbers"),
        SLEEP("Sleep", "Block everything except contacts"),
        MAX("Maximum", "Aggressive mode + block unknowns + quiet hours"),
        OFF("Off", "Disable all blocking")
    }

    suspend fun apply(context: Context, profile: Profile) {
        val repo = SpamRepository.getInstance(context)
        when (profile) {
            Profile.WORK -> {
                repo.setBlockCalls(true)
                repo.setBlockSms(true)
                repo.setBlockUnknown(false)
                repo.setAggressiveMode(false)
                repo.setTimeBlock(false)
            }
            Profile.PERSONAL -> {
                repo.setBlockCalls(true)
                repo.setBlockSms(true)
                repo.setBlockUnknown(true)
                repo.setAggressiveMode(false)
                repo.setTimeBlock(false)
            }
            Profile.SLEEP -> {
                repo.setBlockCalls(true)
                repo.setBlockSms(true)
                repo.setBlockUnknown(true)
                repo.setAggressiveMode(false)
                repo.setTimeBlock(true)
            }
            Profile.MAX -> {
                repo.setBlockCalls(true)
                repo.setBlockSms(true)
                repo.setBlockUnknown(true)
                repo.setAggressiveMode(true)
                repo.setTimeBlock(true)
            }
            Profile.OFF -> {
                repo.setBlockCalls(false)
                repo.setBlockSms(false)
                repo.setBlockUnknown(false)
                repo.setAggressiveMode(false)
                repo.setTimeBlock(false)
            }
        }
    }
}
