package com.sysadmindoc.callshield.data.model

/**
 * The newest release, from the signed `data/app_release.json` that the
 * six-hour sync downloads with the other feeds. `scripts/write_app_release.py`
 * writes it at release time. Home offers it while it's newer than the
 * installed build and not dismissed.
 */
data class AppReleaseNotice(
    val versionCode: Int,
    val versionName: String,
    val releaseUrl: String,
    val apkSha256: String,
) {
    /** Whether Home offers this release to a build of [installedCode] that last dismissed [dismissedCode]. */
    fun shouldShow(
        installedCode: Int,
        dismissedCode: Int,
    ): Boolean = versionCode > installedCode && versionCode != dismissedCode
}
