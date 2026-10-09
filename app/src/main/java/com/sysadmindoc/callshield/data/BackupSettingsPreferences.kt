package com.sysadmindoc.callshield.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.sysadmindoc.callshield.data.BackupRestore.BackupExternalBlocklist
import com.sysadmindoc.callshield.data.BackupRestore.BackupSettings
import com.sysadmindoc.callshield.data.model.ExternalBlocklistSubscription
import com.sysadmindoc.callshield.data.remote.FeedMirror
import com.sysadmindoc.callshield.data.repository.sanitizeAppTheme
import com.sysadmindoc.callshield.service.AnswerHangUpController
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// Settings half of a backup: reading the DataStore snapshot into a
// BackupSettings, bounding what a (possibly hostile) backup carries, and
// writing it back. Pure functions over Preferences, kept out of BackupRestore
// so each file stays readable.

/** At most this many lists come in from one backup. */
private const val MAX_RESTORED_EXTERNAL_BLOCKLISTS = 50
private const val MAX_EXTERNAL_BLOCKLIST_LABEL_LENGTH = 100

// The same JSON SettingsRepository keeps the subscriptions in.
private val externalBlocklistsAdapter =
    Moshi
        .Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
        .adapter<List<ExternalBlocklistSubscription>>(
            Types.newParameterizedType(List::class.java, ExternalBlocklistSubscription::class.java),
        )

internal fun Preferences.externalBlocklistSubscriptions(): List<ExternalBlocklistSubscription> =
    this[SpamRepository.KEY_EXTERNAL_BLOCKLIST_SUBSCRIPTIONS]
        ?.takeIf { it.isNotBlank() }
        ?.let { raw -> runCatching { externalBlocklistsAdapter.fromJson(raw) }.getOrNull() }
        .orEmpty()
        .filter { it.id.isNotBlank() && it.url.isNotBlank() }

internal fun clampHangUpDelaySeconds(value: Int?): Int =
    value?.coerceIn(
        AnswerHangUpController.MIN_DELAY_SECONDS,
        AnswerHangUpController.MAX_DELAY_SECONDS,
    ) ?: AnswerHangUpController.DEFAULT_DELAY_SECONDS

@Suppress("LongMethod")
internal fun Preferences.toBackupSettings(): BackupSettings =
    BackupSettings(
        blockCallsEnabled = this[SpamRepository.KEY_BLOCK_CALLS] ?: true,
        blockSmsEnabled = this[SpamRepository.KEY_BLOCK_SMS] ?: true,
        blockUnknownEnabled = this[SpamRepository.KEY_BLOCK_UNKNOWN] ?: false,
        stirShakenEnabled = this[SpamRepository.KEY_STIR_SHAKEN] ?: true,
        stirTrustedAllowEnabled = this[SpamRepository.KEY_STIR_TRUSTED_ALLOW] ?: true,
        autoMuteLowConfidenceEnabled = this[SpamRepository.KEY_AUTOMUTE_LOW_CONFIDENCE] ?: false,
        neighborSpoofEnabled = this[SpamRepository.KEY_NEIGHBOR_SPOOF] ?: true,
        heuristicsEnabled = this[SpamRepository.KEY_HEURISTICS] ?: true,
        smsContentEnabled = this[SpamRepository.KEY_SMS_CONTENT] ?: true,
        smsBurstEnabled = this[SpamRepository.KEY_SMS_BURST] ?: true,
        urlhausStripQueryEnabled = this[SpamRepository.KEY_URL_STRIP_QUERY] ?: true,
        urlhausRemoteLookupEnabled = this[SpamRepository.KEY_REMOTE_URL_LOOKUP] ?: false,
        liveCallerEnrichmentEnabled = this[SpamRepository.KEY_LIVE_CALLER_ENRICHMENT] ?: false,
        contactWhitelistEnabled = this[SpamRepository.KEY_CONTACT_WHITELIST] ?: true,
        contactsOnlyEnabled = this[SpamRepository.KEY_CONTACTS_ONLY] ?: false,
        dbPrefixExpansionEnabled = this[SpamRepository.KEY_DB_PREFIX_EXPANSION] ?: false,
        aggressiveModeEnabled = this[SpamRepository.KEY_AGGRESSIVE_MODE] ?: false,
        answeredCallerTrustEnabled = this[SpamRepository.KEY_ANSWERED_CALLER_TRUST] ?: true,
        answeredCallerThreshold =
            this[SpamRepository.KEY_ANSWERED_CALLER_THRESHOLD]
                ?: CallbackDetector.DEFAULT_ANSWERED_CALLER_THRESHOLD,
        answeredCallerWindowDays =
            this[SpamRepository.KEY_ANSWERED_CALLER_WINDOW_DAYS]
                ?: CallbackDetector.DEFAULT_ANSWERED_CALLER_WINDOW_DAYS,
        emergencyCallbackGraceEnabled = this[SpamRepository.KEY_EMERGENCY_CALLBACK_GRACE] ?: true,
        emergencyCallbackWindowMinutes =
            this[SpamRepository.KEY_EMERGENCY_CALLBACK_WINDOW_MINUTES]
                ?: CallbackDetector.DEFAULT_EMERGENCY_CALLBACK_WINDOW_MINUTES,
        timeBlockEnabled = this[SpamRepository.KEY_TIME_BLOCK] ?: false,
        timeBlockStartHour = this[SpamRepository.KEY_TIME_BLOCK_START] ?: 22,
        timeBlockEndHour = this[SpamRepository.KEY_TIME_BLOCK_END] ?: 7,
        frequencyEscalationEnabled = this[SpamRepository.KEY_FREQ_ESCALATION] ?: true,
        frequencyThreshold = this[SpamRepository.KEY_FREQ_THRESHOLD] ?: 3,
        autoCleanupEnabled = this[SpamRepository.KEY_AUTO_CLEANUP] ?: false,
        cleanupDays = this[SpamRepository.KEY_CLEANUP_DAYS] ?: 30,
        mlScorerEnabled = this[SpamRepository.KEY_ML_SCORER] ?: true,
        rcsFilterEnabled = this[SpamRepository.KEY_RCS_FILTER] ?: true,
        postCallScreenEnabled = this[SpamRepository.KEY_POST_CALL_SCREEN] ?: false,
        silentVoicemailEnabled = this[SpamRepository.KEY_SILENT_VOICEMAIL] ?: false,
        answerHangUpEnabled = this[SpamRepository.KEY_ANSWER_HANG_UP] ?: false,
        hangUpDelaySeconds = clampHangUpDelaySeconds(this[SpamRepository.KEY_HANG_UP_DELAY_SECONDS]),
        pushAlertEnabled = this[SpamRepository.KEY_PUSH_ALERT] ?: true,
        pushAlertDisabledPackages = (this[SpamRepository.KEY_PUSH_ALERT_DISABLED] ?: emptySet()).sorted(),
        regionBlockEnabled = this[SpamRepository.KEY_REGION_BLOCK] ?: false,
        allowedRegions =
            RegionRules.normalizeRegionCodes(this[SpamRepository.KEY_ALLOWED_REGIONS].orEmpty()).sorted(),
        cnapTrustPatterns =
            RegionRules.normalizeNamePatterns(this[SpamRepository.KEY_CNAP_TRUST_PATTERNS].orEmpty()).sorted(),
        cnapBlockPatterns =
            RegionRules.normalizeNamePatterns(this[SpamRepository.KEY_CNAP_BLOCK_PATTERNS].orEmpty()).sorted(),
        categoryCallActions =
            CategoryCallPolicy.sanitize(this[SpamRepository.KEY_CATEGORY_CALL_ACTIONS].orEmpty()).sorted(),
        selectedContactGroups =
            ContactGroupCatalog
                .preserveScope(this[SpamRepository.KEY_SELECTED_CONTACT_GROUPS].orEmpty())
                .sorted(),
        outgoingRiskWarningEnabled = this[SpamRepository.KEY_OUTGOING_RISK_WARNING] ?: false,
        activeProfileName = this[SpamRepository.KEY_ACTIVE_PROFILE],
        // Preserve "never customized" as null: resolving the default
        // set into a concrete list here (and writing it back on
        // restore) permanently pinned the defaults, so sources added
        // to the catalog later never auto-enabled for restored users.
        // Mirrors the pushAlertDisabledPackages empty->remove pattern.
        notificationScreeningPackages =
            this[SpamRepository.KEY_NOTIFICATION_SCREENING_PACKAGES]
                ?.let { NotificationScreeningSources.enabledPackages(it).sorted() },
        enabledRegulatoryPrefixes =
            RegulatoryPrefix.entries
                .filter { this[it.key] == true }
                .map { it.key.name }
                .sorted(),
        meetingModeEnabled = this[SpamRepository.KEY_MEETING_MODE] ?: false,
        meetingModeApps = this[SpamRepository.KEY_MEETING_MODE_APPS].orEmpty().sorted(),
        outgoingCallHoldEnabled = this[SpamRepository.KEY_OUTGOING_CALL_HOLD] ?: false,
        appTheme = sanitizeAppTheme(this[SpamRepository.KEY_APP_THEME]),
        appUpdateChecksEnabled = this[SpamRepository.KEY_APP_UPDATE_CHECKS] ?: false,
        feedMirrorUrl = this[SpamRepository.KEY_FEED_MIRROR_URL].orEmpty(),
        externalBlocklists =
            externalBlocklistSubscriptions().map {
                BackupExternalBlocklist(it.url, it.label, it.enabled, it.numberPlan, it.catalogId)
            },
    )

/**
 * The lists a backup may name: HTTPS addresses, normalized as when they were
 * added and each once, a label of sane length, and a number plan or catalog
 * id only when the catalog could have declared it.
 */
private fun sanitizeExternalBlocklists(lists: List<BackupExternalBlocklist>): List<BackupExternalBlocklist> {
    val urls = mutableSetOf<String>()
    return lists
        .mapNotNull { list ->
            val url = runCatching { ExternalBlocklistParser.validateHttpUrl(list.url) }.getOrNull() ?: return@mapNotNull null
            list.copy(
                url = url,
                label =
                    list.label
                        .trim()
                        .take(MAX_EXTERNAL_BLOCKLIST_LABEL_LENGTH)
                        .ifBlank { url.toHttpUrlOrNull()?.host.orEmpty() },
                numberPlan = list.numberPlan?.let(ListCatalog::usablePlanOrNull),
                catalogId = list.catalogId.takeIf(ListCatalog::isUsableId).orEmpty(),
            )
        }.filter { urls.add(it.url) }
}

/**
 * These settings with the phone's [current] lists kept as they are and the
 * backup's new ones added after them. A restore never drops a list, which
 * would leave its numbers with nothing to refresh or remove them. This is
 * where a backup's lists are checked; the phone's own are never re-checked,
 * since one added before addresses had to be HTTPS would fail and be dropped.
 */
internal fun BackupSettings.addingExternalBlocklistsTo(current: List<BackupExternalBlocklist>?): BackupSettings {
    val incoming = sanitizeExternalBlocklists(externalBlocklists ?: return this)
    val kept = current.orEmpty()
    val keptUrls = kept.map { it.url }.toSet()
    val added = incoming.filter { it.url !in keptUrls }.take(MAX_RESTORED_EXTERNAL_BLOCKLISTS)
    return copy(externalBlocklists = kept + added)
}

/** The sources of this phone's lists that [desired] leaves out, so their numbers can go too. */
internal fun Preferences.droppedExternalBlocklistSources(desired: BackupSettings?): List<String> {
    val keptUrls = desired?.externalBlocklists?.map { it.url }?.toSet() ?: return emptyList()
    return externalBlocklistSubscriptions().filter { it.url !in keptUrls }.map { it.source }
}

internal fun BackupSettings.sanitized(): BackupSettings =
    copy(
        answeredCallerThreshold = answeredCallerThreshold.coerceIn(1, 10),
        answeredCallerWindowDays = answeredCallerWindowDays.coerceIn(1, 365),
        emergencyCallbackWindowMinutes = emergencyCallbackWindowMinutes.coerceIn(15, 360),
        timeBlockStartHour = BackupRestore.sanitizeScheduleHour(timeBlockStartHour),
        timeBlockEndHour = BackupRestore.sanitizeScheduleHour(timeBlockEndHour),
        frequencyThreshold = frequencyThreshold.coerceIn(1, 25),
        cleanupDays = cleanupDays.coerceIn(1, 365),
        hangUpDelaySeconds = clampHangUpDelaySeconds(hangUpDelaySeconds),
        pushAlertDisabledPackages =
            pushAlertDisabledPackages
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted(),
        allowedRegions = RegionRules.normalizeRegionCodes(allowedRegions).sorted(),
        cnapTrustPatterns = RegionRules.normalizeNamePatterns(cnapTrustPatterns).sorted(),
        cnapBlockPatterns = RegionRules.normalizeNamePatterns(cnapBlockPatterns).sorted(),
        categoryCallActions = CategoryCallPolicy.sanitize(categoryCallActions).sorted(),
        selectedContactGroups = ContactGroupCatalog.preserveScope(selectedContactGroups).sorted(),
        activeProfileName = activeProfileName?.trim()?.ifBlank { null },
        notificationScreeningPackages =
            notificationScreeningPackages
                ?.map { it.trim() }
                ?.filter { NotificationScreeningSources.sourceFor(it) != null }
                ?.distinct()
                ?.sorted(),
        enabledRegulatoryPrefixes =
            enabledRegulatoryPrefixes
                .map { it.trim() }
                .filter { name -> RegulatoryPrefix.entries.any { it.key.name == name } }
                .distinct()
                .sorted(),
        meetingModeApps =
            meetingModeApps
                .map { it.trim() }
                .filter { it in MeetingModeRegistry.MEETING_APPS }
                .distinct()
                .sorted(),
        // A theme or mirror this version can't use is dropped, leaving the current one.
        appTheme = appTheme?.takeIf { sanitizeAppTheme(it) == it },
        feedMirrorUrl = feedMirrorUrl?.let { if (it.isEmpty()) it else FeedMirror.normalize(it) },
        // Lists are checked where a backup's enter (addingExternalBlocklistsTo).
        // Here they are the phone's own as often as a backup's: a rollback or
        // an Undo writes the phone's snapshot back through this.
    )

@Suppress("LongMethod")
internal fun BackupSettings.writeTo(preferences: MutablePreferences) {
    preferences[SpamRepository.KEY_BLOCK_CALLS] = blockCallsEnabled
    preferences[SpamRepository.KEY_BLOCK_SMS] = blockSmsEnabled
    preferences[SpamRepository.KEY_BLOCK_UNKNOWN] = blockUnknownEnabled
    preferences[SpamRepository.KEY_STIR_SHAKEN] = stirShakenEnabled
    preferences[SpamRepository.KEY_STIR_TRUSTED_ALLOW] = stirTrustedAllowEnabled
    preferences[SpamRepository.KEY_AUTOMUTE_LOW_CONFIDENCE] = autoMuteLowConfidenceEnabled
    preferences[SpamRepository.KEY_NEIGHBOR_SPOOF] = neighborSpoofEnabled
    preferences[SpamRepository.KEY_HEURISTICS] = heuristicsEnabled
    preferences[SpamRepository.KEY_SMS_CONTENT] = smsContentEnabled
    preferences[SpamRepository.KEY_SMS_BURST] = smsBurstEnabled
    preferences[SpamRepository.KEY_URL_STRIP_QUERY] = urlhausStripQueryEnabled
    preferences[SpamRepository.KEY_REMOTE_URL_LOOKUP] = urlhausRemoteLookupEnabled
    preferences[SpamRepository.KEY_LIVE_CALLER_ENRICHMENT] = liveCallerEnrichmentEnabled
    preferences[SpamRepository.KEY_CONTACT_WHITELIST] = contactWhitelistEnabled
    preferences[SpamRepository.KEY_CONTACTS_ONLY] = contactsOnlyEnabled
    preferences[SpamRepository.KEY_OUTGOING_RISK_WARNING] = outgoingRiskWarningEnabled
    preferences[SpamRepository.KEY_DB_PREFIX_EXPANSION] = dbPrefixExpansionEnabled
    preferences[SpamRepository.KEY_AGGRESSIVE_MODE] = aggressiveModeEnabled
    preferences[SpamRepository.KEY_ANSWERED_CALLER_TRUST] = answeredCallerTrustEnabled
    preferences[SpamRepository.KEY_ANSWERED_CALLER_THRESHOLD] = answeredCallerThreshold
    preferences[SpamRepository.KEY_ANSWERED_CALLER_WINDOW_DAYS] = answeredCallerWindowDays
    preferences[SpamRepository.KEY_EMERGENCY_CALLBACK_GRACE] = emergencyCallbackGraceEnabled
    preferences[SpamRepository.KEY_EMERGENCY_CALLBACK_WINDOW_MINUTES] = emergencyCallbackWindowMinutes
    preferences[SpamRepository.KEY_TIME_BLOCK] = timeBlockEnabled
    preferences[SpamRepository.KEY_TIME_BLOCK_START] = timeBlockStartHour
    preferences[SpamRepository.KEY_TIME_BLOCK_END] = timeBlockEndHour
    preferences[SpamRepository.KEY_FREQ_ESCALATION] = frequencyEscalationEnabled
    preferences[SpamRepository.KEY_FREQ_THRESHOLD] = frequencyThreshold
    preferences[SpamRepository.KEY_AUTO_CLEANUP] = autoCleanupEnabled
    preferences[SpamRepository.KEY_CLEANUP_DAYS] = cleanupDays
    preferences[SpamRepository.KEY_ML_SCORER] = mlScorerEnabled
    preferences[SpamRepository.KEY_RCS_FILTER] = rcsFilterEnabled
    preferences[SpamRepository.KEY_POST_CALL_SCREEN] = postCallScreenEnabled
    preferences[SpamRepository.KEY_SILENT_VOICEMAIL] = silentVoicemailEnabled
    preferences[SpamRepository.KEY_ANSWER_HANG_UP] = answerHangUpEnabled
    preferences[SpamRepository.KEY_HANG_UP_DELAY_SECONDS] = hangUpDelaySeconds
    preferences[SpamRepository.KEY_PUSH_ALERT] = pushAlertEnabled
    if (pushAlertDisabledPackages.isEmpty()) {
        preferences.remove(SpamRepository.KEY_PUSH_ALERT_DISABLED)
    } else {
        preferences[SpamRepository.KEY_PUSH_ALERT_DISABLED] = pushAlertDisabledPackages.toSet()
    }
    if (allowedRegions.isEmpty()) {
        preferences.remove(SpamRepository.KEY_ALLOWED_REGIONS)
    } else {
        preferences[SpamRepository.KEY_ALLOWED_REGIONS] = allowedRegions.toSet()
    }
    preferences[SpamRepository.KEY_REGION_BLOCK] = regionBlockEnabled && allowedRegions.isNotEmpty()
    RegulatoryPrefix.entries.forEach { preferences[it.key] = it.key.name in enabledRegulatoryPrefixes }
    preferences[SpamRepository.KEY_MEETING_MODE] = meetingModeEnabled
    if (meetingModeApps.isEmpty()) {
        preferences.remove(SpamRepository.KEY_MEETING_MODE_APPS)
    } else {
        preferences[SpamRepository.KEY_MEETING_MODE_APPS] = meetingModeApps.toSet()
    }
    if (cnapTrustPatterns.isEmpty()) {
        preferences.remove(SpamRepository.KEY_CNAP_TRUST_PATTERNS)
    } else {
        preferences[SpamRepository.KEY_CNAP_TRUST_PATTERNS] = cnapTrustPatterns.toSet()
    }
    if (cnapBlockPatterns.isEmpty()) {
        preferences.remove(SpamRepository.KEY_CNAP_BLOCK_PATTERNS)
    } else {
        preferences[SpamRepository.KEY_CNAP_BLOCK_PATTERNS] = cnapBlockPatterns.toSet()
    }
    if (categoryCallActions.isEmpty()) {
        preferences.remove(SpamRepository.KEY_CATEGORY_CALL_ACTIONS)
    } else {
        preferences[SpamRepository.KEY_CATEGORY_CALL_ACTIONS] = categoryCallActions.toSet()
    }
    if (selectedContactGroups.isEmpty()) {
        preferences.remove(SpamRepository.KEY_SELECTED_CONTACT_GROUPS)
    } else {
        preferences[SpamRepository.KEY_SELECTED_CONTACT_GROUPS] = selectedContactGroups.toSet()
    }
    if (activeProfileName == null) {
        preferences.remove(SpamRepository.KEY_ACTIVE_PROFILE)
    } else {
        preferences[SpamRepository.KEY_ACTIVE_PROFILE] = activeProfileName
    }
    outgoingCallHoldEnabled?.let { preferences[SpamRepository.KEY_OUTGOING_CALL_HOLD] = it }
    appTheme?.let { preferences[SpamRepository.KEY_APP_THEME] = it }
    when (appUpdateChecksEnabled) {
        true -> preferences[SpamRepository.KEY_APP_UPDATE_CHECKS] = true
        false -> preferences.remove(SpamRepository.KEY_APP_UPDATE_CHECKS)
        null -> Unit
    }
    when (feedMirrorUrl) {
        null -> Unit
        "" -> preferences.remove(SpamRepository.KEY_FEED_MIRROR_URL)
        else -> preferences[SpamRepository.KEY_FEED_MIRROR_URL] = feedMirrorUrl
    }
    if (notificationScreeningPackages == null) {
        // Backup was taken with the follow-the-defaults sentinel (or is a
        // pre-v7 backup without the field): restore that state rather
        // than leaving whatever customization the device currently has.
        preferences.remove(SpamRepository.KEY_NOTIFICATION_SCREENING_PACKAGES)
    } else {
        preferences[SpamRepository.KEY_NOTIFICATION_SCREENING_PACKAGES] =
            notificationScreeningPackages.toSet()
    }
    externalBlocklists?.let { lists -> writeExternalBlocklists(preferences, lists) }
}

/**
 * Makes [lists] the phone's whole set of subscribed lists. A list it already
 * has stays exactly as it is, fetch state and switch included: turning a list
 * on downloads it and turning it off deletes its numbers, which only the
 * repository does. A new one starts unfetched, so the next refresh downloads
 * it. A list left out is dropped (see droppedExternalBlocklistSources).
 */
private fun writeExternalBlocklists(
    preferences: MutablePreferences,
    lists: List<BackupExternalBlocklist>,
) {
    val current = preferences.externalBlocklistSubscriptions().associateBy { it.url }
    val next =
        lists.map { list ->
            current[list.url] ?: ExternalBlocklistSubscription(
                id = ExternalBlocklistParser.idForUrl(list.url),
                label = list.label,
                url = list.url,
                enabled = list.enabled,
                numberPlan = list.numberPlan,
                catalogId = list.catalogId,
            )
        }
    if (next.isEmpty()) {
        preferences.remove(SpamRepository.KEY_EXTERNAL_BLOCKLIST_SUBSCRIPTIONS)
    } else {
        preferences[SpamRepository.KEY_EXTERNAL_BLOCKLIST_SUBSCRIPTIONS] =
            externalBlocklistsAdapter.toJson(next.sortedBy { it.label.lowercase() })
    }
}
