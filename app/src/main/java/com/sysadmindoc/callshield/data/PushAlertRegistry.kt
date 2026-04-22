package com.sysadmindoc.callshield.data

import java.util.ArrayDeque

/**
 * In-memory ring buffer of recent notifications from messaging / delivery /
 * rideshare apps (Uber, DoorDash, Amazon, USPS, Gmail, Google Messages…).
 *
 * When an unknown number calls, the [com.sysadmindoc.callshield.data.checker.PushAlertChecker]
 * scans this buffer for a regex match — if we just got "Your Uber driver
 * Michael is arriving" 30 seconds ago, the unknown caller is almost
 * certainly the driver, not spam.
 *
 * Inspired by SpamBlocker's `NotificationListenerService` push-alert
 * mechanism (github.com/aj3423/SpamBlocker). Single biggest false-positive
 * fix in the OSS peer landscape.
 *
 * ## Design decisions
 *
 * - **In-memory only** — this data is ephemeral by design. Persisting
 *   notification contents would be a privacy nightmare; a TTL-bounded
 *   in-memory ring is good enough.
 * - **Bounded size** — 128 entries is >>than any realistic TTL window.
 *   Older entries are evicted when capacity is hit.
 * - **Sender-package allowlist** — only apps in [ALERT_SOURCE_PACKAGES]
 *   feed this buffer. Prevents random notification spam from polluting
 *   the trust signal.
 * - **Thread-safe** — `synchronized` on all mutations; the listener
 *   thread writes, the call-screening coroutine reads.
 */
object PushAlertRegistry {

    /**
     * Default allowlist of packages whose notifications carry
     * trust-building context about an unknown caller. User-editable in a
     * future settings screen; for now a sensible default covers the
     * 80/20 case.
     */
    val ALERT_SOURCE_PACKAGES = setOf(
        // Rideshare
        "com.ubercab",
        "com.ubercab.driver",
        "com.lyft.android",
        // Delivery
        "com.dd.doordash",
        "com.grubhub.android",
        "com.ubercab.eats",
        "com.instacart.client",
        "com.amazon.mShop.android.shopping",
        "com.amazon.logistics.driver",
        "com.fedex.ida.android",
        "com.ups.mobile.android",
        // USPS Informed Delivery
        "gov.usps.mobile",
        // Messaging bridges — a verification text from Google/Apple can precede the call
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.mms",
        // Generic business (calendar alerts often precede an expected call)
        "com.google.android.calendar",
        "com.microsoft.office.outlook",
    )

    /** Default TTL — how long a notification stays relevant. */
    const val DEFAULT_TTL_MS = 30L * 60L * 1000L   // 30 minutes

    private const val MAX_ENTRIES = 128

    // User opt-outs (A3 allowlist editor). A package appears here only if
    // the user has explicitly disabled it in settings; the effective
    // allowlist is [ALERT_SOURCE_PACKAGES] minus this set. Empty by
    // default, so the feature behaves identically for users who never
    // touch the allowlist.
    //
    // Fed by [com.sysadmindoc.callshield.service.RcsNotificationListener]'s
    // background observer — never write to this from the hot path.
    @Volatile
    private var disabledPackages: Set<String> = emptySet()

    /**
     * Called from the listener's preference observer; lock-free on the hot
     * path. Defensive `HashSet(...)` copy guards against future callers
     * passing a mutable set — today's caller is a DataStore-backed
     * immutable set so the copy is cheap insurance.
     */
    fun setDisabledPackages(disabled: Set<String>) {
        disabledPackages = HashSet(disabled)
    }

    /**
     * Apply an opt-out update atomically: prune first (so already-cached
     * alerts from newly-disabled packages can't slip into a concurrent
     * screening verdict), then publish the new disabled set. Use this
     * instead of calling [setDisabledPackages] + [pruneByPackages]
     * separately — the two-step pattern had a visible race where stale
     * alerts from newly-disabled packages were still reachable via
     * [snapshot] between the writes.
     */
    fun applyOptOuts(disabled: Set<String>) {
        val newlyDisabled = disabled - disabledPackages
        if (newlyDisabled.isNotEmpty()) {
            pruneByPackages(newlyDisabled)
        }
        disabledPackages = HashSet(disabled)
    }

    /** Snapshot of the active opt-out set — used by the listener to detect flips. */
    fun currentDisabledPackages(): Set<String> = disabledPackages

    /**
     * `true` iff [pkg] is in the default allowlist AND the user hasn't
     * turned it off. The check runs on the notification-listener thread,
     * so it avoids allocation and is a hash-set-contains pair.
     */
    fun isAllowedSource(pkg: String): Boolean =
        pkg in ALERT_SOURCE_PACKAGES && pkg !in disabledPackages

    /**
     * Drop cached alerts that originated from any of [packages]. Called
     * when the user turns off a source so already-captured content from
     * that app can't influence the next call's verdict.
     */
    fun pruneByPackages(packages: Set<String>) {
        if (packages.isEmpty()) return
        synchronized(lock) {
            val iter = buffer.iterator()
            while (iter.hasNext()) {
                if (iter.next().packageName in packages) iter.remove()
            }
        }
    }

    data class Alert(
        val packageName: String,
        val title: String,
        val body: String,
        val timestamp: Long,
    ) {
        /** `title` + `body` joined for regex matching. */
        val searchText: String get() = if (title.isEmpty()) body else "$title\n$body"
    }

    private val lock = Any()
    private val buffer = ArrayDeque<Alert>(MAX_ENTRIES)

    /**
     * Record a notification. Called by [com.sysadmindoc.callshield.service.RcsNotificationListener]
     * for every posted notification from an allowlisted package.
     */
    fun record(alert: Alert) {
        synchronized(lock) {
            // Drop exact duplicates posted within the last 5 s — Google
            // Messages re-posts the same notification multiple times per
            // incoming SMS and we don't want to fill the buffer.
            val recent = buffer.peekLast()
            if (recent != null &&
                recent.packageName == alert.packageName &&
                recent.title == alert.title &&
                recent.body == alert.body &&
                alert.timestamp - recent.timestamp < 5_000
            ) return

            while (buffer.size >= MAX_ENTRIES) {
                buffer.pollFirst()
            }
            buffer.addLast(alert)
        }
    }

    /**
     * Return a defensive copy of all alerts newer than [olderThanTtlMs]
     * ago. The checker iterates this snapshot without holding the lock.
     */
    fun snapshot(nowMillis: Long = System.currentTimeMillis(), ttlMs: Long = DEFAULT_TTL_MS): List<Alert> {
        val cutoff = nowMillis - ttlMs
        // Iterate newest-first so the checker sees the most recent match
        // quickly. `reversed()` on ArrayDeque copies — fine, the buffer is
        // bounded to MAX_ENTRIES so this is trivial even under lock.
        return synchronized(lock) {
            buffer.reversed().filter { it.timestamp >= cutoff }
        }
    }

    /**
     * Evict stale entries. Called opportunistically from [record]; callers
     * can invoke manually if they want a clean buffer (tests do).
     */
    fun pruneOlderThan(cutoffMillis: Long) {
        synchronized(lock) {
            while (buffer.peekFirst()?.let { it.timestamp < cutoffMillis } == true) {
                buffer.pollFirst()
            }
        }
    }

    fun clear() {
        synchronized(lock) { buffer.clear() }
    }

    /** Current size — used only by tests and debug UI. */
    fun size(): Int = synchronized(lock) { buffer.size }
}
