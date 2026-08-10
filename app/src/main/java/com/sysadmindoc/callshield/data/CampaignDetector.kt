package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.model.CampaignObservation
import com.sysadmindoc.callshield.util.filterAsciiDigits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/** Evidence summary used by the campaign checker and caller explanation surfaces. */
data class CampaignEvidence(
    val prefix: String,
    val observationCount: Int,
    val distinctNumberCount: Int,
    val repeatedNumberCount: Int,
    val sourceAgreementCount: Int,
) {
    val isActive: Boolean
        get() = distinctNumberCount >= CampaignDetector.MIN_DISTINCT_NUMBERS
}

/**
 * Graph-based campaign detection via NPA-NXX prefix clustering.
 *
 * A small in-memory window keeps the live screening path fast. A bounded
 * seven-day Room observation store lets a process restart retain neighbor
 * velocity and number churn without retaining unbounded call history.
 */
class CampaignDetector
    @Inject
    constructor() {
        private data class ObservationSample(
            val number: String,
            val observedAt: Long,
            val sourceIds: Set<String>,
        )

        private data class PersistedPrefixCache(
            val loadedAt: Long,
            val observations: List<CampaignObservation>,
        )

        private val lock = Any()

        /** Kept as timestamps for compatibility with existing diagnostics/tests. */
        private val recentPrefixes = mutableMapOf<String, MutableList<Long>>()
        private val recentNumbers = mutableMapOf<String, MutableList<String>>()
        private val recentSourceIds = mutableMapOf<String, MutableList<Set<String>>>()
        private val persistedCache = mutableMapOf<String, PersistedPrefixCache>()
        private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val lastPersistencePruneAt = AtomicLong(0L)

        @Volatile private var observationStore: CampaignObservationStore? = null

        /** Connect the singleton to Room after the Hilt database graph is ready. */
        internal fun attachObservationStore(store: CampaignObservationStore) {
            observationStore = store
            persistenceScope.launch {
                runCatching {
                    store.prune(
                        before = System.currentTimeMillis() - RETENTION_MS,
                        maxRows = MAX_PERSISTED_OBSERVATIONS,
                    )
                }
            }
        }

        fun recordCall(
            number: String,
            sourceIds: Set<String> = emptySet(),
        ) {
            val prefix = extractNpaNxx(number) ?: return
            val now = System.currentTimeMillis()
            val sanitizedSources =
                sourceIds
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .map { it.take(MAX_SOURCE_ID_LENGTH) }
                    .take(MAX_SOURCE_IDS_PER_OBSERVATION)
                    .toSet()
            val shouldPrune =
                synchronized(lock) {
                    pruneExpiredEntries(now)
                    recentPrefixes.getOrPut(prefix) { mutableListOf() }.add(now)
                    recentNumbers.getOrPut(prefix) { mutableListOf() }.add(number)
                    recentSourceIds.getOrPut(prefix) { mutableListOf() }.add(sanitizedSources)
                    trimTrackedPrefix(prefix)
                    val previousPrune = lastPersistencePruneAt.get()
                    now - previousPrune >= PERSISTENCE_PRUNE_INTERVAL_MS &&
                        lastPersistencePruneAt.compareAndSet(previousPrune, now)
                }
            val store = observationStore ?: return
            persistenceScope.launch {
                runCatching {
                    store.record(
                        CampaignObservation(
                            number = number,
                            prefix = prefix,
                            observedAt = now,
                            sourceIds = sanitizedSources.sorted().joinToString(","),
                        ),
                    )
                    if (shouldPrune) {
                        store.prune(
                            before = now - RETENTION_MS,
                            maxRows = MAX_PERSISTED_OBSERVATIONS,
                        )
                    }
                }
                synchronized(lock) { persistedCache.remove(prefix) }
            }
        }

        /** Fast in-memory check retained for callers that cannot suspend. */
        fun isActiveCampaign(number: String): Boolean {
            val prefix = extractNpaNxx(number) ?: return false
            val now = System.currentTimeMillis()
            synchronized(lock) {
                pruneExpiredEntries(now)
                return buildEvidence(prefix, memorySamples(prefix), now).isActive
            }
        }

        /** Combine current-process observations with persisted evidence. */
        suspend fun getCampaignEvidence(number: String): CampaignEvidence? {
            val prefix = extractNpaNxx(number) ?: return null
            val now = System.currentTimeMillis()
            val cutoff = now - WINDOW_MS
            val memory =
                synchronized(lock) {
                    pruneExpiredEntries(now)
                    memorySamples(prefix)
                }
            val persisted = loadPersisted(prefix, cutoff, now)
            return buildEvidence(
                prefix,
                memory +
                    persisted.map { observation ->
                        ObservationSample(
                            number = observation.number,
                            observedAt = observation.observedAt,
                            sourceIds = observation.sourceIds.asSourceIdSet(),
                        )
                    },
                now,
            )
        }

        fun getActiveCampaigns(): List<Pair<String, Int>> {
            val now = System.currentTimeMillis()
            synchronized(lock) {
                pruneExpiredEntries(now)
                return recentPrefixes.keys
                    .mapNotNull { prefix ->
                        val evidence = buildEvidence(prefix, memorySamples(prefix), now)
                        evidence.takeIf(CampaignEvidence::isActive)?.let { prefix to it.distinctNumberCount }
                    }.sortedByDescending { it.second }
            }
        }

        private suspend fun loadPersisted(
            prefix: String,
            cutoff: Long,
            now: Long,
        ): List<CampaignObservation> {
            val store = observationStore ?: return emptyList()
            synchronized(lock) {
                persistedCache[prefix]?.let { cached ->
                    if (now - cached.loadedAt < PERSISTED_CACHE_TTL_MS) {
                        return cached.observations.filter { it.observedAt > cutoff }
                    }
                }
            }
            val observations =
                try {
                    store.load(prefix, cutoff)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    emptyList()
                }
            synchronized(lock) {
                if (persistedCache.size >= MAX_CACHED_PREFIXES && prefix !in persistedCache) {
                    persistedCache.keys.firstOrNull()?.let(persistedCache::remove)
                }
                persistedCache[prefix] = PersistedPrefixCache(now, observations)
            }
            return observations.filter { it.observedAt > cutoff }
        }

        private fun buildEvidence(
            prefix: String,
            samples: List<ObservationSample>,
            now: Long,
        ): CampaignEvidence {
            val cutoff = now - WINDOW_MS
            val byKey = linkedMapOf<String, MutableSet<String>>()
            val numbers = mutableMapOf<String, Int>()
            samples
                .filter { it.observedAt > cutoff && it.number.isNotBlank() }
                .forEach { sample ->
                    val key = "${sample.number}|${sample.observedAt}"
                    byKey.getOrPut(key) { mutableSetOf() }.addAll(sample.sourceIds)
                }
            byKey.keys.forEach { key ->
                val number = key.substringBefore('|')
                numbers[number] = (numbers[number] ?: 0) + 1
            }
            val sourceAgreement =
                samples
                    .filter { it.observedAt > cutoff }
                    .flatMap { it.sourceIds }
                    .toSet()
                    .size
            return CampaignEvidence(
                prefix = prefix,
                observationCount = byKey.size,
                distinctNumberCount = numbers.size,
                repeatedNumberCount = numbers.count { it.value > 1 },
                sourceAgreementCount = sourceAgreement,
            )
        }

        private fun memorySamples(prefix: String): List<ObservationSample> {
            val timestamps = recentPrefixes[prefix].orEmpty()
            val numbers = recentNumbers[prefix].orEmpty()
            val sources = recentSourceIds[prefix].orEmpty()
            return timestamps.indices.map { index ->
                ObservationSample(
                    number = numbers.getOrNull(index).orEmpty(),
                    observedAt = timestamps[index],
                    sourceIds = sources.getOrNull(index).orEmpty(),
                )
            }
        }

        private fun pruneExpiredEntries(now: Long) {
            recentNumbers.keys.retainAll(recentPrefixes.keys)
            recentSourceIds.keys.retainAll(recentPrefixes.keys)
            recentPrefixes.entries.removeAll { (prefix, timestamps) ->
                val numbers = recentNumbers[prefix].orEmpty()
                val sources = recentSourceIds[prefix].orEmpty()
                val keep = timestamps.indices.filter { index -> now - timestamps[index] <= WINDOW_MS }
                val keptTimestamps = keep.map(timestamps::get)
                val keptNumbers = keep.mapNotNull(numbers::getOrNull)
                val keptSources = keep.mapNotNull(sources::getOrNull)
                timestamps.clear()
                timestamps.addAll(keptTimestamps)
                if (keptNumbers.isEmpty()) recentNumbers.remove(prefix) else recentNumbers[prefix] = keptNumbers.toMutableList()
                if (keptSources.isEmpty()) recentSourceIds.remove(prefix) else recentSourceIds[prefix] = keptSources.toMutableList()
                timestamps.isEmpty()
            }
        }

        private fun trimTrackedPrefix(prefix: String) {
            val timestamps = recentPrefixes[prefix] ?: return
            while (timestamps.size > MAX_OBSERVATIONS_PER_PREFIX) {
                timestamps.removeAt(0)
                recentNumbers[prefix]?.removeFirstOrNull()
                recentSourceIds[prefix]?.removeFirstOrNull()
            }
            if (recentPrefixes.size <= MAX_TRACKED_PREFIXES) return
            while (recentPrefixes.size > MAX_TRACKED_PREFIXES) {
                val stalest =
                    recentPrefixes.entries
                        .minByOrNull { (_, times) -> times.maxOrNull() ?: Long.MIN_VALUE }
                        ?.key ?: break
                recentPrefixes.remove(stalest)
                recentNumbers.remove(stalest)
                recentSourceIds.remove(stalest)
            }
        }

        private fun extractNpaNxx(number: String): String? {
            val digits = filterAsciiDigits(number)
            val normalized =
                when {
                    digits.length == 11 && digits.startsWith("1") -> digits.substring(1)
                    digits.length == 10 -> digits
                    else -> return null
                }
            return normalized.substring(0, 6)
        }

        companion object {
            const val MIN_DISTINCT_NUMBERS = 5

            val shared: CampaignDetector = CampaignDetector()

            private const val WINDOW_MS = 3_600_000L
            private const val RETENTION_MS = 7 * 86_400_000L
            private const val MAX_OBSERVATIONS_PER_PREFIX = 64
            private const val MAX_TRACKED_PREFIXES = 1000
            private const val MAX_PERSISTED_OBSERVATIONS = 2000
            private const val MAX_SOURCE_IDS_PER_OBSERVATION = 8
            private const val MAX_SOURCE_ID_LENGTH = 64
            private const val PERSISTENCE_PRUNE_INTERVAL_MS = 15 * 60_000L
            private const val PERSISTED_CACHE_TTL_MS = 15_000L
            private const val MAX_CACHED_PREFIXES = 128

            fun recordCall(number: String) {
                shared.recordCall(number)
            }

            fun isActiveCampaign(number: String): Boolean = shared.isActiveCampaign(number)

            fun getActiveCampaigns(): List<Pair<String, Int>> = shared.getActiveCampaigns()
        }
    }

private fun String.asSourceIdSet(): Set<String> =
    split(',')
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()
