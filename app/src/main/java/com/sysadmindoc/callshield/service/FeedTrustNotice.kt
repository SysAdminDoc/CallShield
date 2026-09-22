package com.sysadmindoc.callshield.service

import android.content.Context
import android.util.Log
import com.sysadmindoc.callshield.BuildConfig
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.remote.GitHubDataSource
import kotlinx.coroutines.CancellationException

/**
 * Tells the user, once per app version, that feed downloads are failing
 * certificate verification.
 *
 * A failed sync with data already on the device reports success, so nothing
 * else ever says this. It happened for real: from about 2026-08-02 every
 * install's pins stopped matching GitHub's new chain and three releases ran on
 * their bundled snapshot with only a stale sync date to show for it. Update
 * checks are off by default and distribution is sideload-only, so without this
 * nobody learns an update is needed.
 */
internal object FeedTrustNotice {
    internal val RELEASES_URL =
        "https://github.com/${GitHubDataSource.DEFAULT_REPO_OWNER}/${GitHubDataSource.DEFAULT_REPO_NAME}/releases/latest"

    /** A recorded failure the running version has not already shown a notice for. */
    fun shouldNotify(
        failedAt: Long,
        notifiedVersion: Int?,
        currentVersion: Int,
    ): Boolean = failedAt > 0L && notifiedVersion != currentVersion

    /** Never throws except for cancellation: a notice must not change a sync worker's result. */
    suspend fun maybeNotify(
        context: Context,
        repo: SpamRepository,
        currentVersion: Int = BuildConfig.VERSION_CODE,
    ) {
        try {
            if (!shouldNotify(repo.readFeedTrustFailedAt(), repo.readFeedTrustNoticeVersion(), currentVersion)) return
            // Only a notice that was actually posted counts; with notifications
            // blocked, the next sync tries again rather than going quiet forever.
            if (NotificationHelper.notifyFeedTrustFailure(context, RELEASES_URL)) {
                repo.recordFeedTrustNoticeVersion(currentVersion)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "Could not post the feed trust notice", e)
        }
    }

    private const val TAG = "FeedTrustNotice"
}
