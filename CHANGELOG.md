# Changelog

All notable changes to CallShield will be documented in this file.

## [v1.5.0] - 2026-04-15

### Fixed
- SpamMLScorer race condition: replaced 6 independent @Volatile fields with single atomic ModelState snapshot to prevent half-updated model reads during concurrent scoring
- WildcardRule glob-to-regex: all regex metacharacters (`.`, `(`, `)`, `[`, `]`, etc.) now properly escaped — previously only `+` was escaped, causing false matches on patterns like "212.555*"
- Frequency auto-escalation: unbounded time window caused legitimate callers with 3+ calls over months to be auto-blocked; now uses 7-day sliding window
- RcsNotificationListener: replaced verbose `scope.coroutineContext[Job]?.cancel()` with idiomatic `scope.cancel()`
- SyncWorker: non-retryable failures (HTTP 404) were silently reported as success to WorkManager; now correctly returns Result.failure()
- SpamActionReceiver: bare CoroutineScope without SupervisorJob could crash before pendingResult.finish() on uncaught exceptions
- MainViewModel scan guard: TOCTOU race allowed duplicate concurrent call log / SMS scans; flag now set before coroutine launch
- isWangiriCountryCode: Caribbean +1 NPAs (876 Jamaica, 284 BVI, 649 Turks & Caicos, etc.) were never detected as wangiri due to overly broad US/CA exemption

### Performance
- Consolidated 7 separate OkHttpClient instances into shared `HttpClient.shared` singleton with per-caller derived builders — eliminates redundant connection pools and thread pools
- Cached hot-path Room queries (prefixes, wildcards, keyword rules) behind @Volatile lazy fields with write-through invalidation — removes per-call disk I/O from the 5-second screening deadline
- DataStore settings in isSpam(): single `dataStore.data.first()` read replaces 8+ separate Flow .first() collector operations
- CampaignDetector.trimTrackedPrefixes: replaced O(n log n) sort with O(n) min-scan inside synchronized block

### Hardening
- WildcardRule: user-provided regex patterns capped at 200 chars to guard against ReDoS on the call screening path
- SmsContentAnalyzer: URL regex pattern length-capped at 2048 chars to prevent ReDoS on pathological SMS bodies

## [v1.4.0]

- Changed: Update hot list + merge community reports 2026-04-13T15:33
- Changed: Update hot list + merge community reports 2026-04-13T14:38
- Changed: Update hot list + merge community reports 2026-04-13T13:40
- Changed: Update hot list + merge community reports 2026-04-13T12:56
- Changed: Update hot list + merge community reports 2026-04-13T12:29
- Changed: Update hot list + merge community reports 2026-04-13T11:31
- Changed: Update hot list + merge community reports 2026-04-13T10:36
- Changed: Update hot list + merge community reports 2026-04-13T09:41
- Changed: Update hot list + merge community reports 2026-04-13T08:47
- Changed: Update hot list + merge community reports 2026-04-13T08:44
