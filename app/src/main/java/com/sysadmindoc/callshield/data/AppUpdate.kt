package com.sysadmindoc.callshield.data

enum class AppUpdateStatus {
    NEVER_CHECKED,
    UPDATE_AVAILABLE,
    UP_TO_DATE,
    INSTALLED_NEWER,
    MALFORMED_RELEASE,
    UNAVAILABLE,
}

data class AppUpdateRelease(
    val tagName: String,
    val htmlUrl: String,
    val checksumUrl: String? = null,
)

data class AppUpdateState(
    val status: AppUpdateStatus = AppUpdateStatus.NEVER_CHECKED,
    val latestTag: String? = null,
    val releaseUrl: String? = null,
    val checksumUrl: String? = null,
    val checkedAt: Long = 0L,
) {
    val updateAvailable: Boolean
        get() = status == AppUpdateStatus.UPDATE_AVAILABLE

    companion object {
        fun fromRelease(
            currentVersion: String,
            release: AppUpdateRelease,
            checkedAt: Long = System.currentTimeMillis(),
        ): AppUpdateState {
            val verdict = AppUpdateChecker.evaluate(currentVersion, release.tagName)
            return AppUpdateState(
                status = verdict,
                latestTag = release.tagName,
                releaseUrl = release.htmlUrl,
                checksumUrl = release.checksumUrl,
                checkedAt = checkedAt,
            )
        }

        fun unavailable(checkedAt: Long = System.currentTimeMillis()): AppUpdateState = AppUpdateState(status = AppUpdateStatus.UNAVAILABLE, checkedAt = checkedAt)
    }
}

/** Pure release-tag comparison so update behavior is deterministic and testable. */
object AppUpdateChecker {
    private val versionPattern = Regex("^v?([0-9]+)\\.([0-9]+)\\.([0-9]+)$")

    fun evaluate(
        currentVersion: String,
        latestTag: String,
    ): AppUpdateStatus {
        val current = parse(currentVersion) ?: return AppUpdateStatus.MALFORMED_RELEASE
        val latest = parse(latestTag) ?: return AppUpdateStatus.MALFORMED_RELEASE
        return when {
            compareVersions(latest, current) > 0 -> AppUpdateStatus.UPDATE_AVAILABLE
            compareVersions(latest, current) == 0 -> AppUpdateStatus.UP_TO_DATE
            else -> AppUpdateStatus.INSTALLED_NEWER
        }
    }

    private fun compareVersions(
        left: List<Int>,
        right: List<Int>,
    ): Int =
        left.zip(right).firstOrNull { it.first != it.second }?.let { it.first - it.second }
            ?: left.size - right.size

    private fun parse(raw: String): List<Int>? {
        val match = versionPattern.matchEntire(raw.trim()) ?: return null
        return match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
    }
}
